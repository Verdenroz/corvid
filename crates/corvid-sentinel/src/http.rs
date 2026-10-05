use std::io;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use axum::Json;
use axum::Router;
use axum::body::Bytes;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use serde::{Deserialize, Serialize};

use crate::state::{Notice, NoticeKind, Seen, save};
use crate::{dates, handle, notice, parse, update, verify, watch};

pub struct Config {
    pub secret: Vec<u8>,
    pub state_path: PathBuf,
    pub seen: Seen,
    pub update_dir: PathBuf,
    pub update_poll: Duration,
}

pub(crate) struct AppState {
    secret: Vec<u8>,
    state_path: PathBuf,
    seen: Mutex<Seen>,
}

impl AppState {
    // A poisoned lock means a prior request panicked mid-decision; the state it left behind is
    // still whatever the last successful commit wrote, so it is safe to keep using.
    fn lock(&self) -> MutexGuard<'_, Seen> {
        self.seen
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Applies `change` to the current state and persists the result before it becomes
    /// current, so a failed save leaves the state, and so a retried batch, as it was. `None`
    /// from `change` means nothing to commit.
    pub(crate) fn commit(&self, change: impl FnOnce(&Seen) -> Option<Seen>) -> io::Result<()> {
        let mut seen = self.lock();
        let Some(next) = change(&seen) else {
            return Ok(());
        };
        save(&self.state_path, &next)?;
        *seen = next;
        Ok(())
    }
}

/// The routes, and the watcher that turns failed updates into notices; the caller spawns it.
pub fn build(config: Config) -> (Router, impl Future<Output = ()> + Send + 'static) {
    let state = Arc::new(AppState {
        secret: config.secret,
        state_path: config.state_path,
        seen: Mutex::new(config.seen),
    });
    let watcher = watch::updates(state.clone(), config.update_dir.clone(), config.update_poll);
    let router = Router::new()
        .route("/webhook", post(webhook))
        .route("/corvid/notices", get(notices))
        .with_state(state)
        .merge(update::routes(config.update_dir));
    (router, watcher)
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
    let notices = state
        .lock()
        .notices
        .iter()
        .filter(|notice| notice.id > after)
        .cloned()
        .collect();
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
            Ok(events) => handle(&events, seen),
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
