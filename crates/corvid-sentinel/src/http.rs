use std::io;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use axum::Json;
use axum::Router;
use axum::body::Bytes;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use serde::{Deserialize, Serialize};

use crate::persisted::Persisted;
use crate::state::{Notice, NoticeKind, Seen, save};
use crate::{auth, dates, devices, handle, notice, parse, scribe, update, verify, watch};

pub struct Config {
    pub secret: Vec<u8>,
    pub state_path: PathBuf,
    pub seen: Seen,
    pub update_dir: PathBuf,
    pub update_poll: Duration,
    pub devices: Option<devices::Config>,
    pub auth: Option<auth::Config>,
    pub scribe: Option<scribe::Config>,
}

pub(crate) struct AppState {
    secret: Vec<u8>,
    seen: Persisted<Seen>,
}

impl AppState {
    /// `None` from `change` means nothing to commit.
    pub(crate) fn commit(&self, change: impl FnOnce(&Seen) -> Option<Seen>) -> io::Result<()> {
        self.seen.commit(|seen| (change(seen), ()))
    }
}

/// The routes, and the watcher that turns failed updates into notices; the caller spawns it.
pub fn build(config: Config) -> (Router, impl Future<Output = ()> + Send + 'static) {
    let state = Arc::new(AppState {
        secret: config.secret,
        seen: Persisted::new(config.state_path, config.seen, save),
    });
    let update_dir = config.update_dir.clone();
    let watcher = watch::updates(state.clone(), update_dir.clone(), config.update_poll);
    let router = Router::new()
        .route("/webhook", post(webhook))
        .route("/corvid/notices", get(notices))
        .with_state(state.clone())
        .merge(update::routes(config.update_dir));
    let router = router.merge(match config.devices {
        Some(devices) => devices::routes(devices),
        None => devices::off(),
    });
    let router = router.merge(match config.auth {
        Some(auth) => auth::routes(auth, state, update_dir),
        None => auth::off(),
    });
    let router = router.merge(match config.scribe {
        Some(scribe) => scribe::routes(scribe),
        None => scribe::off(),
    });
    (router, watcher)
}

#[derive(Serialize)]
pub(crate) struct Off {
    pub error: &'static str,
}

/// What a router for a switched-off feature answers on every route, so a client can tell "off"
/// from a request that never reached the sentinel.
pub(crate) fn off_json(error: &'static str) -> (StatusCode, Json<Off>) {
    (StatusCode::SERVICE_UNAVAILABLE, Json(Off { error }))
}

#[derive(Deserialize)]
struct After {
    after: Option<u64>,
}

#[derive(Serialize)]
struct Feed {
    notices: Vec<Notice>,
}

async fn notices(State(state): State<Arc<AppState>>, Query(query): Query<After>) -> Json<Feed> {
    let after = query.after.unwrap_or(0);
    let notices = state.seen.read(|seen| {
        seen.notices
            .iter()
            .filter(|notice| notice.id > after)
            .cloned()
            .collect()
    });
    Json(Feed { notices })
}

