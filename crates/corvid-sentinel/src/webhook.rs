use serde::Deserialize;

/// One event from a Stalwart WebHook batch, narrowed to what a sign-in notice needs.
///
/// `account`, `remote_ip` and `listener` are `Option` because a body missing one is still
/// something to alert on, not something to discard: see `decide`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthEvent {
    pub created_at: String,
    pub kind: Kind,
    pub account: Option<String>,
    pub remote_ip: Option<String>,
    pub listener: Option<String>,
    pub details: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Kind {
    Success,
    Failed,
    /// The account password was correct; the sign-in stopped at the TOTP step. Never fires for
    /// an app password, since TOTP only guards the primary credential.
    MfaRequired,
    TooManyAttempts,
    Ban,
    /// An event type this crate does not otherwise handle, carried by name so it can still be
    /// named in a notice rather than silently ignored.
    Other(String),
}

#[derive(Debug, Deserialize)]
struct RawBatch {
    events: Vec<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
struct RawEvent {
    #[serde(rename = "createdAt")]
    created_at: String,
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    data: RawData,
}

// No deny_unknown_fields: Stalwart's event payloads carry fields this crate does not read, and
// a new one appearing must not fail the whole batch.
#[derive(Debug, Default, Deserialize)]
struct RawData {
    #[serde(rename = "accountName")]
    account_name: Option<String>,
    #[serde(rename = "remoteIp")]
    remote_ip: Option<String>,
    #[serde(rename = "listenerId")]
    listener_id: Option<String>,
    details: Option<String>,
    // auth.too-many-attempts carries the account name only here, one level down. The nested
    // event has no id or createdAt of its own, unlike a top-level one.
    #[serde(rename = "causedBy")]
    caused_by: Option<Cause>,
}

// Stalwart sends a bare source location instead of an event for some types (auth.error).
#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum Cause {
    Event(Box<RawCause>),
    Location(#[expect(dead_code, reason = "a location names no account")] String),
}

#[derive(Debug, Deserialize)]
struct RawCause {
    #[serde(default)]
    data: RawData,
}

/// The events of a batch that parsed, and how many others did not.
#[derive(Debug, PartialEq, Eq)]
pub struct Batch {
    pub events: Vec<AuthEvent>,
    pub unreadable: usize,
}

pub fn parse(body: &[u8]) -> serde_json::Result<Batch> {
    let raw: RawBatch = serde_json::from_slice(body)?;
    let total = raw.events.len();
    let events: Vec<AuthEvent> = raw
        .events
        .into_iter()
        .filter_map(|event| serde_json::from_value::<RawEvent>(event).ok())
        .map(AuthEvent::from)
        .collect();
    Ok(Batch {
        unreadable: total - events.len(),
        events,
    })
}

impl From<RawEvent> for AuthEvent {
    fn from(raw: RawEvent) -> Self {
        let kind = match raw.kind.as_str() {
            "auth.success" => Kind::Success,
            "auth.failed" => Kind::Failed,
            "auth.mfa-required" => Kind::MfaRequired,
            "auth.too-many-attempts" => Kind::TooManyAttempts,
            "security.authentication-ban" => Kind::Ban,
            other => Kind::Other(other.to_owned()),
        };
        let account = raw.data.account_name.or(match raw.data.caused_by {
            Some(Cause::Event(inner)) => inner.data.account_name,
            Some(Cause::Location(_)) | None => None,
        });
        Self {
            created_at: raw.created_at,
            kind,
            account,
            remote_ip: raw.data.remote_ip,
            listener: raw.data.listener_id,
            details: raw.data.details,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!("tests/fixtures/{name}")).unwrap()
    }

