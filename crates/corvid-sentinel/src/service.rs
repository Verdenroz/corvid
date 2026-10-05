use crate::decide::decide;
use crate::notice;
use crate::state::Seen;
use crate::webhook::AuthEvent;

/// Decides one webhook batch and returns the state with a notice appended for each alert, or
/// `None` when the batch changes nothing worth saving.
///
/// `seen` itself is never touched, so a caller that does not commit the result will decide the
/// same batch identically on retry rather than have it read as already handled.
pub fn handle(events: &[AuthEvent], seen: &Seen) -> Option<Seen> {
    let (alerts, mut next) = decide(events, seen);
    if alerts.is_empty()
        && next.identities == seen.identities
        && next.too_many_attempts_notified == seen.too_many_attempts_notified
    {
        return None;
    }
    for alert in &alerts {
        let (kind, title, detail) = notice::render(alert);
        next.push_notice(kind, alert.at.clone(), title.to_owned(), detail);
    }
    Some(next)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::{CredentialKind, Identity, NoticeKind};
    use crate::webhook::Kind;

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

    fn known() -> Identity {
        Identity {
            account: "alice@corvid.test".to_owned(),
            kind: CredentialKind::Primary,
            listener: "imaps".to_owned(),
            remote_ip: "203.0.113.9".to_owned(),
        }
    }

    #[test]
    fn a_new_sign_in_becomes_a_notice_and_the_identity_is_committed() {
        let next = handle(&[event()], &Seen::default()).unwrap();

        assert_eq!(next.notices.len(), 1);
        assert_eq!(next.notices[0].kind, NoticeKind::SignIn);
        assert_eq!(next.notices[0].at, "2026-09-27T02:08:58Z");
        assert!(next.identities.contains(&known()));
    }

    #[test]
    fn nothing_alertable_and_nothing_new_is_nothing_to_save() {
        let mut seen = Seen::default();
        seen.identities.insert(known());

        assert!(handle(&[event()], &seen).is_none());
    }

    #[test]
    fn handling_a_batch_leaves_the_input_state_alone() {
        let seen = Seen::default();

        handle(&[event()], &seen);

        assert!(seen.identities.is_empty());
        assert!(seen.notices.is_empty());
    }

    #[test]
    fn ids_keep_counting_across_batches() {
        let first = handle(&[event()], &Seen::default()).unwrap();
        let mut other = event();
        other.remote_ip = Some("203.0.113.10".to_owned());

        let second = handle(&[other], &first).unwrap();

        assert_eq!(
            second.notices.iter().map(|n| n.id).collect::<Vec<_>>(),
            [1, 2]
        );
    }
}
