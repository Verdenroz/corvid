use std::collections::BTreeMap;

use axum::Json;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use serde::{Deserialize, Serialize};

use super::state::Summary;
use super::{Shared, accept, commit};

const MAX_TEXT_CHARS: usize = 2000;

#[derive(Deserialize)]
pub(super) struct Post {
    upserts: Vec<Upsert>,
    deletes: Vec<String>,
}

#[derive(Deserialize)]
struct Upsert {
    thread_id: String,
    input_hash: String,
    text: String,
    model: String,
    generated_at: i64,
}

pub(super) async fn ingest(
    State(state): State<Shared>,
    headers: HeaderMap,
    body: Bytes,
) -> StatusCode {
    let post = match accept::<Post>(&state, &headers, &body) {
        Ok(post) => post,
        Err(status) => return status,
    };
    if post
        .upserts
        .iter()
        .any(|upsert| upsert.text.chars().count() > MAX_TEXT_CHARS)
    {
        return StatusCode::BAD_REQUEST;
    }
    let upserts = post
        .upserts
        .into_iter()
        .map(|upsert| {
            let summary = Summary {
                input_hash: upsert.input_hash,
                text: upsert.text,
                model: upsert.model,
                generated_at: upsert.generated_at,
            };
            (upsert.thread_id, summary)
        })
        .collect();
    let deletes = post.deletes;
    match commit(state, move |scribe| {
        (Some(scribe.with_summaries(upserts, &deletes)), ())
    })
    .await
    {
        Ok(()) => StatusCode::NO_CONTENT,
        Err(status) => status,
    }
}

#[derive(Deserialize)]
pub(super) struct Ask {}

#[derive(Serialize)]
pub(super) struct Manifest {
    threads: BTreeMap<String, String>,
}

pub(super) async fn manifest(
    State(state): State<Shared>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<Manifest>, StatusCode> {
    accept::<Ask>(&state, &headers, &body)?;
    let threads = state.scribe.read(|scribe| {
        scribe
            .summaries
            .iter()
            .map(|(thread_id, summary)| (thread_id.clone(), summary.input_hash.clone()))
            .collect()
    });
    Ok(Json(Manifest { threads }))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Served {
    thread_id: String,
    text: String,
    generated_at: i64,
}

pub(super) async fn serve(
    State(state): State<Shared>,
    Path(thread_id): Path<String>,
) -> Result<Json<Served>, StatusCode> {
    state
        .scribe
        .read(|scribe| scribe.summaries.get(&thread_id).cloned())
        .map(|summary| {
            Json(Served {
                thread_id,
                text: summary.text,
                generated_at: summary.generated_at,
            })
        })
        .ok_or(StatusCode::NOT_FOUND)
}