    #[test]
    fn a_primary_password_sign_in_carries_no_details() {
        let events = parse(&fixture("auth-success-primary-jmap.json"))
            .unwrap()
            .events;
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].kind, Kind::Success);
        assert_eq!(events[0].account.as_deref(), Some("admin@corvid.test"));
        assert_eq!(events[0].remote_ip.as_deref(), Some("172.24.0.1"));
        assert_eq!(events[0].listener.as_deref(), Some("http"));
        assert_eq!(events[0].details, None);
    }

    #[test]
    fn an_app_password_sign_in_names_it_in_details() {
        let events = parse(&fixture("auth-success-app-password-imap.json"))
            .unwrap()
            .events;
        assert_eq!(
            events[0].details.as_deref(),
            Some("Authenticated with app password")
        );
    }

    #[test]
    fn a_failed_attempt_has_no_details() {
        let events = parse(&fixture("auth-failed.json")).unwrap().events;
        assert_eq!(events[0].kind, Kind::Failed);
        assert_eq!(events[0].details, None);
    }

    #[test]
    fn a_batch_holds_every_event_in_order() {
        let events = parse(&fixture("auth-failed-batch.json")).unwrap().events;
        assert_eq!(events.len(), 3);
        assert!(events.iter().all(|event| event.kind == Kind::Failed));
    }

    #[test]
    fn too_many_attempts_reads_the_account_from_caused_by() {
        let events = parse(&fixture("auth-too-many-attempts.json"))
            .unwrap()
            .events;
        let burst = events
            .iter()
            .find(|event| event.kind == Kind::TooManyAttempts)
            .unwrap();
        assert_eq!(burst.account.as_deref(), Some("alice@corvid.test"));
        assert_eq!(burst.remote_ip.as_deref(), Some("172.24.0.3"));
    }

    #[test]
    fn a_ban_carries_the_account_and_address() {
        let events = parse(&fixture("security-authentication-ban.json"))
            .unwrap()
            .events;
        assert_eq!(events[0].kind, Kind::Ban);
        assert_eq!(events[0].account.as_deref(), Some("alice@corvid.test"));
        assert_eq!(events[0].remote_ip.as_deref(), Some("172.24.0.1"));
    }

    #[test]
    fn an_event_type_this_crate_does_not_know_is_kept_by_name() {
        let body = br#"{"events":[{"id":"1","createdAt":"2026-01-01T00:00:00Z","type":"auth.credential-expired","data":{"accountName":"a@b.test","remoteIp":"1.2.3.4","listenerId":"http"}}]}"#;
        let events = parse(body).unwrap().events;
        assert_eq!(
            events[0].kind,
            Kind::Other("auth.credential-expired".to_owned())
        );
    }

    #[test]
    fn a_correct_password_stopped_by_totp_is_its_own_kind() {
        let events = parse(&fixture("auth-mfa-required-imap.json"))
            .unwrap()
            .events;
        assert_eq!(events[0].kind, Kind::MfaRequired);
        assert_eq!(events[0].account.as_deref(), Some("alice@corvid.test"));
        assert_eq!(events[0].remote_ip.as_deref(), Some("172.24.0.3"));
        assert_eq!(events[0].listener.as_deref(), Some("imaps"));
    }

    #[test]
    fn a_cause_that_is_a_source_location_parses_with_no_account() {
        let body = br#"{"events":[{"id":"1","createdAt":"2026-01-01T00:00:00Z","type":"auth.error","data":{"causedBy":"crates/directory/src/core/dispatch.rs:17","reason":"Unsupported credentials type for OIDC backend"}}]}"#;
        let batch = parse(body).unwrap();
        assert_eq!(batch.unreadable, 0);
        assert_eq!(batch.events[0].kind, Kind::Other("auth.error".to_owned()));
        assert_eq!(batch.events[0].account, None);
    }

    #[test]
    fn an_event_of_an_unknown_shape_is_counted_and_the_rest_are_kept() {
        let body = br#"{"events":[{"id":"1","type":"auth.failed"},{"id":"2","createdAt":"2026-01-01T00:00:00Z","type":"auth.failed","data":{"accountName":"a@b.test"}}]}"#;
        let batch = parse(body).unwrap();
        assert_eq!(batch.unreadable, 1);
        assert_eq!(batch.events.len(), 1);
    }

    #[test]
    fn a_body_missing_the_remote_address_still_parses() {
        let body = br#"{"events":[{"id":"1","createdAt":"2026-01-01T00:00:00Z","type":"auth.success","data":{"accountName":"a@b.test"}}]}"#;
        let events = parse(body).unwrap().events;
        assert_eq!(events[0].remote_ip, None);
    }
}
