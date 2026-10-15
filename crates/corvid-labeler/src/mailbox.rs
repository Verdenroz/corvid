use std::collections::BTreeSet;
use std::io;

use corvid_label::SenderKey;

/// The properties the labeler reads for one message.
///
/// `authentication_results` keeps every instance in the order they appear, because only the
/// topmost one was written by the receiving server and the rest arrived with the message.
#[derive(Clone)]
pub struct Message {
    pub id: String,
    pub from: String,
    pub subject: String,
    pub list_id: Option<String>,
    pub authentication_results: Vec<String>,
    pub keywords: BTreeSet<String>,
    pub received_at_secs: i64,
    pub thread_id: String,
    pub mailbox_ids: BTreeSet<String>,
}

impl Message {
    /// Whole days since the epoch: the prior's half-life is measured in months, and a whole-day
    /// value makes a correction on the day of receipt retract exactly what it observed.
    pub fn received_at_days(&self) -> f64 {
        self.received_at_secs.div_euclid(86400) as f64
    }

    pub fn sender_key(&self) -> Option<SenderKey> {
        let results: Vec<&str> = self
            .authentication_results
            .iter()
            .map(String::as_str)
            .collect();
        corvid_ingest::sender_key(&self.from, self.list_id.as_deref(), &results)
    }
}

pub struct Changes {
    pub created: Vec<String>,
    pub updated: Vec<String>,
    pub destroyed: Vec<String>,
    pub new_state: String,
}

/// The ids of the mailboxes that have a role the labeler cares about.
#[derive(Debug, Clone)]
pub struct Roles {
    pub inbox: String,
    pub archive: Option<String>,
    pub trash: Option<String>,
    pub junk: Option<String>,
}

/// What the labeler needs from a mail store, so the loop can be exercised without one.
pub trait Mailbox {
    fn changes(&self, since: Option<&str>) -> io::Result<Changes>;
    fn roles(&self) -> io::Result<Roles>;
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
