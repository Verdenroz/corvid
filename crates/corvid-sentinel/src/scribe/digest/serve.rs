use axum::Json;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use serde::Serialize;

use crate::dates::rfc3339;
use crate::scribe::Shared;
use crate::scribe::state::{Digest, Group, Item};

#[derive(Serialize)]
pub(super) struct Served {
    id: String,
    from: String,
    to: String,
    groups: Vec<ServedGroup>,
}

#[derive(Serialize)]
struct ServedGroup {
    label: Option<String>,
    items: Vec<ServedItem>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ServedItem {
    thread_id: String,
    email_id: String,
    from: String,
    subject: String,
    received_at: String,
    summary: Option<String>,
}

impl From<&Digest> for Served {
    fn from(digest: &Digest) -> Self {
        Self {
            id: digest.id.clone(),
            from: rfc3339(digest.from),
            to: rfc3339(digest.to),
            groups: digest.groups.iter().map(ServedGroup::from).collect(),
        }
    }
}

impl From<&Group> for ServedGroup {
    fn from(group: &Group) -> Self {
        Self {
            label: group.label.clone(),
            items: group.items.iter().map(ServedItem::from).collect(),
        }
    }
}

impl From<&Item> for ServedItem {
    fn from(item: &Item) -> Self {
        Self {
            thread_id: item.thread_id.clone(),
            email_id: item.email_id.clone(),
            from: item.from.clone(),
            subject: item.subject.clone(),
            received_at: rfc3339(item.received_at),
            summary: item.summary.clone(),
        }
    }
}

#[derive(Serialize)]
pub(super) struct Listed {
    digests: Vec<Entry>,
}

#[derive(Serialize)]
struct Entry {
    id: String,
    to: String,
    count: usize,
}

pub(super) async fn list(State(state): State<Shared>) -> Json<Listed> {
    let digests = state.scribe.read(|scribe| {
        scribe
            .digests
            .iter()
            .rev()
            .map(|digest| Entry {
                id: digest.id.clone(),
                to: rfc3339(digest.to),
                count: digest.groups.iter().map(|group| group.items.len()).sum(),
            })
            .collect()
    });
    Json(Listed { digests })
}

pub(super) async fn latest(State(state): State<Shared>) -> Result<Json<Served>, StatusCode> {
    state
        .scribe
        .read(|scribe| scribe.digests.last().map(Served::from))
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

pub(super) async fn one(
    State(state): State<Shared>,
    Path(id): Path<String>,
) -> Result<Json<Served>, StatusCode> {
    state
        .scribe
        .read(|scribe| {
            scribe
                .digests
                .iter()
                .find(|digest| digest.id == id)
                .map(Served::from)
        })
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}
