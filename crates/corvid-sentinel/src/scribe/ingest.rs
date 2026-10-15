use serde::Deserialize;

use super::state::{Action, Sender, SenderKind, Suggestion};

/// The labeler's post may be this far from the sentinel's clock either way.
pub(super) const SKEW_SECONDS: i64 = 300;

#[derive(Deserialize)]
pub(super) struct Post {
    pub(super) sent_at: i64,
    pub(super) candidates: Vec<Candidate>,
}

#[derive(Deserialize)]
pub(super) struct Candidate {
    id: String,
    key: String,
    outcome: String,
    settled: u32,
    hits: u32,
}

impl Candidate {
    /// `None` for a key or outcome this build does not know how to offer.
    pub(super) fn parse(self) -> Option<Suggestion> {
        let parsed = self.suggestion();
        if parsed.is_none() {
            tracing::warn!(key = %self.key, outcome = %self.outcome, "dropped a suggestion candidate");
        }
        parsed
    }

    fn suggestion(&self) -> Option<Suggestion> {
        let (kind, value) = self.key.split_once(':')?;
        let kind = match kind {
            "list" => SenderKind::List,
            "from" => SenderKind::From,
            _ => return None,
        };
        let action = match self.outcome.split_once(':') {
            None if self.outcome == "archive" => Action::Archive,
            None if self.outcome == "trash" => Action::Trash,
            Some(("folder", mailbox_id)) if !mailbox_id.is_empty() => Action::Folder {
                mailbox_id: mailbox_id.to_owned(),
            },
            _ => return None,
        };
        (!value.is_empty()).then(|| Suggestion {
            id: self.id.clone(),
            sender: Sender {
                kind,
                value: value.to_owned(),
            },
            action,
            settled: self.settled,
            hits: self.hits,
        })
    }
}