async fn webhook(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Bytes,
) -> StatusCode {
    let signature = headers
        .get("X-Signature")
        .and_then(|value| value.to_str().ok());
    if !verify(&state.secret, &body, signature) {
        return StatusCode::UNAUTHORIZED;
    }
    // A verified body Corvid cannot parse still gets a notice, not a silent 4xx: it is exactly
    // the kind of thing the sign-in log rule ("alert on what you don't recognise") exists for.
    let events = parse(&body);

    let outcome = tokio::task::spawn_blocking(move || {
        state.commit(|seen| match events {
            Ok(batch) => {
                let handled = handle(&batch.events, seen);
                if batch.unreadable == 0 {
                    return handled;
                }
                tracing::warn!(
                    count = batch.unreadable,
                    "verified webhook events could not be parsed"
                );
                let mut next = handled.unwrap_or_else(|| seen.clone());
                next.push_notice(
                    NoticeKind::SignIn,
                    dates::rfc3339_now(),
                    "Unreadable webhook events".to_owned(),
                    notice::unreadable_events(batch.unreadable, &body),
                );
                Some(next)
            }
            Err(error) => {
                tracing::warn!(%error, "a verified webhook body could not be parsed");
                let mut next = seen.clone();
                next.push_notice(
                    NoticeKind::SignIn,
                    dates::rfc3339_now(),
                    "Unreadable webhook batch".to_owned(),
                    notice::unparseable(&body),
                );
                Some(next)
            }
        })
    })
    .await
    .expect("the blocking task panicked");

    match outcome {
        Ok(()) => StatusCode::OK,
        // Stalwart retries within discardAfter, which is the point: off the delivery path, a
        // notice that could not be recorded must not be lost silently.
        Err(error) => {
            tracing::warn!(%error, "could not record a notice");
            StatusCode::BAD_GATEWAY
        }
    }
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use tokio::net::TcpListener;

    use super::*;
    use crate::state::open;

    const SECRET: &[u8] = b"integration test secret";

    async fn serve(state_path: &Path) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (app, _watcher) = build(Config {
            secret: SECRET.to_vec(),
            state_path: state_path.to_owned(),
            seen: Seen::default(),
            update_dir: std::env::temp_dir().join("sentinel-test-no-update-dir"),
            update_poll: Duration::from_secs(60),
            devices: None,
            auth: None,
            scribe: None,
        });
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        format!("http://{addr}/webhook")
    }

    fn sign(body: &[u8]) -> String {
        use base64::Engine;
        use base64::engine::general_purpose::STANDARD;
        use hmac::{Hmac, KeyInit, Mac};
        use sha2::Sha256;

        let mut mac = <Hmac<Sha256>>::new_from_slice(SECRET).unwrap();
        mac.update(body);
        STANDARD.encode(mac.finalize().into_bytes())
    }

    fn success_body() -> Vec<u8> {
        br#"{"events":[{"id":"1","createdAt":"2026-09-27T02:08:58Z","type":"auth.success","data":{"accountName":"alice@corvid.test","remoteIp":"203.0.113.9","listenerId":"imaps"}}]}"#.to_vec()
    }

    async fn post(url: &str, signature: String, body: Vec<u8>) -> reqwest::Response {
        reqwest::Client::new()
            .post(url)
            .header("X-Signature", signature)
            .body(body)
            .send()
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn a_correctly_signed_new_sign_in_is_recorded_as_a_notice() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sentinel.json");
        let url = serve(&path).await;
        let body = success_body();

        let response = post(&url, sign(&body), body).await;

        assert_eq!(response.status(), 200);
        let notices = open(&path).unwrap().notices;
        assert_eq!(notices.len(), 1);
        assert_eq!(notices[0].kind, NoticeKind::SignIn);
    }

    #[tokio::test]
    async fn a_batch_that_changes_nothing_does_not_rewrite_the_state_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sentinel.json");
        let url = serve(&path).await;
        let body = success_body();
        post(&url, sign(&body), body.clone()).await;
        std::fs::remove_file(&path).unwrap();

        let response = post(&url, sign(&body), body).await;

        assert_eq!(response.status(), 200);
        assert!(!path.exists());
    }

    #[tokio::test]
    async fn a_bad_signature_is_rejected_and_records_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sentinel.json");
        let url = serve(&path).await;

        let response = post(&url, "not-a-real-signature".to_owned(), success_body()).await;

        assert_eq!(response.status(), 401);
        assert!(!path.exists());
    }

    #[tokio::test]
    async fn a_verified_but_unparseable_body_is_still_recorded() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sentinel.json");
        let url = serve(&path).await;
        let body = b"not json".to_vec();

        let response = post(&url, sign(&body), body).await;

        assert_eq!(response.status(), 200);
        assert_eq!(open(&path).unwrap().notices.len(), 1);
    }

    #[tokio::test]
    async fn an_auth_error_with_a_string_cause_is_read_and_noticed_by_name() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sentinel.json");
        let url = serve(&path).await;
        let body = br#"{"events":[{"id":"1","createdAt":"2026-10-09T02:51:00Z","type":"auth.error","data":{"causedBy":"crates/directory/src/core/dispatch.rs:17","reason":"Unsupported credentials type for OIDC backend"}}]}"#.to_vec();

        let response = post(&url, sign(&body), body).await;

        assert_eq!(response.status(), 200);
        let notices = open(&path).unwrap().notices;
        assert_eq!(notices.len(), 1);
        assert_eq!(notices[0].title, "Unknown sign-in event");
    }

    #[tokio::test]
    async fn one_unreadable_event_does_not_hide_the_others_in_its_batch() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sentinel.json");
        let url = serve(&path).await;
        let body = br#"{"events":[{"id":"1","type":"auth.failed"},{"id":"2","createdAt":"2026-09-27T02:08:58Z","type":"auth.success","data":{"accountName":"alice@corvid.test","remoteIp":"203.0.113.9","listenerId":"imaps"}}]}"#.to_vec();

        let response = post(&url, sign(&body), body).await;

        assert_eq!(response.status(), 200);
        let titles: Vec<_> = open(&path)
            .unwrap()
            .notices
            .into_iter()
            .map(|n| n.title)
            .collect();
        assert_eq!(titles.len(), 2);
        assert!(titles.contains(&"Unreadable webhook events".to_owned()));
    }

    #[tokio::test]
    async fn a_failed_save_answers_with_a_retryable_status_and_the_retry_records_it() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("absent").join("sentinel.json");
        let url = serve(&path).await;
        let body = success_body();

        let response = post(&url, sign(&body), body.clone()).await;
        assert!(response.status().is_server_error(), "{}", response.status());

        std::fs::create_dir(path.parent().unwrap()).unwrap();
        let retried = post(&url, sign(&body), body).await;

        assert_eq!(retried.status(), 200);
        assert_eq!(open(&path).unwrap().notices.len(), 1);
    }
}
