use sha2::{Digest, Sha256};

use crate::decide::{Alert, Reason};
use crate::state::{CredentialKind, NoticeKind};

/// What the feed shows for one alert: its kind, a short title and a sentence of detail.
///
/// Carries only what `decide` learned about the sign-in itself: address, listener and
/// credential kind. Never the account's mail.
pub fn render(alert: &Alert) -> (NoticeKind, &'static str, String) {
    let account = alert.account.as_deref().unwrap_or("an unreported account");
    let address = alert
        .remote_ip
        .as_deref()
        .unwrap_or("an unreported address");
    let listener = alert
        .listener
        .as_deref()
        .unwrap_or("an unreported listener");
    match &alert.reason {
        Reason::NewSignIn(CredentialKind::Primary) => (
            NoticeKind::SignIn,
            "New sign-in",
            format!(
                "{account} signed in with the account password from {address} via {listener}, \
                 a combination not seen before."
            ),
        ),
        Reason::NewSignIn(CredentialKind::AppPassword) => (
            NoticeKind::SignIn,
            "New sign-in",
            format!(
                "{account} signed in with an app password from {address} via {listener}, a \
                 combination not seen before."
            ),
        ),
        Reason::PasswordAcceptedAwaitingCode => (
            NoticeKind::SignIn,
            "Password entered, code missing",
            format!(
                "The account password for {account} was entered correctly from {address} via \
                 {listener}, but the sign-in stopped at the TOTP step. Mail apps and services \
                 must use an app password instead (device.sh add)."
            ),
        ),
        Reason::UnrecognisedCredential(details) => (
            NoticeKind::SignIn,
            "Unrecognised credential",
            format!(
                "{account} signed in from {address} via {listener} with a credential type \
                 this crate does not recognise: {details:?}."
            ),
        ),
        Reason::Incomplete => (
            NoticeKind::SignIn,
            "Sign-in not checked",
            format!(
                "{account} signed in from {address} via {listener}, but the event was missing \
                 a field, so it could not be checked against previous sign-ins."
            ),
        ),
        Reason::TooManyAttempts => (
            NoticeKind::TooManyAttempts,
            "Repeated failed sign-ins",
            format!(
                "Repeated failed sign-in attempts for {account} from {address} via {listener}."
            ),
        ),
        Reason::Banned => (
            NoticeKind::Ban,
            "Address banned",
            format!("{address} was banned after repeated failed sign-ins ({account})."),
        ),
        Reason::UnknownEvent(name) => (
            NoticeKind::SignIn,
            "Unknown sign-in event",
            format!(
                "An event type this crate does not recognise ({name}) for {account} from \
                 {address} via {listener}."
            ),
        ),
    }
}

/// A batch that verified but could not be parsed is identified by its digest rather than
/// repeated back, since the content is exactly what did not parse as JSON.
pub fn unparseable(body: &[u8]) -> String {
    let digest = hex::encode(Sha256::digest(body));
    format!(
        "A webhook batch of {} bytes verified but could not be parsed. sha256: {digest}",
        body.len()
    )
}

/// Events inside a verified batch that did not match the expected shape; the batch is
/// identified by digest for the same reason as `unparseable`.
pub fn unreadable_events(count: usize, body: &[u8]) -> String {
    let digest = hex::encode(Sha256::digest(body));
    format!("{count} event(s) in a verified webhook batch could not be parsed. sha256: {digest}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn alert(reason: Reason) -> Alert {
        Alert {
            at: "2026-09-27T02:08:58Z".to_owned(),
            account: Some("alice@corvid.test".to_owned()),
            remote_ip: Some("203.0.113.9".to_owned()),
            listener: Some("imaps".to_owned()),
            reason,
        }
    }

    #[test]
    fn an_app_password_is_named_as_such_not_by_a_description() {
        let (kind, _, detail) = render(&alert(Reason::NewSignIn(CredentialKind::AppPassword)));
        assert_eq!(kind, NoticeKind::SignIn);
        assert!(detail.contains("signed in with an app password"));
    }

    #[test]
    fn an_unrecognised_credential_carries_its_raw_text() {
        let mut alert = alert(Reason::UnrecognisedCredential(
            "Authenticated with a magic link".to_owned(),
        ));
        alert.account = None;
        let (_, _, detail) = render(&alert);
        assert!(detail.contains("Authenticated with a magic link"));
        assert!(detail.contains("an unreported account"));
    }

    #[test]
    fn a_password_stopped_at_totp_names_the_app_password_alternative() {
        let (_, _, detail) = render(&alert(Reason::PasswordAcceptedAwaitingCode));
        assert!(detail.contains("stopped at the TOTP step"));
        assert!(detail.contains("device.sh add"));
    }

    #[test]
    fn a_ban_and_repeated_failures_get_their_own_kinds() {
        assert_eq!(render(&alert(Reason::Banned)).0, NoticeKind::Ban);
        assert_eq!(
            render(&alert(Reason::TooManyAttempts)).0,
            NoticeKind::TooManyAttempts
        );
    }

    #[test]
    fn an_unparseable_batch_is_identified_by_digest_not_content() {
        let detail = unparseable(b"secret body");
        assert!(!detail.contains("secret body"));
        assert!(detail.contains("11 bytes"));
    }
}
