use std::collections::BTreeSet;
use std::io;

/// The restore destination, kept behind a trait so restore is exercised without a server.
pub trait Target {
    /// Every `Message-Id` header value already on the target, for idempotency and the final
    /// audit.
    fn message_ids(&self) -> io::Result<BTreeSet<String>>;
    /// Returns the id of the mailbox at this full path, creating any level that is missing.
    fn ensure_mailbox(&self, path: &str) -> io::Result<String>;
    fn upload(&self, bytes: &[u8]) -> io::Result<String>;
    fn import(
        &self,
        blob_id: &str,
        mailbox_ids: &BTreeSet<String>,
        received_at: &str,
        keywords: &BTreeSet<String>,
    ) -> io::Result<()>;
}
