use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Seen`'s shape changes. See `corvid_store::pin_shape!` below.
const SCHEMA: u32 = 1;

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

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Seen {
    pub identities: BTreeSet<Identity>,
    /// Address to the epoch second a too-many-attempts or ban notice last fired for it.
    pub burst_notified: BTreeMap<String, i64>,
}

corvid_store::pin_shape!(Identity {
    account,
    kind,
    listener,
    remote_ip
});
corvid_store::pin_shape!(Seen {
    identities,
    burst_notified
});

pub fn open(path: &Path) -> io::Result<Seen> {
    corvid_store::open(path, SCHEMA)
}

pub fn save(path: &Path, seen: &Seen) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, seen)
}
