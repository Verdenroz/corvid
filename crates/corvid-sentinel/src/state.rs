use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Seen`'s shape changes.
const SCHEMA: u32 = 2;
const PREVIOUS_SCHEMA: u32 = 1;

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

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Seen {
    pub identities: BTreeSet<Identity>,
    /// Address to the epoch second a too-many-attempts notice last fired for it. A ban is never
    /// rate-limited, so it needs no entry here.
    pub too_many_attempts_notified: BTreeMap<String, i64>,
    /// The `checkedAt` of the last failed update run a notice was delivered for.
    #[serde(default)]
    pub update_noticed: Option<String>,
}

corvid_store::pin_shape!(Identity {
    account,
    kind,
    listener,
    remote_ip
});
corvid_store::pin_shape!(Seen {
    identities,
    too_many_attempts_notified,
    update_noticed
});

pub fn open(path: &Path) -> io::Result<Seen> {
    match corvid_store::load(path, SCHEMA) {
        Ok(seen) => Ok(seen.unwrap_or_default()),
        // Schema 1 lacks `update_noticed`, which reads back as None.
        Err(error) if error.kind() == io::ErrorKind::InvalidData => {
            corvid_store::load(path, PREVIOUS_SCHEMA)
                .ok()
                .flatten()
                .ok_or(error)
        }
        Err(error) => Err(error),
    }
}

pub fn save(path: &Path, seen: &Seen) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, seen)
}
