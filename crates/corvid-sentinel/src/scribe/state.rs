use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Scribe`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 1;
pub const DECIDED_CAP: usize = 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SenderKind {
    List,
    From,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Sender {
    pub kind: SenderKind,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    rename_all = "lowercase",
    rename_all_fields = "camelCase"
)]
pub enum Action {
    Archive,
    Trash,
    Folder { mailbox_id: String },
}

/// Stored in the shape the app reads, so serving one is a copy.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Suggestion {
    pub id: String,
    pub sender: Sender,
    pub action: Action,
    pub settled: u32,
    pub hits: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Verdict {
    Accepted,
    Dismissed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Decision {
    pub id: String,
    pub verdict: Verdict,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Scribe {
    /// Tonight's candidates; each post replaces them wholesale.
    pub suggestions: Vec<Suggestion>,
    /// Oldest first, only the newest `DECIDED_CAP` kept. A decided id is never served again.
    pub decided: Vec<Decision>,
}

impl Scribe {
    pub fn is_decided(&self, id: &str) -> bool {
        self.decided.iter().any(|decision| decision.id == id)
    }

    pub fn with_suggestions(&self, suggestions: Vec<Suggestion>) -> Self {
        Self {
            suggestions,
            decided: self.decided.clone(),
        }
    }

    pub fn with_decision(&self, id: &str, verdict: Verdict) -> Self {
        let mut decided = self.decided.clone();
        decided.push(Decision {
            id: id.to_owned(),
            verdict,
        });
        let excess = decided.len().saturating_sub(DECIDED_CAP);
        decided.drain(..excess);
        Self {
            suggestions: self.suggestions.clone(),
            decided,
        }
    }
}

corvid_store::pin_shape!(Sender { kind, value });
corvid_store::pin_shape!(Suggestion {
    id,
    sender,
    action,
    settled,
    hits
});
corvid_store::pin_shape!(Decision { id, verdict });
corvid_store::pin_shape!(Scribe {
    suggestions,
    decided
});

pub fn open(path: &Path) -> io::Result<Scribe> {
    corvid_store::open(path, SCHEMA)
}

pub fn save(path: &Path, scribe: &Scribe) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, scribe)
}
