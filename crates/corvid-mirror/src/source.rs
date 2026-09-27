use std::collections::BTreeSet;
use std::io;

/// What the mirror needs of a message: enough to store it and to know it is the right bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Record {
    pub id: String,
    pub blob_id: String,
    pub received_at: String,
    /// The server's own count of the message's octets.
    ///
    /// The only check on a downloaded body that is not self-referential: a digest of the stored
    /// bytes would accept a truncated or padded download.
    pub size: usize,
    pub keywords: BTreeSet<String>,
    /// Mailbox names, as of the last sight. Restore recreates mailboxes by name, since a
    /// rebuilt server's mailbox ids never match the ones the archive was written against.
    pub mailboxes: BTreeSet<String>,
}

pub struct Listing {
    pub ids: Vec<String>,
    pub total: usize,
}

pub struct Changes {
    pub created: Vec<String>,
    pub updated: Vec<String>,
    pub new_state: String,
}

/// The server side, kept behind a trait so the sync and audit paths are exercised without one.
pub trait Source {
    fn changes(&self, since: Option<&str>) -> io::Result<Changes>;
    fn records(&self, ids: &[String]) -> io::Result<Vec<Record>>;
    fn blob(&self, blob_id: &str) -> io::Result<Vec<u8>>;
    /// The current change-feed position.
    fn state(&self) -> io::Result<String>;
    /// Every id the account currently holds, with the server's own count of them.
    ///
    /// The count lets a capped listing be detected instead of auditing a prefix as success.
    fn all_ids(&self) -> io::Result<Listing>;
}
