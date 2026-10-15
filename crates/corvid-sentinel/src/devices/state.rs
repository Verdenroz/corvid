use std::collections::{BTreeMap, BTreeSet};
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

/// A kind of push beyond new-mail previews. A device gets one only if it asked for it, since a
/// client reads any push it cannot decode as new mail.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PushKind {
    Digest,
}

/// An approved device: where its key lives, and where its new-mail previews go if it takes any.
/// The push record and the kinds are part of the device, so revoking or re-enrolling it ends its
/// pushes. A file written before `kinds` existed reads as asking for none.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Device {
    #[serde(flatten)]
    pub key: Approved,
    #[serde(default)]
    pub push: Option<Push>,
    #[serde(default)]
    pub kinds: BTreeSet<PushKind>,
}

/// The keys previews are encrypted to, base64url as the device sent them, and where they go.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Push {
    pub p256dh: String,
    pub auth: String,
    pub target: Target,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Target {
    /// The path and query of an endpoint FCM issued.
    Fcm { endpoint: String },
    /// SHA-256 of the bearer token a device on its own connection reads its stream with.
    Stream { token_hash: String },
}

impl Push {
    /// Whether registering `other` would change nothing the device holds. A stream token is
    /// shown once, so two stream registrations with the same keys are the same one.
    pub fn same_as(&self, other: &Push) -> bool {
        let same_target = match (&self.target, &other.target) {
            (Target::Fcm { endpoint: a }, Target::Fcm { endpoint: b }) => a == b,
            (Target::Stream { .. }, Target::Stream { .. }) => true,
            _ => false,
        };
        same_target && self.p256dh == other.p256dh && self.auth == other.auth
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Devices {
    pub requests: BTreeMap<String, Request>,
    pub approved: BTreeMap<String, Device>,
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
corvid_store::pin_shape!(Device { key, push, kinds });
corvid_store::pin_shape!(Push {
    p256dh,
    auth,
    target
});
corvid_store::pin_shape!(Devices { requests, approved });

pub fn open(path: &Path) -> io::Result<Devices> {
    corvid_store::open_migrating(path, SCHEMA, PREVIOUS, |old: DevicesV1| Devices {
        requests: old.requests,
        approved: old
            .approved
            .into_iter()
            .map(|(name, key)| {
                (
                    name,
                    Device {
                        key,
                        push: None,
                        kinds: BTreeSet::new(),
                    },
                )
            })
            .collect(),
    })
}

pub fn save(path: &Path, devices: &Devices) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, devices)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_file_from_before_pushes_opens_with_its_devices_and_no_push_records() {
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

        assert_eq!(devices.approved["phone"].key, approved);
        assert!(devices.approved["phone"].push.is_none());
    }
}
