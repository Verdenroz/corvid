use std::collections::BTreeSet;
use std::io;

use serde::{Deserialize, Serialize};

/// Where the scribe stands in the server's two change feeds.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Cursors {
    pub email: String,
    pub thread: String,
}

pub struct Changes {
    /// Threads that gained, lost or moved a message, or were destroyed.
    pub threads: Vec<String>,
    pub cursors: Cursors,
}

/// One folder, matched against the scribe's exclusions by name or role.
pub struct Folder {
    pub id: String,
    pub name: String,
    pub role: Option<String>,
}

/// A message of a thread, known by id and folders alone so a thread can be checked for change
/// before any body is downloaded.
pub struct Member {
    pub id: String,
    pub mailbox_ids: BTreeSet<String>,
}

pub struct Mail {
    pub id: String,
    pub from: String,
    /// The JMAP UTC date, which sorts as text.
    pub received_at: String,
    /// Plain text without quoted lines.
    pub body: String,
}

pub trait Mailbox {
    /// What changed since `since`. A first run, with no cursors, adopts the server's current
    /// position and reports nothing: history is not summarized. So does a cursor the server can
    /// no longer calculate changes from; those threads are judged when they next change.
    fn changes(&self, since: Option<&Cursors>) -> io::Result<Changes>;

    fn folders(&self) -> io::Result<Vec<Folder>>;

    /// Every message of the thread, empty when the thread no longer exists.
    fn members(&self, thread_id: &str) -> io::Result<Vec<Member>>;

    fn mails(&self, ids: &[String]) -> io::Result<Vec<Mail>>;
}
