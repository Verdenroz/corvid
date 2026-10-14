//! Records what the user does with each new Inbox arrival and mines it for rule candidates.
//!
//! Only arrivals the labeler sees within minutes of delivery are observed, and an outcome is
//! decided after a settle window, so one reading session cannot make a rule.

mod mine;
mod state;
mod track;

use std::io;
use std::path::{Path, PathBuf};

use corvid_store::{open, save};

use crate::mailbox::{Mailbox, Message};
use state::{Actions, SCHEMA};
pub use state::{Candidate, Exit};

/// What one poll saw change.
pub struct Delta<'a> {
    pub messages: &'a [Message],
    pub created: &'a [String],
    pub destroyed: &'a [String],
}

/// The action log, held in memory and written only when a poll changed it.
pub struct ActionLog {
    path: PathBuf,
    actions: Actions,
}

impl ActionLog {
    /// A corrupt or foreign-schema file is `InvalidData`, a refusal like the labeler's own state.
    pub fn open(path: &Path) -> io::Result<Self> {
        Ok(Self {
            path: path.to_owned(),
            actions: open(path, SCHEMA)?,
        })
    }

    /// Folds one poll into the log and settles whatever has waited long enough.
    pub fn record(
        &mut self,
        mailbox: &impl Mailbox,
        user: &str,
        now: i64,
        delta: &Delta,
    ) -> io::Result<()> {
        let observed = if delta.messages.is_empty() {
            false
        } else {
            let roles = mailbox.roles()?;
            self.actions.observe(now, user, &roles, delta)
        };
        let expunged = self.actions.expunge(delta.destroyed);
        let settled = self.actions.settle(now);
        if observed || expunged || settled {
            save(&self.path, SCHEMA, &self.actions)?;
        }
        Ok(())
    }

    /// Drops history older than the window and rewrites the candidates from what remains.
    pub fn mine(&mut self, now: i64) -> io::Result<&[Candidate]> {
        self.actions.forget_before(now);
        self.actions.mine();
        save(&self.path, SCHEMA, &self.actions)?;
        Ok(&self.actions.candidates)
    }
}
