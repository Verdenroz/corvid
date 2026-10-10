use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Seen`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 2;
const NOTICE_CAP: usize = 200;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum CredentialKind {
    Primary,
    AppPassword,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Identity {
    pub account: String,
    pub kind: CredentialKind,
    pub listener: String,
    pub remote_ip: String,
}

/// Kept apart from `Identity` so a file holding one still reads under a binary that has no API
/// key credential kind, which would refuse an unknown `kind` and stop the service.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct ApiKeyIdentity {
    pub account: String,
    pub listener: String,
    pub remote_ip: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum NoticeKind {
    SignIn,
    TooManyAttempts,
    Ban,
    Update,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Notice {
    pub id: u64,
    pub kind: NoticeKind,
    pub at: String,
    pub title: String,
    pub detail: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Seen {
    pub identities: BTreeSet<Identity>,
    #[serde(default)]
    pub api_keys: BTreeSet<ApiKeyIdentity>,
    /// Address to the epoch second a too-many-attempts notice last fired for it. A ban is never
    /// rate-limited, so it needs no entry here.
    pub too_many_attempts_notified: BTreeMap<String, i64>,
    /// Oldest first, only the newest `NOTICE_CAP` kept.
    pub notices: Vec<Notice>,
    pub last_notice_id: u64,
    /// The `checkedAt` of the last failed or rolled-back update that became a notice.
    pub update_noticed: Option<String>,
}

#[derive(Deserialize)]
struct SeenV1 {
    identities: BTreeSet<Identity>,
    too_many_attempts_notified: BTreeMap<String, i64>,
}

impl From<SeenV1> for Seen {
    fn from(old: SeenV1) -> Self {
        Self {
            identities: old.identities,
            too_many_attempts_notified: old.too_many_attempts_notified,
            ..Self::default()
        }
    }
}

impl Seen {
    pub fn push_notice(&mut self, kind: NoticeKind, at: String, title: String, detail: String) {
        self.last_notice_id += 1;
        self.notices.push(Notice {
            id: self.last_notice_id,
            kind,
            at,
            title,
            detail,
        });
        let excess = self.notices.len().saturating_sub(NOTICE_CAP);
        self.notices.drain(..excess);
    }
}

corvid_store::pin_shape!(Identity {
    account,
    kind,
    listener,
    remote_ip
});
corvid_store::pin_shape!(ApiKeyIdentity {
    account,
    listener,
    remote_ip
});
corvid_store::pin_shape!(Notice {
    id,
    kind,
    at,
    title,
    detail
});
corvid_store::pin_shape!(Seen {
    identities,
    api_keys,
    too_many_attempts_notified,
    notices,
    last_notice_id,
    update_noticed
});

pub fn open(path: &Path) -> io::Result<Seen> {
    corvid_store::open_migrating(path, SCHEMA, 1, |old: SeenV1| old.into())
}

pub fn save(path: &Path, seen: &Seen) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, seen)
}
