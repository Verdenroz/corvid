use std::io;

use crate::decide::decide;
use crate::notice;
use crate::state::Seen;
use crate::webhook::AuthEvent;

pub trait Notifier {
    fn account_email(&self) -> &str;
    fn send(&self, message: &[u8]) -> io::Result<()>;
}

/// Decides one webhook batch and delivers it if anything is alertable.
///
/// Returns the state a successful delivery leaves. `seen` itself is never touched, so a caller
/// that gets an `Err` and does not commit the result will decide the same batch identically on
/// retry rather than have it read as already handled.
pub fn handle(events: &[AuthEvent], seen: &Seen, notifier: &impl Notifier) -> io::Result<Seen> {
    let (alerts, next) = decide(events, seen);
    if alerts.is_empty() {
        return Ok(next);
    }
    notifier.send(&notice::render(&alerts, notifier.account_email()))?;
    Ok(next)
}

#[cfg(test)]
mod tests {
    use std::cell::{Cell, RefCell};

    use super::*;
    use crate::state::{CredentialKind, Identity};
    use crate::webhook::Kind;

    #[derive(Default)]
    struct FakeMailbox {
        sent: RefCell<Vec<Vec<u8>>>,
        refuse: Cell<bool>,
    }

    impl Notifier for FakeMailbox {
        fn account_email(&self) -> &str {
            "alice@corvid.test"
        }

        fn send(&self, message: &[u8]) -> io::Result<()> {
            if self.refuse.get() {
                return Err(io::Error::other("the mail server refused"));
            }
            self.sent.borrow_mut().push(message.to_owned());
            Ok(())
        }
    }

    fn event() -> AuthEvent {
        AuthEvent {
            created_at: "2026-09-27T02:08:58Z".to_owned(),
            kind: Kind::Success,
            account: Some("alice@corvid.test".to_owned()),
            remote_ip: Some("203.0.113.9".to_owned()),
            listener: Some("imaps".to_owned()),
            details: None,
        }
    }

    #[test]
    fn a_new_sign_in_is_delivered_and_the_identity_is_committed() {
        let mailbox = FakeMailbox::default();
        let seen = Seen::default();

        let next = handle(&[event()], &seen, &mailbox).unwrap();

        assert_eq!(mailbox.sent.borrow().len(), 1);
        assert!(next.identities.contains(&Identity {
            account: "alice@corvid.test".to_owned(),
            kind: CredentialKind::Primary,
            listener: "imaps".to_owned(),
            remote_ip: "203.0.113.9".to_owned(),
        }));
    }

    #[test]
    fn nothing_alertable_sends_no_mail() {
        let mailbox = FakeMailbox::default();
        let mut seen = Seen::default();
        seen.identities.insert(Identity {
            account: "alice@corvid.test".to_owned(),
            kind: CredentialKind::Primary,
            listener: "imaps".to_owned(),
            remote_ip: "203.0.113.9".to_owned(),
        });

        handle(&[event()], &seen, &mailbox).unwrap();

        assert_eq!(mailbox.sent.borrow().len(), 0);
    }

    #[test]
    fn a_failed_delivery_leaves_the_identity_uncommitted() {
        let mailbox = FakeMailbox::default();
        mailbox.refuse.set(true);
        let seen = Seen::default();

        let error = handle(&[event()], &seen, &mailbox).unwrap_err();

        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert!(seen.identities.is_empty(), "seen itself is never mutated");
    }

    #[test]
    fn a_batch_that_failed_to_deliver_replays_identically_on_retry() {
        let mailbox = FakeMailbox::default();
        mailbox.refuse.set(true);
        let seen = Seen::default();

        handle(&[event()], &seen, &mailbox).unwrap_err();
        mailbox.refuse.set(false);
        let next = handle(&[event()], &seen, &mailbox).unwrap();

        assert_eq!(
            mailbox.sent.borrow().len(),
            1,
            "the retry is what got delivered"
        );
        assert_eq!(next.identities.len(), 1);
    }
}
