//! Rule suggestions the labeler mines and posts, kept until the app accepts or dismisses them.

mod ingest;
mod state;
mod summaries;

use std::path::PathBuf;
use std::sync::Arc;

use axum::Json;
use axum::Router;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::dates::now_secs;
use crate::http::{Off, off_json};
use crate::persisted::Persisted;
use crate::verify;
use ingest::{Candidate, Post};
pub use state::{Scribe, open};
use state::{Suggestion, Verdict, save};

pub struct Config {
    pub secret: Vec<u8>,
    pub state_path: PathBuf,
    pub scribe: Scribe,
}

struct ScribeState {
    secret: Vec<u8>,
    scribe: Persisted<Scribe>,
}

type Shared = Arc<ScribeState>;

/// A post may be this far from the sentinel's clock either way.
const SKEW_SECONDS: i64 = 300;

#[derive(Deserialize)]
struct Signed<T> {
    sent_at: i64,
    #[serde(flatten)]
    body: T,
}

/// The body of a post the labeler or scribe signed, when the signature and the clock hold.
fn accept<T: DeserializeOwned>(
    state: &ScribeState,
    headers: &HeaderMap,
    body: &[u8],
) -> Result<T, StatusCode> {
    let signature = headers
        .get("X-Signature")
        .and_then(|value| value.to_str().ok());
    if !verify(&state.secret, body, signature) {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let signed: Signed<T> = serde_json::from_slice(body).map_err(|_| StatusCode::BAD_REQUEST)?;
    if (now_secs() - signed.sent_at).abs() > SKEW_SECONDS {
        return Err(StatusCode::BAD_REQUEST);
    }
    Ok(signed.body)
}

/// Runs a state change off the async threads, since it writes the state file.
async fn commit<T: Send + 'static>(
    state: Shared,
    change: impl FnOnce(&Scribe) -> (Option<Scribe>, T) + Send + 'static,
) -> Result<T, StatusCode> {
    Persisted::commit_blocking(state, |state| &state.scribe, "suggestion", change).await
}

pub fn routes(config: Config) -> Router {
    let state = Arc::new(ScribeState {
        secret: config.secret,
        scribe: Persisted::new(config.state_path, config.scribe, save),
    });
    Router::new()
        .route("/internal/scribe/suggestions", post(ingest_post))
        .route("/internal/scribe/summaries", post(summaries::ingest))
        .route("/internal/scribe/manifest", post(summaries::manifest))
        .route("/corvid/summaries/{thread_id}", get(summaries::serve))
        .route("/corvid/suggestions", get(list))
        .route(
            "/corvid/suggestions/{id}/dismiss",
            post(|state, id| decide(state, id, Verdict::Dismissed)),
        )
        .route(
            "/corvid/suggestions/{id}/accept",
            post(|state, id| decide(state, id, Verdict::Accepted)),
        )
        .with_state(state)
}

/// The scribe routes on a host whose config has no `sentinel.scribe`. A stable 503 tells a
/// client "switched off" from a request that never reached the sentinel, which Stalwart answers 404.
pub fn off() -> Router {
    async fn off() -> (StatusCode, Json<Off>) {
        off_json("suggestions_off")
    }
    async fn summaries_off() -> (StatusCode, Json<Off>) {
        off_json("summaries_off")
    }
    Router::new()
        .route("/internal/scribe/summaries", post(summaries_off))
        .route("/internal/scribe/manifest", post(summaries_off))
        .route("/corvid/summaries/{thread_id}", get(summaries_off))
        .route("/internal/scribe/suggestions", post(off))
        .route("/corvid/suggestions", get(off))
        .route("/corvid/suggestions/{id}/dismiss", post(off))
        .route("/corvid/suggestions/{id}/accept", post(off))
}

async fn ingest_post(State(state): State<Shared>, headers: HeaderMap, body: Bytes) -> StatusCode {
    let post = match accept::<Post>(&state, &headers, &body) {
        Ok(post) => post,
        Err(status) => return status,
    };
    let suggestions: Vec<Suggestion> = post
        .candidates
        .into_iter()
        .filter_map(Candidate::parse)
        .collect();
    match commit(state, move |scribe| {
        (Some(scribe.with_suggestions(suggestions)), ())
    })
    .await
    {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(status) => status,
    }
}

#[derive(Serialize)]
struct Feed {
    suggestions: Vec<Suggestion>,
}

async fn list(State(state): State<Shared>) -> Json<Feed> {
    let suggestions = state.scribe.read(|scribe| {
        scribe
            .suggestions
            .iter()
            .filter(|suggestion| !scribe.is_decided(&suggestion.id))
            .cloned()
            .collect()
    });
    Json(Feed { suggestions })
}

async fn decide(
    State(state): State<Shared>,
    Path(id): Path<String>,
    verdict: Verdict,
) -> StatusCode {
    let result = commit(state, move |scribe| {
        if scribe.is_decided(&id) {
            return (None, StatusCode::NO_CONTENT);
        }
        if !scribe
            .suggestions
            .iter()
            .any(|suggestion| suggestion.id == id)
        {
            return (None, StatusCode::NOT_FOUND);
        }
        (
            Some(scribe.with_decision(&id, verdict)),
            StatusCode::NO_CONTENT,
        )
    })
    .await;
    result.unwrap_or_else(|status| status)
}
