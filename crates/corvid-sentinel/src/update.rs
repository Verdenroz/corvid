use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::Router;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{StatusCode, header};
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

impl Action {
    fn word(&self) -> &'static str {
        match self {
            Self::Check => "check",
            Self::Apply => "apply",
        }
    }
}

#[derive(Deserialize)]
struct Status {
    running: String,
    latest: Option<String>,
    #[serde(rename = "checkedAt")]
    checked_at: Option<String>,
    result: String,
    detail: Option<String>,
}

pub struct Failure {
    pub checked_at: String,
    pub version: String,
    pub result: String,
    pub detail: Option<String>,
}

/// The failed run in `status.json` that `noticed` does not cover yet, if any.
pub fn failure(dir: &Path, noticed: Option<&str>) -> io::Result<Option<Failure>> {
    let body = match std::fs::read(dir.join("status.json")) {
        Ok(body) => body,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let status: Status = serde_json::from_slice(&body)?;
    let Some(checked_at) = status.checked_at else {
        return Ok(None);
    };
    let failed = matches!(status.result.as_str(), "rolledBack" | "failed");
    if !failed || noticed == Some(checked_at.as_str()) {
        return Ok(None);
    }
    Ok(Some(Failure {
        checked_at,
        version: status.latest.unwrap_or(status.running),
        result: status.result,
        detail: status.detail,
    }))
}

pub fn routes(dir: PathBuf) -> Router {
    Router::new()
        .route("/corvid/update", get(status).post(request))
        .layer(DefaultBodyLimit::max(REQUEST_BODY_LIMIT))
        .with_state(Arc::new(dir))
}

async fn status(State(dir): State<Arc<PathBuf>>) -> Response {
    if !dir.is_dir() {
        return StatusCode::NOT_FOUND.into_response();
    }
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
    if !dir.is_dir() {
        return StatusCode::NOT_FOUND;
    }
    let Ok(Request { action }) = serde_json::from_slice(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    match write_request(&dir, action.word()).await {
        Ok(()) => StatusCode::ACCEPTED,
        Err(error) => {
            tracing::warn!(%error, "cannot write the update request");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

async fn write_request(dir: &Path, word: &str) -> io::Result<()> {
    let pending = dir.join("request");
    match tokio::fs::read_to_string(&pending).await {
        Ok(held) if held == "apply" || held == word => return Ok(()),
        Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(error),
        _ => {}
    }
    let temporary = dir.join(format!(
        ".request.{}.{}",
        std::process::id(),
        NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed)
    ));
    tokio::fs::write(&temporary, word).await?;
    tokio::fs::rename(&temporary, &pending).await
}
