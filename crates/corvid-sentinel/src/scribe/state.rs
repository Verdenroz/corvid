use std::collections::BTreeMap;
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Scribe`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 1;
const DECIDED_CAP: usize = 1000;
const SUMMARY_CAP: usize = 5000;
const DIGEST_CAP: usize = 30;

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
#[serde(rename_all = "camelCase")]
pub struct Suggestion {
    pub id: String,
    pub sender: Sender,
    pub action: Action,
    pub settled: u32,
    pub hits: u32,
    #[serde(default)]
    pub history: Vec<bool>,
    #[serde(default)]
    pub span_days: u32,
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

/// One morning's Inbox mail after `from` up to `to`, in epoch seconds. Its id is the local date it
/// was built for.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Digest {
    pub id: String,
    pub from: i64,
    pub to: i64,
    pub groups: Vec<Group>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Group {
    pub label: Option<String>,
    pub items: Vec<Item>,
}

/// A thread's newest Inbox message in the window.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Item {
    pub thread_id: String,
    pub email_id: String,
    pub from: String,
    #[serde(default)]
    pub from_name: Option<String>,
    #[serde(default)]
    pub is_list: bool,
    pub subject: String,
    pub received_at: i64,
    pub summary: Option<String>,
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
    /// Oldest first, only the newest `DIGEST_CAP` kept.
    #[serde(default)]
    pub digests: Vec<Digest>,
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

    pub fn with_digest(&self, digest: Digest) -> Self {
        let mut digests = self.digests.clone();
        digests.push(digest);
        let excess = digests.len().saturating_sub(DIGEST_CAP);
        digests.drain(..excess);
        Self {
            digests,
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
    hits,
    history,
    span_days
});
corvid_store::pin_shape!(Decision { id, verdict });
corvid_store::pin_shape!(Summary {
    input_hash,
    text,
    model,
    generated_at
});
corvid_store::pin_shape!(Digest {
    id,
    from,
    to,
    groups
});
corvid_store::pin_shape!(Group { label, items });
corvid_store::pin_shape!(Item {
    thread_id,
    email_id,
    from,
    from_name,
    is_list,
    subject,
    received_at,
    summary
});
corvid_store::pin_shape!(Scribe {
    suggestions,
    decided,
    summaries,
    digests
});

pub fn open(path: &Path) -> io::Result<Scribe> {
    corvid_store::open(path, SCHEMA)
}

pub fn save(path: &Path, scribe: &Scribe) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, scribe)
}
