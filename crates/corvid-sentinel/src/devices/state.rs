use std::collections::BTreeMap;
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Devices`' shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 2;
const PREVIOUS: u32 = 1;

pub const LIFETIME_SECONDS: i64 = 600;
pub const MAX_ATTEMPTS: u8 = 5;
pub const MAX_OPEN: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Stage {
    Open,
    Claimed,
    Approved,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Request {
    pub name: String,
    pub code: String,
    pub expires_at: i64,
    pub attempts: u8,
    pub stage: Stage,
}

/// Where an approved device's key lives. The device reads exactly this node, never a lookup by
/// name, since anyone who can sign in can add files beside it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Approved {
    pub account_id: String,
    pub node_id: String,
    pub blob_id: String,
}

/// Where to push an approved device's new-mail previews, and the keys they are encrypted to,
/// base64url as the device sent them. Kept beside the device, so revoking or re-enrolling it ends
/// its pushes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Push {
    pub endpoint: String,
    pub p256dh: String,
    pub auth: String,
    /// SHA-256 of the bearer token a device on its own connection reads its stream with.
    pub token_hash: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Devices {
    pub requests: BTreeMap<String, Request>,
    pub approved: BTreeMap<String, Approved>,
    pub pushes: BTreeMap<String, Push>,
}

/// Schema 1, from before devices could take pushes.
#[derive(Deserialize)]
struct DevicesV1 {
    requests: BTreeMap<String, Request>,
    approved: BTreeMap<String, Approved>,
}

impl Request {
    pub fn is_live(&self, now: i64) -> bool {
        self.expires_at > now && self.attempts < MAX_ATTEMPTS
    }
}

impl Devices {
    pub fn without_expired_requests(&self, now: i64) -> Self {
        let mut next = self.clone();
        next.requests.retain(|_, request| request.expires_at > now);
        next
    }

    pub fn open_requests(&self, now: i64) -> usize {
        self.requests
            .values()
            .filter(|request| request.stage == Stage::Open && request.is_live(now))
            .count()
    }
}

corvid_store::pin_shape!(Request {
    name,
    code,
    expires_at,
    attempts,
    stage
});
corvid_store::pin_shape!(Approved {
    account_id,
    node_id,
    blob_id
});
corvid_store::pin_shape!(Push {
    endpoint,
    p256dh,
    auth,
    token_hash
});
corvid_store::pin_shape!(Devices {
    requests,
    approved,
    pushes
});

pub fn open(path: &Path) -> io::Result<Devices> {
    corvid_store::open_migrating(path, SCHEMA, PREVIOUS, |old: DevicesV1| Devices {
        requests: old.requests,
        approved: old.approved,
        pushes: BTreeMap::new(),
    })
}

pub fn save(path: &Path, devices: &Devices) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, devices)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_file_from_before_pushes_opens_with_its_devices_and_no_endpoints() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("devices.json");
        let approved = Approved {
            account_id: "c".to_owned(),
            node_id: "n1".to_owned(),
            blob_id: "b1".to_owned(),
        };
        let old = serde_json::json!({"requests": {}, "approved": {"phone": approved}});
        corvid_store::save(&path, PREVIOUS, &old).unwrap();

        let devices = open(&path).unwrap();

        assert_eq!(devices.approved["phone"], approved);
        assert!(devices.pushes.is_empty());
    }
}
