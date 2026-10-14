//! Records what the user does with each new Inbox arrival and mines it for rule candidates.
//!
//! Only arrivals the labeler sees within minutes of delivery are observed, and an outcome is
//! decided after a settle window, so one reading session cannot make a rule.

mod state;
mod track;

use std::io;
use std::path::Path;

use corvid_store::{open, save};

use crate::mailbox::{Mailbox, Message};
use state::{Actions, SCHEMA};

/// What one poll saw change.
pub struct Delta<'a> {
    pub messages: &'a [Message],
    pub created: &'a [String],
    pub destroyed: &'a [String],
}

/// Folds one poll into the action log and settles whatever has waited long enough.
pub fn record(
    path: &Path,
    mailbox: &impl Mailbox,
    user: &str,
    now: i64,
    delta: &Delta,
) -> io::Result<()> {
    let mut actions: Actions = open(path, SCHEMA)?;
    let roles = mailbox.roles()?;
    if roles.inbox.is_none() {
        return Err(io::Error::other("the server reports no inbox"));
    }
    actions.observe(now, user, &roles, delta);
    actions.settle(now);
    save(path, SCHEMA, &actions)
}
