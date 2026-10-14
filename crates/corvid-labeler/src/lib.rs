mod actions;
mod cold_start;
mod dates;
mod jmap;
mod mailbox;
mod model;

use std::collections::BTreeSet;
use std::io;
use std::path::Path;

use corvid_ingest::{is_prior_keyword, open, save, sender_key};

pub use actions::{Delta, record};
pub use cold_start::{Budget, ColdStart, cold_start};
pub use jmap::Jmap;
pub use mailbox::{Changes, Mailbox, Message, Roles};
pub use model::{Model, Oracle};

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Summary {
    pub labelled: usize,
    pub written: usize,
    pub unkeyed: usize,
}

/// Reads what changed, learns from it, writes labels, and advances the cursor.
///
/// Every failure here stops before the cursor moves, so the poll repeats rather than skipping
/// messages. The dedup set absorbs the repeat.
pub fn poll(path: &Path, mailbox: &impl Mailbox) -> io::Result<Summary> {
    poll_observed(path, mailbox, |_| Ok(()))
}

/// `poll`, handing `observe` what changed before anything is labelled.
///
/// Corrupt state in `observe` stops the poll like any other; any other failure there is logged
/// and labeling goes on.
pub fn poll_observed(
    path: &Path,
    mailbox: &impl Mailbox,
    observe: impl FnOnce(&Delta) -> io::Result<()>,
) -> io::Result<Summary> {
    let mut ingest = open(path)?;
    let changes = mailbox.changes(ingest.cursor())?;
    // A counted id reported as created means the cursor is not this server's and the feed is
    // replaying. Assumes ids are never reused; if they are, this halts loudly on real traffic.
    if let Some(replayed) = changes.created.iter().find(|id| ingest.has_counted(id)) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("{replayed} is reported as newly created but has been seen before"),
        ));
    }

    let mut ids = changes.created.clone();
    ids.extend(changes.updated);
    ids.sort();
    ids.dedup();

    let messages = mailbox.fetch(&ids)?;
    let delta = Delta {
        messages: &messages,
        created: &changes.created,
        destroyed: &changes.destroyed,
    };
    match observe(&delta) {
        Err(error) if error.kind() == io::ErrorKind::InvalidData => return Err(error),
        Err(error) => tracing::warn!(%error, "action log not updated"),
        Ok(()) => {}
    }

    let mut summary = Summary::default();
    for message in messages {
        let results: Vec<&str> = message
            .authentication_results
            .iter()
            .map(String::as_str)
            .collect();
        let Some(key) = sender_key(&message.from, message.list_id.as_deref(), &results) else {
            summary.unkeyed += 1;
            continue;
        };

        // Learn before predicting, so a correction on this message informs its own label.
        ingest.reconcile(
            &message.id,
            &key,
            &message.keywords,
            message.received_at_days,
        );
        let wanted = ingest.label(&message.id, &key, message.received_at_days);
        summary.labelled += 1;

        let ours: BTreeSet<String> = message
            .keywords
            .iter()
            .filter(|keyword| is_prior_keyword(keyword))
            .cloned()
            .collect();
        let add: BTreeSet<String> = wanted.difference(&ours).cloned().collect();
        let remove: BTreeSet<String> = ours.difference(&wanted).cloned().collect();
        if !add.is_empty() || !remove.is_empty() {
            mailbox.apply_keywords(&message.id, &add, &remove)?;
            ingest.note_write();
            summary.written += 1;
        }
    }

    ingest.set_cursor(changes.new_state);
    save(path, &ingest)?;
    Ok(summary)
}
