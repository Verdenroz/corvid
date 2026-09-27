use std::sync::Arc;

use corvid_rewrite::{Rewriter, header};
use indymilter::{
    Actions, Callbacks, Context, ContextActions, EomContext, NegotiateContext, Status,
};

use crate::session::{Outcome, Session};

const MARKER: &str = "X-Corvid-Rewrite";

// Milter permits several replace_body calls and the MTA appends them, so a body larger
// than one packet is sent in pieces rather than truncated.
const CHUNK: usize = 60_000;

pub fn callbacks(rewriter: Arc<Rewriter>) -> Callbacks<Session> {
    Callbacks::new()
        .on_negotiate(|context: &mut NegotiateContext<Session>, _, offered| {
            Box::pin(async move {
                // Request only the actions used, so a bug here cannot do more to a message.
                context.requested_actions = Actions::ADD_HEADER | Actions::REPLACE_BODY;
                // indymilter requests a NO_* skip per unregistered callback; Stalwart offers only
                // NOHELO and NOEOH, and asking for an option not offered fails negotiation.
                context.requested_opts &= offered;
                Status::Continue
            })
        })
        // A connection carries several messages, so the previous one is cleared here
        // rather than relying on DATA being the only entry point.
        .on_data(|context: &mut Context<Session>| {
            Box::pin(async move {
                context.data = Some(Session::default());
                Status::Continue
            })
        })
        .on_header(|context: &mut Context<Session>, name, value| {
            Box::pin(async move {
                context.data.get_or_insert_with(Session::default).header(
                    name.to_string_lossy().into_owned(),
                    value.to_string_lossy().into_owned(),
                );
                Status::Continue
            })
        })
        .on_body(|context: &mut Context<Session>, chunk| {
            Box::pin(async move {
                context
                    .data
                    .get_or_insert_with(Session::default)
                    .body_chunk(&chunk);
                Status::Continue
            })
        })
        .on_eom(move |context: &mut EomContext<Session>| {
            let rewriter = Arc::clone(&rewriter);
            Box::pin(async move {
                let Some(session) = context.data.take() else {
                    return Status::Continue;
                };
                match session.finish(&rewriter) {
                    // Marked even when clean, so a missing header means the milter never ran.
                    Outcome::Unchanged => {
                        let _ = context
                            .actions
                            .add_header(MARKER, header::Marked::Nothing.render())
                            .await;
                        Status::Continue
                    }
                    Outcome::Skipped => {
                        let _ = context
                            .actions
                            .add_header(MARKER, header::Marked::Skipped.render())
                            .await;
                        Status::Continue
                    }
                    Outcome::Replace { body, report } => {
                        for chunk in body.chunks(CHUNK) {
                            if context.actions.replace_body(chunk).await.is_err() {
                                return Status::Tempfail;
                            }
                        }
                        let _ = context
                            .actions
                            .add_header(
                                MARKER,
                                header::Marked::Rewritten(header::Summary::of(&report)).render(),
                            )
                            .await;
                        Status::Continue
                    }
                }
            })
        })
}
