use std::io;

use corvid_jmap::{Client, ids, missing};
use serde_json::{Map, Value, json};

use crate::service::Notifier;

/// Under the `corvid-` namespace so nothing reads it back as a user's own keyword.
const ALERT_KEYWORD: &str = "corvid-sentinel-alert";

pub struct Jmap {
    client: Client,
    inbox_id: String,
    account_email: String,
}

impl Jmap {
    pub fn connect(base_url: &str, user: &str, password: &str) -> io::Result<Self> {
        let client = Client::connect(base_url, user, password)?;
        let inbox_id = inbox_id(&client)?;
        Ok(Self {
            client,
            inbox_id,
            account_email: user.to_owned(),
        })
    }
}

impl Notifier for Jmap {
    fn account_email(&self) -> &str {
        &self.account_email
    }

    fn send(&self, message: &[u8]) -> io::Result<()> {
        let blob_id = self.client.upload("message/rfc822", message.to_vec())?;

        let mut mailbox_ids = Map::new();
        mailbox_ids.insert(self.inbox_id.clone(), Value::Bool(true));
        let mut keywords = Map::new();
        keywords.insert(ALERT_KEYWORD.to_owned(), Value::Bool(true));
        let mut email = Map::new();
        email.insert("blobId".to_owned(), Value::String(blob_id));
        email.insert("mailboxIds".to_owned(), Value::Object(mailbox_ids));
        email.insert("keywords".to_owned(), Value::Object(keywords));
        let mut emails = Map::new();
        emails.insert("n1".to_owned(), Value::Object(email));

        let result = self.client.call(
            "Email/import",
            json!({"accountId": self.client.account_id(), "emails": emails}),
        )?;
        match result["notCreated"].get("n1") {
            Some(problem) => Err(io::Error::other(format!(
                "the notice was not created: {problem}"
            ))),
            None => Ok(()),
        }
    }
}

fn inbox_id(client: &Client) -> io::Result<String> {
    let result = client.call(
        "Mailbox/query",
        json!({"accountId": client.account_id(), "filter": {"role": "inbox"}}),
    )?;
    ids(&result["ids"])
        .into_iter()
        .next()
        .ok_or_else(|| missing("an Inbox mailbox"))
}
