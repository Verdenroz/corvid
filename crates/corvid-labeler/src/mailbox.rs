use std::collections::BTreeSet;
use std::io;

/// The properties the labeler reads for one message.
///
/// `authentication_results` keeps every instance in the order they appear, because only the
/// topmost one was written by the receiving server and the rest arrived with the message.
pub struct Message {
    pub id: String,
    pub from: String,
    pub subject: String,
    pub list_id: Option<String>,
    pub authentication_results: Vec<String>,
    pub keywords: BTreeSet<String>,
    pub received_at_days: f64,
}

pub struct Changes {
    pub created: Vec<String>,
    pub updated: Vec<String>,
    pub new_state: String,
}

/// What the labeler needs from a mail store, so the loop can be exercised without one.
pub trait Mailbox {
    fn changes(&self, since: Option<&str>) -> io::Result<Changes>;
    fn fetch(&self, ids: &[String]) -> io::Result<Vec<Message>>;
    /// Messages the model has not finished with, oldest first.
    ///
    /// Oldest first because the newest few hundred are whoever wrote this week. The window
    /// advances as the queue drains, so successive passes cover the mailbox with no stored
    /// position.
    fn awaiting_model(&self, limit: usize) -> io::Result<Vec<Message>>;
    /// Adds and removes keywords in one update. The namespace is the labeler's, so a label it
    /// no longer applies has to come off, not merely stop being written.
    fn apply_keywords(
        &self,
        id: &str,
        add: &BTreeSet<String>,
        remove: &BTreeSet<String>,
    ) -> io::Result<()>;
}
