use crate::dates;
use crate::state::{CredentialKind, Identity, Seen};
use crate::webhook::{AuthEvent, Kind};

const APP_PASSWORD_DETAILS: &str = "Authenticated with app password";
const API_KEY_DETAILS: &str = "Authenticated with API key";
/// How often a too-many-attempts notice may repeat for the same address.
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
    /// The account password was correct and the sign-in stopped at the TOTP step: the strongest
    /// signal this crate can get that someone holds the password, whether or not they ever
    /// produce a code. IMAP, SMTP and JMAP Basic auth have no way to supply one, so this is also
    /// what a mail app or service still using the account password looks like.
    PasswordAcceptedAwaitingCode,
    /// A success event missing the account, address or listener a stable identity needs.
    Incomplete,
    TooManyAttempts,
    Banned,
    UnknownEvent(String),
}

/// Decides what to alert on in one webhook batch, against the account's sign-in history.
///
/// Returns the alerts and the state as it would be after them, without touching `seen`: a
/// caller that fails to deliver the alerts can discard the result and decide the same batch
/// again, rather than have it read as already seen.
pub fn decide(events: &[AuthEvent], seen: &Seen) -> (Vec<Alert>, Seen) {
    let mut next = seen.clone();
    let alerts = events
        .iter()
        .filter_map(|event| decide_one(event, &mut next))
        .collect();
    (alerts, next)
}

