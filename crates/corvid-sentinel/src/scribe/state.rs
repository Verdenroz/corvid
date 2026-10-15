use std::collections::BTreeMap;
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Scribe`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 1;
const DECIDED_CAP: usize = 1000;
const SUMMARY_CAP: usize = 5000;

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

/// A thread's summary, kept with the hash of the messages it was written from.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Summary {
    pub input_hash: String,
    pub text: String,
    pub model: String,
    pub generated_at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Scribe {
    /// Tonight's candidates; each post replaces them wholesale.
    pub suggestions: Vec<Suggestion>,
    /// Oldest first, only the newest `DECIDED_CAP` kept. A decided id is never served again.
    pub decided: Vec<Decision>,
    /// By thread id. Past `SUMMARY_CAP` the oldest `generated_at` goes first. Defaults so a
    /// file written before summaries existed still opens.
    #[serde(default)]
    pub summaries: BTreeMap<String, Summary>,
}

impl Scribe {
    pub fn is_decided(&self, id: &str) -> bool {
        self.decided.iter().any(|decision| decision.id == id)
    }

    pub fn with_suggestions(&self, suggestions: Vec<Suggestion>) -> Self {
        Self {
            suggestions,
            ..self.clone()
        }
    }

    pub fn with_summaries(&self, upserts: Vec<(String, Summary)>, deletes: &[String]) -> Self {
        let mut summaries = self.summaries.clone();
        for thread_id in deletes {
            summaries.remove(thread_id);
        }
        summaries.extend(upserts);
        while summaries.len() > SUMMARY_CAP {
            let oldest = summaries
                .iter()
                .min_by_key(|(_, summary)| summary.generated_at)
                .map(|(thread_id, _)| thread_id.clone())
                .expect("over the cap means not empty");
            summaries.remove(&oldest);
        }
        Self {
            summaries,
            ..self.clone()
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
            decided,
            ..self.clone()
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
corvid_store::pin_shape!(Summary {
    input_hash,
    text,
    model,
    generated_at
});
corvid_store::pin_shape!(Scribe {
    suggestions,
    decided,
    summaries
});

pub fn open(path: &Path) -> io::Result<Scribe> {
    corvid_store::open(path, SCHEMA)
}

pub fn save(path: &Path, scribe: &Scribe) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, scribe)
}
