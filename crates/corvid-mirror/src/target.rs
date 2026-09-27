use std::collections::BTreeSet;
use std::io;

/// The restore destination, kept behind a trait so restore is exercised without a server.
pub trait Target {
    /// Identifies the account, so a restore ledger built against one target refuses to run
    /// against another.
    fn account_id(&self) -> String;
    /// Every `Message-Id` header value already on the target. Informational only: restore
    /// dedups by its own ledger, since a `Message-Id` is not unique.
    fn message_ids(&self) -> io::Result<BTreeSet<String>>;
    /// Returns the id of the mailbox at this full path, creating any level that is missing.
    fn ensure_mailbox(&self, path: &str) -> io::Result<String>;
    fn upload(&self, bytes: &[u8]) -> io::Result<String>;
    /// Imports a message, returning the id the target assigned it.
    fn import(
        &self,
        blob_id: &str,
        mailbox_ids: &BTreeSet<String>,
        received_at: &str,
        keywords: &BTreeSet<String>,
    ) -> io::Result<String>;
    /// Which of these target ids the target still has.
    fn existing(&self, ids: &[String]) -> io::Result<BTreeSet<String>>;
}
