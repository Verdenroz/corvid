use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

pub const SCHEMA: u32 = 1;

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Actions {
    pub pending: BTreeMap<String, Pending>,
    /// Thread and key of every arrival seen recently, so a long thread counts once.
    pub threads: BTreeMap<String, i64>,
    pub keys: BTreeMap<String, Vec<Observation>>,
    pub candidates: Vec<Candidate>,
}

/// An arrival waiting out its settle window.
#[derive(Debug, Serialize, Deserialize)]
pub struct Pending {
    pub key: String,
    pub first_seen: i64,
    pub seen: bool,
    pub left: Option<Exit>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Exit {
    Archive,
    Trash,
    Junk,
    Folder(String),
}

impl Exit {
    pub fn label(&self) -> String {
        match self {
            Self::Archive => "archive".into(),
            Self::Trash => "trash".into(),
            Self::Junk => "junk".into(),
            Self::Folder(id) => format!("folder:{id}"),
        }
    }
}

/// One settled arrival: the day it landed and where it went if it left the Inbox unseen.
#[derive(Debug, Serialize, Deserialize)]
pub struct Observation {
    pub day: i64,
    pub exit: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Candidate {
    pub id: String,
    pub key: String,
    pub outcome: String,
    pub settled: u32,
    pub hits: u32,
    pub lower_bound: f64,
}

corvid_store::pin_shape!(Actions {
    pending,
    threads,
    keys,
    candidates
});
corvid_store::pin_shape!(Pending {
    key,
    first_seen,
    seen,
    left
});
corvid_store::pin_shape!(Observation { day, exit });
corvid_store::pin_shape!(Candidate {
    id,
    key,
    outcome,
    settled,
    hits,
    lower_bound
});
