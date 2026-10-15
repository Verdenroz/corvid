//! What the digest needs from the devices side: the mail account's reader.

use super::Shared;
use super::keys::Keys;

pub struct Courier {
    device: Shared,
}

impl Courier {
    pub(super) fn new(device: Shared) -> Self {
        Self { device }
    }

    pub(crate) fn keys(&self) -> &Keys {
        &self.device.keys
    }
}
