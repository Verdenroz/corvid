use crate::dates;
use crate::state::{CredentialKind, Identity, Seen};
use crate::webhook::{AuthEvent, Kind};

const APP_PASSWORD_DETAILS: &str = "Authenticated with app password";
/// How often a too-many-attempts or ban notice may repeat for the same address.
const BURST_WINDOW_SECS: i64 = 3600;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Alert {
    pub at: String,
    pub account: Option<String>,
    pub remote_ip: Option<String>,
    pub listener: Option<String>,
    pub reason: Reason,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reason {
    NewSignIn(CredentialKind),
    /// `details` did not match any known credential kind exactly, and is carried verbatim
    /// rather than guessed at: a reworded string from a Stalwart upgrade must not be silently
    /// read as the primary password.
    UnrecognisedCredential(String),
    /// A success event missing the account, address or listener a stable identity needs.
    Incomplete,
    TooManyAttempts,
    Banned,
    UnknownEvent(String),
}

/// Decides what to alert on in one webhook batch, against the account's sign-in history.
///
/// Nothing here is persisted; the caller commits `seen`'s mutations only once every alert has
/// actually been delivered, so a delivery failure replays as first sightings rather than
/// silently losing them.
pub fn decide(events: &[AuthEvent], seen: &mut Seen) -> Vec<Alert> {
    events
        .iter()
        .filter_map(|event| decide_one(event, seen))
        .collect()
}

fn decide_one(event: &AuthEvent, seen: &mut Seen) -> Option<Alert> {
    let reason = match &event.kind {
        Kind::Success => success(event, seen)?,
        Kind::TooManyAttempts => burst(event, seen, Reason::TooManyAttempts)?,
        Kind::Ban => burst(event, seen, Reason::Banned)?,
        Kind::Other(name) => Reason::UnknownEvent(name.clone()),
        Kind::Failed => return None,
    };
    Some(Alert {
        at: event.created_at.clone(),
        account: event.account.clone(),
        remote_ip: event.remote_ip.clone(),
        listener: event.listener.clone(),
        reason,
    })
}

fn success(event: &AuthEvent, seen: &mut Seen) -> Option<Reason> {
    let kind = match event.details.as_deref() {
        None => CredentialKind::Primary,
        Some(APP_PASSWORD_DETAILS) => CredentialKind::AppPassword,
        Some(other) => return Some(Reason::UnrecognisedCredential(other.to_owned())),
    };
    let (Some(account), Some(remote_ip), Some(listener)) =
        (&event.account, &event.remote_ip, &event.listener)
    else {
        return Some(Reason::Incomplete);
    };
    let identity = Identity {
        account: account.clone(),
        kind,
        listener: listener.clone(),
        remote_ip: remote_ip.clone(),
    };
    seen.identities
        .insert(identity)
        .then_some(Reason::NewSignIn(kind))
}

fn burst(event: &AuthEvent, seen: &mut Seen, reason: Reason) -> Option<Reason> {
    let Some(remote_ip) = &event.remote_ip else {
        return Some(reason);
    };
    let Some(now) = dates::seconds(&event.created_at) else {
        return Some(reason);
    };
    match seen.burst_notified.get(remote_ip) {
        Some(&last) if now - last < BURST_WINDOW_SECS => None,
        _ => {
            seen.burst_notified.insert(remote_ip.clone(), now);
            Some(reason)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event(kind: Kind) -> AuthEvent {
        AuthEvent {
            created_at: "2026-09-27T02:08:58Z".to_owned(),
            kind,
            account: Some("verde@corvid.test".to_owned()),
            remote_ip: Some("203.0.113.9".to_owned()),
            listener: Some("imaps".to_owned()),
            details: None,
        }
    }

    #[test]
    fn a_first_sign_in_alerts_as_new() {
        let mut seen = Seen::default();
        let alerts = decide(&[event(Kind::Success)], &mut seen);

        assert_eq!(alerts.len(), 1);
        assert_eq!(alerts[0].reason, Reason::NewSignIn(CredentialKind::Primary));
    }

    #[test]
    fn the_same_identity_again_does_not_alert() {
        let mut seen = Seen::default();
        decide(&[event(Kind::Success)], &mut seen);

        assert_eq!(decide(&[event(Kind::Success)], &mut seen), vec![]);
    }

    #[test]
    fn a_different_address_for_the_same_account_alerts_again() {
        let mut seen = Seen::default();
        decide(&[event(Kind::Success)], &mut seen);

        let mut elsewhere = event(Kind::Success);
        elsewhere.remote_ip = Some("198.51.100.4".to_owned());
        let alerts = decide(&[elsewhere], &mut seen);

        assert_eq!(alerts.len(), 1);
    }

    #[test]
    fn an_app_password_sign_in_is_tracked_separately_from_the_primary_password() {
        let mut seen = Seen::default();
        decide(&[event(Kind::Success)], &mut seen);

        let mut app = event(Kind::Success);
        app.details = Some(APP_PASSWORD_DETAILS.to_owned());
        let alerts = decide(&[app], &mut seen);

        assert_eq!(alerts.len(), 1);
        assert_eq!(
            alerts[0].reason,
            Reason::NewSignIn(CredentialKind::AppPassword)
        );
    }

    #[test]
    fn an_unrecognised_credential_string_always_alerts() {
        let mut seen = Seen::default();
        let mut unknown = event(Kind::Success);
        unknown.details = Some("Authenticated with a magic link".to_owned());

        for _ in 0..2 {
            let alerts = decide(&[unknown.clone()], &mut seen);
            assert_eq!(
                alerts,
                vec![Alert {
                    reason: Reason::UnrecognisedCredential(
                        "Authenticated with a magic link".to_owned()
                    ),
                    ..alerts[0].clone()
                }]
            );
        }
    }

    #[test]
    fn a_success_missing_the_address_still_alerts_rather_than_guessing() {
        let mut seen = Seen::default();
        let mut incomplete = event(Kind::Success);
        incomplete.remote_ip = None;

        let alerts = decide(&[incomplete], &mut seen);

        assert_eq!(
            alerts,
            vec![Alert {
                reason: Reason::Incomplete,
                ..alerts[0].clone()
            }]
        );
    }

    #[test]
    fn a_lone_failed_attempt_does_not_alert() {
        let mut seen = Seen::default();
        assert_eq!(decide(&[event(Kind::Failed)], &mut seen), vec![]);
    }

    #[test]
    fn too_many_attempts_alerts() {
        let mut seen = Seen::default();
        let alerts = decide(&[event(Kind::TooManyAttempts)], &mut seen);
        assert_eq!(alerts[0].reason, Reason::TooManyAttempts);
    }

    #[test]
    fn a_second_burst_from_the_same_address_within_an_hour_is_suppressed() {
        let mut seen = Seen::default();
        decide(&[event(Kind::Ban)], &mut seen);

        let mut soon_after = event(Kind::TooManyAttempts);
        soon_after.created_at = "2026-09-27T02:38:58Z".to_owned();
        assert_eq!(decide(&[soon_after], &mut seen), vec![]);
    }

    #[test]
    fn a_burst_an_hour_later_from_the_same_address_alerts_again() {
        let mut seen = Seen::default();
        decide(&[event(Kind::Ban)], &mut seen);

        let mut later = event(Kind::Ban);
        later.created_at = "2026-09-27T03:08:58Z".to_owned();
        let alerts = decide(&[later], &mut seen);

        assert_eq!(alerts.len(), 1);
    }

    #[test]
    fn an_unknown_event_type_alerts_by_name() {
        let mut seen = Seen::default();
        let alerts = decide(
            &[event(Kind::Other("auth.mfa-required".to_owned()))],
            &mut seen,
        );

        assert_eq!(
            alerts[0].reason,
            Reason::UnknownEvent("auth.mfa-required".to_owned())
        );
    }
}
