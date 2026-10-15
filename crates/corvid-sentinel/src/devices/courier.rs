//! What the digest needs from the devices side: the mail account's reader, and a way to tell the
//! devices that asked for it that a digest is ready.

use axum::Json;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use futures_util::future::join_all;
use serde::Deserialize;
use serde_json::json;
use std::collections::BTreeSet;

use super::keys::Keys;
use super::push::{Pushing, push_to, refuse};
use super::state::{Push, PushKind};
use super::{Shared, commit, valid_name};
use crate::http::Off;

pub struct Courier {
    device: Shared,
    push: Option<Pushing>,
}

impl Courier {
    pub(super) fn new(device: Shared, push: Option<Pushing>) -> Self {
        Self { device, push }
    }

    pub(crate) fn keys(&self) -> &Keys {
        &self.device.keys
    }

    /// Pushes `{"v":2,"kind":"digest","id":...}`, which names no mail, to each device that asked
    /// for digests and has somewhere to receive them.
    pub(crate) async fn announce_digest(&self, id: &str) {
        let Some(push) = &self.push else {
            return;
        };
        let plaintext = json!({"v": 2, "kind": "digest", "id": id})
            .to_string()
            .into_bytes();
        let devices: Vec<(String, Push)> = self.device.devices.read(|devices| {
            devices
                .approved
                .iter()
                .filter(|(_, device)| device.kinds.contains(&PushKind::Digest))
                .filter_map(|(name, device)| Some((name.clone(), device.push.clone()?)))
                .collect()
        });
        join_all(devices.into_iter().map(|(name, registered)| {
            push_to(push, name, registered, std::slice::from_ref(&plaintext))
        }))
        .await;
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Kinds {
    kinds: BTreeSet<PushKind>,
}

/// Replaces the kinds of push a device takes beyond new-mail previews. It never touches the
/// device's push registration.
pub(super) async fn set_kinds(
    State(state): State<Pushing>,
    Path(name): Path<String>,
    body: Bytes,
) -> Result<StatusCode, (StatusCode, Json<Off>)> {
    let Kinds { kinds } = serde_json::from_slice(&body)
        .ok()
        .filter(|_| valid_name(&name))
        .ok_or_else(|| refuse(StatusCode::BAD_REQUEST))?;
    let known = commit(state.device.clone(), move |devices| {
        let Some(device) = devices.approved.get(&name) else {
            return (None, false);
        };
        if device.kinds == kinds {
            return (None, true);
        }
        let mut next = devices.clone();
        next.approved.get_mut(&name).expect("read above").kinds = kinds;
        (Some(next), true)
    })
    .await
    .map_err(refuse)?;
    known
        .then_some(StatusCode::NO_CONTENT)
        .ok_or_else(|| refuse(StatusCode::NOT_FOUND))
}