fn decide_one(event: &AuthEvent, seen: &mut Seen) -> Option<Alert> {
    let reason = match &event.kind {
        Kind::Success => success(event, seen)?,
        Kind::MfaRequired => mfa_required(event, seen)?,
        Kind::TooManyAttempts => too_many_attempts(event, seen)?,
        // A ban on the VPN exit locks out every VPN user, so this alert is never rate-limited.
        Kind::Ban => Reason::Banned,
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
        Some(API_KEY_DETAILS) => CredentialKind::ApiKey,
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

/// Shares `seen.identities` with `success`, keyed the same way (account, listener, address) at
/// `CredentialKind::Primary`: whichever of the two events for one login arrives first marks the
/// identity seen, so a normal two-step login alerts once regardless of delivery order.
fn mfa_required(event: &AuthEvent, seen: &mut Seen) -> Option<Reason> {
    let (Some(account), Some(remote_ip), Some(listener)) =
        (&event.account, &event.remote_ip, &event.listener)
    else {
        return Some(Reason::Incomplete);
    };
    let identity = Identity {
        account: account.clone(),
        kind: CredentialKind::Primary,
        listener: listener.clone(),
        remote_ip: remote_ip.clone(),
    };
    seen.identities
        .insert(identity)
        .then_some(Reason::PasswordAcceptedAwaitingCode)
}

fn too_many_attempts(event: &AuthEvent, seen: &mut Seen) -> Option<Reason> {
    let Some(remote_ip) = &event.remote_ip else {
        return Some(Reason::TooManyAttempts);
    };
    let Some(now) = dates::seconds(&event.created_at) else {
        return Some(Reason::TooManyAttempts);
    };
    match seen.too_many_attempts_notified.get(remote_ip) {
        Some(&last) if now - last < BURST_WINDOW_SECS => None,
        _ => {
            seen.too_many_attempts_notified
                .insert(remote_ip.clone(), now);
            Some(Reason::TooManyAttempts)
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
            account: Some("alice@corvid.test".to_owned()),
            remote_ip: Some("203.0.113.9".to_owned()),
            listener: Some("imaps".to_owned()),
            details: None,
        }
    }

    /// Decides against `seen` and advances it, the way a caller does after a delivery succeeds.
    fn step(events: &[AuthEvent], seen: &mut Seen) -> Vec<Alert> {
        let (alerts, next) = decide(events, seen);
        *seen = next;
        alerts
    }

    #[test]
    fn a_first_sign_in_alerts_as_new() {
        let mut seen = Seen::default();
        let alerts = step(&[event(Kind::Success)], &mut seen);

        assert_eq!(alerts.len(), 1);
        assert_eq!(alerts[0].reason, Reason::NewSignIn(CredentialKind::Primary));
    }

    #[test]
    fn the_same_identity_again_does_not_alert() {
        let mut seen = Seen::default();
        step(&[event(Kind::Success)], &mut seen);

        assert_eq!(step(&[event(Kind::Success)], &mut seen), vec![]);
    }

    #[test]
    fn a_failed_delivery_leaves_the_next_decision_unchanged() {
        let seen = Seen::default();
        let events = [event(Kind::Success)];

        let (first, _discarded) = decide(&events, &seen);
        let (second, _) = decide(&events, &seen);

        assert_eq!(
            first, second,
            "the batch was never committed, so it replays identically"
        );
    }

    #[test]
    fn a_different_address_for_the_same_account_alerts_again() {
        let mut seen = Seen::default();
        step(&[event(Kind::Success)], &mut seen);

        let mut elsewhere = event(Kind::Success);
        elsewhere.remote_ip = Some("198.51.100.4".to_owned());
        let alerts = step(&[elsewhere], &mut seen);

        assert_eq!(alerts.len(), 1);
    }

    #[test]
    fn an_app_password_sign_in_is_tracked_separately_from_the_primary_password() {
        let mut seen = Seen::default();
        step(&[event(Kind::Success)], &mut seen);

        let mut app = event(Kind::Success);
        app.details = Some(APP_PASSWORD_DETAILS.to_owned());
        let alerts = step(&[app], &mut seen);

        assert_eq!(alerts.len(), 1);
        assert_eq!(
            alerts[0].reason,
            Reason::NewSignIn(CredentialKind::AppPassword)
        );
    }

    #[test]
    fn an_api_key_sign_in_alerts_once_per_identity() {
        let mut seen = Seen::default();
        let mut key = event(Kind::Success);
        key.details = Some(API_KEY_DETAILS.to_owned());

        let first = step(&[key.clone()], &mut seen);
        let second = step(&[key], &mut seen);

        assert_eq!(first.len(), 1);
        assert_eq!(first[0].reason, Reason::NewSignIn(CredentialKind::ApiKey));
        assert!(second.is_empty());
    }

    #[test]
    fn an_unrecognised_credential_string_always_alerts() {
        let mut seen = Seen::default();
        let mut unknown = event(Kind::Success);
        unknown.details = Some("Authenticated with a magic link".to_owned());

        for _ in 0..2 {
            let alerts = step(&[unknown.clone()], &mut seen);
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

        let alerts = step(&[incomplete], &mut seen);

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
        assert_eq!(step(&[event(Kind::Failed)], &mut seen), vec![]);
    }

    #[test]
    fn too_many_attempts_alerts() {
        let mut seen = Seen::default();
        let alerts = step(&[event(Kind::TooManyAttempts)], &mut seen);
        assert_eq!(alerts[0].reason, Reason::TooManyAttempts);
    }

    #[test]
    fn a_second_too_many_attempts_from_the_same_address_within_an_hour_is_suppressed() {
        let mut seen = Seen::default();
        step(&[event(Kind::TooManyAttempts)], &mut seen);

        let mut soon_after = event(Kind::TooManyAttempts);
        soon_after.created_at = "2026-09-27T02:38:58Z".to_owned();
        assert_eq!(step(&[soon_after], &mut seen), vec![]);
    }

    #[test]
    fn too_many_attempts_an_hour_later_from_the_same_address_alerts_again() {
        let mut seen = Seen::default();
        step(&[event(Kind::TooManyAttempts)], &mut seen);

        let mut later = event(Kind::TooManyAttempts);
        later.created_at = "2026-09-27T03:08:58Z".to_owned();
        let alerts = step(&[later], &mut seen);

        assert_eq!(alerts.len(), 1);
    }

    #[test]
    fn a_ban_right_after_a_too_many_attempts_notice_still_alerts() {
        let mut seen = Seen::default();
        step(&[event(Kind::TooManyAttempts)], &mut seen);

        let alerts = step(&[event(Kind::Ban)], &mut seen);

        assert_eq!(alerts[0].reason, Reason::Banned);
    }

    #[test]
    fn a_second_ban_for_the_same_address_within_an_hour_still_alerts() {
        let mut seen = Seen::default();
        step(&[event(Kind::Ban)], &mut seen);

        let mut soon_after = event(Kind::Ban);
        soon_after.created_at = "2026-09-27T02:09:00Z".to_owned();
        let alerts = step(&[soon_after], &mut seen);

        assert_eq!(alerts.len(), 1, "a ban must never be suppressed");
    }

    #[test]
    fn an_unknown_event_type_alerts_by_name() {
        let mut seen = Seen::default();
        let alerts = step(
            &[event(Kind::Other("auth.credential-expired".to_owned()))],
            &mut seen,
        );

        assert_eq!(
            alerts[0].reason,
            Reason::UnknownEvent("auth.credential-expired".to_owned())
        );
    }

    #[test]
    fn a_correct_password_stopped_at_totp_alerts_once_per_identity() {
        let mut seen = Seen::default();
        let alerts = step(&[event(Kind::MfaRequired)], &mut seen);

        assert_eq!(alerts.len(), 1);
        assert_eq!(alerts[0].reason, Reason::PasswordAcceptedAwaitingCode);
        assert_eq!(step(&[event(Kind::MfaRequired)], &mut seen), vec![]);
    }

    #[test]
    fn a_completed_totp_login_alerts_exactly_once_regardless_of_delivery_order() {
        let mut forward = Seen::default();
        let forward_alerts = step(
            &[event(Kind::MfaRequired), event(Kind::Success)],
            &mut forward,
        );
        assert_eq!(forward_alerts.len(), 1);
        assert_eq!(
            forward_alerts[0].reason,
            Reason::PasswordAcceptedAwaitingCode
        );

        let mut reversed = Seen::default();
        let reversed_alerts = step(
            &[event(Kind::Success), event(Kind::MfaRequired)],
            &mut reversed,
        );
        assert_eq!(reversed_alerts.len(), 1);
        assert_eq!(
            reversed_alerts[0].reason,
            Reason::NewSignIn(CredentialKind::Primary)
        );
    }
}
