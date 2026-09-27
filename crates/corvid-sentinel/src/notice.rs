use crate::dates;
use crate::decide::{Alert, Reason};
use crate::state::CredentialKind;

/// Renders a batch's alerts as one plain-text message, ready for `Email/import`.
///
/// Carries only what `decide` learned about the sign-in itself: time, address, listener and
/// credential kind. Never the account's mail.
pub fn render(alerts: &[Alert], to: &str) -> Vec<u8> {
    let subject = match alerts.len() {
        1 => "Corvid Sentinel: 1 sign-in alert".to_owned(),
        n => format!("Corvid Sentinel: {n} sign-in alerts"),
    };
    let date = alerts
        .first()
        .and_then(|alert| dates::rfc2822(&alert.at))
        .unwrap_or_else(|| "Thu, 01 Jan 1970 00:00:00 +0000".to_owned());
    let body = alerts.iter().map(line).collect::<Vec<_>>().join("\r\n");
    format!(
        "From: Corvid Sentinel <corvid-sentinel@corvid.local>\r\n\
         To: {to}\r\n\
         Subject: {subject}\r\n\
         Date: {date}\r\n\
         Content-Type: text/plain; charset=utf-8\r\n\
         \r\n\
         {body}\r\n"
    )
    .into_bytes()
}

fn line(alert: &Alert) -> String {
    let at = &alert.at;
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
        Reason::NewSignIn(CredentialKind::Primary) => format!(
            "{at}: {account} signed in with the account password from {address} via \
             {listener}, a combination not seen before."
        ),
        Reason::NewSignIn(CredentialKind::AppPassword) => format!(
            "{at}: {account} signed in with an app password from {address} via {listener}, a \
             combination not seen before."
        ),
        Reason::UnrecognisedCredential(details) => format!(
            "{at}: {account} signed in from {address} via {listener} with a credential type \
             this crate does not recognise: {details:?}."
        ),
        Reason::Incomplete => format!(
            "{at}: {account} signed in from {address} via {listener}, but the event was \
             missing a field, so it could not be checked against previous sign-ins."
        ),
        Reason::TooManyAttempts => format!(
            "{at}: repeated failed sign-in attempts for {account} from {address} via {listener}."
        ),
        Reason::Banned => {
            format!("{at}: {address} was banned after repeated failed sign-ins ({account}).")
        }
        Reason::UnknownEvent(name) => format!(
            "{at}: an event type this crate does not recognise ({name}) for {account} from \
             {address} via {listener}."
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn alert(reason: Reason) -> Alert {
        Alert {
            at: "2026-09-27T02:08:58Z".to_owned(),
            account: Some("verde@corvid.test".to_owned()),
            remote_ip: Some("203.0.113.9".to_owned()),
            listener: Some("imaps".to_owned()),
            reason,
        }
    }

    fn text(bytes: &[u8]) -> String {
        String::from_utf8(bytes.to_vec()).unwrap()
    }

    #[test]
    fn one_alert_gets_a_singular_subject() {
        let message = text(&render(
            &[alert(Reason::NewSignIn(CredentialKind::Primary))],
            "verde@corvid.test",
        ));
        assert!(message.contains("Subject: Corvid Sentinel: 1 sign-in alert\r\n"));
    }

    #[test]
    fn several_alerts_get_a_plural_subject_and_one_line_each() {
        let message = text(&render(
            &[alert(Reason::TooManyAttempts), alert(Reason::Banned)],
            "verde@corvid.test",
        ));
        assert!(message.contains("Subject: Corvid Sentinel: 2 sign-in alerts\r\n"));
        assert_eq!(message.matches("2026-09-27T02:08:58Z").count(), 2);
    }

    #[test]
    fn the_date_header_is_the_first_alerts_time() {
        let message = text(&render(
            &[alert(Reason::NewSignIn(CredentialKind::Primary))],
            "verde@corvid.test",
        ));
        assert!(message.contains("Date: Sun, 27 Sep 2026 02:08:58 +0000\r\n"));
    }

    #[test]
    fn an_app_password_is_named_as_such_not_by_a_description() {
        let message = text(&render(
            &[alert(Reason::NewSignIn(CredentialKind::AppPassword))],
            "verde@corvid.test",
        ));
        assert!(message.contains("signed in with an app password"));
    }

    #[test]
    fn an_unrecognised_credential_carries_its_raw_text() {
        let mut alert = alert(Reason::UnrecognisedCredential(
            "Authenticated with a magic link".to_owned(),
        ));
        alert.account = None;
        let message = text(&render(&[alert], "verde@corvid.test"));
        assert!(message.contains("Authenticated with a magic link"));
        assert!(message.contains("an unreported account"));
    }

    #[test]
    fn the_from_address_is_a_corvid_identity() {
        let message = text(&render(
            &[alert(Reason::NewSignIn(CredentialKind::Primary))],
            "verde@corvid.test",
        ));
        assert!(message.starts_with("From: Corvid Sentinel <corvid-sentinel@corvid.local>\r\n"));
    }
}
