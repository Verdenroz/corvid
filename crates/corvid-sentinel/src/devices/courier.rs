//! What the digest needs from the devices side: the mail account's reader, and a way to tell the
//! devices that asked for it that a digest is ready.

use serde_json::json;

use super::Shared;
use super::keys::Keys;
use super::push::{Pushing, fan_out};
use super::state::PushKind;

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
        fan_out(
            push,
            |device| device.kinds.contains(&PushKind::Digest),
            &[plaintext],
        )
        .await;
    }
}
