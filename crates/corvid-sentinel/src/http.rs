use std::io;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use axum::Router;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::routing::post;

use crate::service::{Notifier, handle};
use crate::state::{Seen, save};
use crate::{parse, verify};

pub struct Config<N> {
    pub secret: Vec<u8>,
    pub state_path: PathBuf,
    pub seen: Seen,
    pub notifier: N,
}

struct AppState<N> {
    secret: Vec<u8>,
    state_path: PathBuf,
    seen: Mutex<Seen>,
    notifier: N,
}

pub fn router<N: Notifier + Send + Sync + 'static>(config: Config<N>) -> Router {
    let state = Arc::new(AppState {
        secret: config.secret,
        state_path: config.state_path,
        seen: Mutex::new(config.seen),
        notifier: config.notifier,
    });
    Router::new()
        .route("/webhook", post(webhook::<N>))
        .with_state(state)
}

async fn webhook<N: Notifier + Send + Sync + 'static>(
    State(state): State<Arc<AppState<N>>>,
    headers: HeaderMap,
    body: Bytes,
) -> StatusCode {
    let signature = headers
        .get("X-Signature")
        .and_then(|value| value.to_str().ok());
    if !verify(&state.secret, &body, signature) {
        return StatusCode::UNAUTHORIZED;
    }
    let events = match parse(&body) {
        Ok(events) => events,
        Err(error) => {
            tracing::warn!(%error, "malformed webhook body");
            return StatusCode::BAD_REQUEST;
        }
    };

    let outcome = tokio::task::spawn_blocking(move || {
        let mut seen = state.seen.lock().unwrap();
        let next = handle(&events, &seen, &state.notifier)?;
        save(&state.state_path, &next)?;
        *seen = next;
        Ok::<(), io::Error>(())
    })
    .await
    .expect("the blocking task panicked");

    match outcome {
        Ok(()) => StatusCode::OK,
        // Stalwart retries within discardAfter, which is the point: off the delivery path, a
        // failed notice must not be lost silently.
        Err(error) => {
            tracing::warn!(%error, "could not deliver a notice");
            StatusCode::BAD_GATEWAY
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex as StdMutex;
    use std::sync::atomic::{AtomicBool, Ordering};

    use tokio::net::TcpListener;

    use super::*;

    const SECRET: &[u8] = b"integration test secret";

    #[derive(Default)]
    struct FakeMailbox {
        sent: StdMutex<Vec<Vec<u8>>>,
        refuse: AtomicBool,
    }

    impl Notifier for Arc<FakeMailbox> {
        fn account_email(&self) -> &str {
            "verde@corvid.test"
        }

        fn send(&self, message: &[u8]) -> io::Result<()> {
            if self.refuse.load(Ordering::SeqCst) {
                return Err(io::Error::other("the mail server refused"));
            }
            self.sent.lock().unwrap().push(message.to_owned());
            Ok(())
        }
    }

    async fn serve(mailbox: Arc<FakeMailbox>) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let app = router(Config {
            secret: SECRET.to_vec(),
            state_path: std::env::temp_dir().join(format!("sentinel-test-{addr}.json")),
            seen: Seen::default(),
            notifier: mailbox,
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
        br#"{"events":[{"id":"1","createdAt":"2026-09-27T02:08:58Z","type":"auth.success","data":{"accountName":"verde@corvid.test","remoteIp":"203.0.113.9","listenerId":"imaps"}}]}"#.to_vec()
    }

    #[tokio::test]
    async fn a_correctly_signed_new_sign_in_is_delivered() {
        let mailbox = Arc::new(FakeMailbox::default());
        let url = serve(mailbox.clone()).await;
        let body = success_body();
        let client = reqwest::Client::new();

        let response = client
            .post(&url)
            .header("X-Signature", sign(&body))
            .body(body)
            .send()
            .await
            .unwrap();

        assert_eq!(response.status(), 200);
        assert_eq!(mailbox.sent.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn a_bad_signature_is_rejected_and_sends_nothing() {
        let mailbox = Arc::new(FakeMailbox::default());
        let url = serve(mailbox.clone()).await;
        let client = reqwest::Client::new();

        let response = client
            .post(&url)
            .header("X-Signature", "not-a-real-signature")
            .body(success_body())
            .send()
            .await
            .unwrap();

        assert_eq!(response.status(), 401);
        assert_eq!(mailbox.sent.lock().unwrap().len(), 0);
    }

    #[tokio::test]
    async fn a_delivery_failure_answers_with_a_retryable_status_and_commits_nothing() {
        let mailbox = Arc::new(FakeMailbox::default());
        mailbox.refuse.store(true, Ordering::SeqCst);
        let url = serve(mailbox.clone()).await;
        let body = success_body();
        let client = reqwest::Client::new();

        let response = client
            .post(&url)
            .header("X-Signature", sign(&body))
            .body(body.clone())
            .send()
            .await
            .unwrap();
        assert!(response.status().is_server_error(), "{}", response.status());

        mailbox.refuse.store(false, Ordering::SeqCst);
        let retried = client
            .post(&url)
            .header("X-Signature", sign(&body))
            .body(body)
            .send()
            .await
            .unwrap();

        assert_eq!(retried.status(), 200);
        assert_eq!(
            mailbox.sent.lock().unwrap().len(),
            1,
            "the failed attempt sent nothing; only the retry did"
        );
    }
}
