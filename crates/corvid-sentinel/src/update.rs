use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::Router;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Request as HttpRequest, State};
use axum::http::{StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use serde::Deserialize;

const REQUEST_BODY_LIMIT: usize = 256;

static NEXT_TEMPORARY: AtomicU64 = AtomicU64::new(0);

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    action: Action,
}

#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
enum Action {
    Check,
    Apply,
}

/// What the host updater is asked to do, weakest first: a later request never downgrades a held one.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Word {
    Check,
    Apply,
    /// Run the deploy files already on the host, with no pull.
    Deploy,
}

impl Word {
    fn text(self) -> &'static str {
        match self {
            Self::Check => "check",
            Self::Deploy => "deploy",
            Self::Apply => "apply",
        }
    }

    fn read(text: &str) -> Option<Self> {
        [Self::Check, Self::Apply, Self::Deploy]
            .into_iter()
            .find(|word| word.text() == text)
    }
}

impl From<Action> for Word {
    fn from(action: Action) -> Self {
        match action {
            Action::Check => Self::Check,
            Action::Apply => Self::Apply,
        }
    }
}

/// Asks the host to deploy the release it already runs, which is what makes a first passkey
/// switch Stalwart's directory without waiting for the nightly update. Where no updater is
/// mounted there is nobody to ask.
pub(crate) async fn request_deploy(dir: &Path) {
    if !dir.is_dir() {
        return;
    }
    if let Err(error) = write_request(dir, Word::Deploy).await {
        tracing::warn!(%error, "cannot write the deploy request");
    }
}

pub fn routes(dir: PathBuf) -> Router {
    let dir = Arc::new(dir);
    Router::new()
        .route("/corvid/update", get(status).post(request))
        .layer(DefaultBodyLimit::max(REQUEST_BODY_LIMIT))
        .layer(middleware::from_fn_with_state(dir.clone(), require_mounted))
        .with_state(dir)
}

async fn require_mounted(
    State(dir): State<Arc<PathBuf>>,
    request: HttpRequest,
    next: Next,
) -> Response {
    if dir.is_dir() {
        next.run(request).await
    } else {
        StatusCode::NOT_FOUND.into_response()
    }
}

async fn status(State(dir): State<Arc<PathBuf>>) -> Response {
    match tokio::fs::read(dir.join("status.json")).await {
        Ok(body) => ([(header::CONTENT_TYPE, "application/json")], body).into_response(),
        Err(error) if error.kind() == io::ErrorKind::NotFound => (
            StatusCode::SERVICE_UNAVAILABLE,
            [(header::CONTENT_TYPE, "application/json")],
            r#"{"error":"the updater has not reported yet"}"#,
        )
            .into_response(),
        Err(error) => {
            tracing::warn!(%error, "cannot read the update status");
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}

async fn request(State(dir): State<Arc<PathBuf>>, body: Bytes) -> StatusCode {
    let Ok(Request { action }) = serde_json::from_slice(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    match write_request(&dir, action.into()).await {
        Ok(()) => StatusCode::ACCEPTED,
        Err(error) => {
            tracing::warn!(%error, "cannot write the update request");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

async fn write_request(dir: &Path, word: Word) -> io::Result<()> {
    let pending = dir.join("request");
    match tokio::fs::read_to_string(&pending).await {
        Ok(held) if Word::read(&held).is_some_and(|held| held >= word) => return Ok(()),
        Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(error),
        _ => {}
    }
    let temporary = dir.join(format!(
        ".request.{}.{}",
        std::process::id(),
        NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed)
    ));
    tokio::fs::write(&temporary, word.text()).await?;
    tokio::fs::rename(&temporary, &pending).await
}
