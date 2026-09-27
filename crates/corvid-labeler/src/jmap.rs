use std::collections::BTreeSet;
use std::io;

use corvid_ingest::MODEL_DONE;
use corvid_jmap::{Client, ids, missing, text};
use serde_json::{Value, json};

use crate::dates;
use crate::mailbox::{Changes, Mailbox, Message};

const MAX_CHANGES: u32 = 500;

/// Every instance of the header, in order. Only the first was written by the receiving server;
/// the rest arrived with the message and a forged one is indistinguishable except by position.
const AUTHENTICATION: &str = "header:Authentication-Results:asText:all";
const LIST_ID: &str = "header:List-Id:asText";

const PROPERTIES: [&str; 7] = [
    "id",
    "from",
    "subject",
    "keywords",
    "receivedAt",
    LIST_ID,
    AUTHENTICATION,
];

pub struct Jmap {
    client: Client,
}

impl Jmap {
    pub fn connect(base_url: &str, user: &str, password: &str) -> io::Result<Self> {
        Ok(Self {
            client: Client::connect(base_url, user, password)?,
        })
    }

    fn account(&self) -> &str {
        self.client.account_id()
    }
}

impl Mailbox for Jmap {
    fn changes(&self, since: Option<&str>) -> io::Result<Changes> {
        // A first run adopts the current position; historical mail is the cold-start pass's job.
        let Some(since) = since else {
            let state = self.client.call(
                "Email/get",
                json!({"accountId": self.account(), "ids": [], "properties": ["id"]}),
            )?;
            let new_state = text(&state["state"]).ok_or_else(|| missing("a mailbox state"))?;
            return Ok(Changes {
                created: Vec::new(),
                updated: Vec::new(),
                new_state,
            });
        };

        let changes = self.client.call(
            "Email/changes",
            json!({
                "accountId": self.account(),
                "sinceState": since,
                "maxChanges": MAX_CHANGES,
            }),
        )?;
        Ok(Changes {
            created: ids(&changes["created"]),
            updated: ids(&changes["updated"]),
            new_state: text(&changes["newState"]).ok_or_else(|| missing("a new state"))?,
        })
    }

    fn fetch(&self, wanted: &[String]) -> io::Result<Vec<Message>> {
        if wanted.is_empty() {
            return Ok(Vec::new());
        }
        let result = self.client.call(
            "Email/get",
            json!({
                "accountId": self.account(),
                "ids": wanted,
                "properties": PROPERTIES,
            }),
        )?;
        Ok(result["list"]
            .as_array()
            .map(|list| list.iter().filter_map(message).collect())
            .unwrap_or_default())
    }

    fn awaiting_model(&self, limit: usize) -> io::Result<Vec<Message>> {
        let query = self.client.call(
            "Email/query",
            json!({
                "accountId": self.account(),
                "filter": {"notKeyword": MODEL_DONE},
                "sort": [{"property": "receivedAt", "isAscending": true}],
                "limit": limit,
            }),
        )?;
        self.fetch(&ids(&query["ids"]))
    }

    fn apply_keywords(
        &self,
        id: &str,
        add: &BTreeSet<String>,
        remove: &BTreeSet<String>,
    ) -> io::Result<()> {
        if add.is_empty() && remove.is_empty() {
            return Ok(());
        }
        let patch: serde_json::Map<String, Value> = add
            .iter()
            .map(|keyword| (path(keyword), Value::Bool(true)))
            .chain(remove.iter().map(|keyword| (path(keyword), Value::Null)))
            .collect();
        let result = self.client.call(
            "Email/set",
            json!({"accountId": self.account(), "update": {id: patch}}),
        )?;
        match result["notUpdated"].get(id) {
            Some(problem) => Err(io::Error::other(format!("{id} was not updated: {problem}"))),
            None => Ok(()),
        }
    }
}

fn message(value: &Value) -> Option<Message> {
    Some(Message {
        id: text(&value["id"])?,
        from: text(&value["from"][0]["email"])?,
        subject: text(&value["subject"]).unwrap_or_default(),
        list_id: text(&value[LIST_ID]),
        authentication_results: value[AUTHENTICATION]
            .as_array()
            .map(|all| all.iter().filter_map(text).collect())
            .unwrap_or_default(),
        keywords: value["keywords"]
            .as_object()
            .map(|map| map.keys().cloned().collect())
            .unwrap_or_default(),
        received_at_days: text(&value["receivedAt"])
            .as_deref()
            .and_then(dates::days)?,
    })
}

/// A patch key is a JSON Pointer, so an unescaped `/` in a label splits the path and the server
/// stores a truncated keyword while reporting success. Escape `~` before `/`, or the
/// replacement's own tildes get escaped again.
fn path(keyword: &str) -> String {
    format!("keywords/{}", keyword.replace('~', "~0").replace('/', "~1"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_plain_label_needs_no_escaping() {
        assert_eq!(path("corvid-receipts"), "keywords/corvid-receipts");
    }

    // A split pointer stores `corvid-x`, so the desired set never matches and every poll rewrites.
    #[test]
    fn a_slash_in_a_label_does_not_split_the_path() {
        assert_eq!(path("corvid-x/y"), "keywords/corvid-x~1y");
    }

    #[test]
    fn a_tilde_is_escaped_before_the_slash() {
        assert_eq!(path("corvid-p~q"), "keywords/corvid-p~0q");
        assert_eq!(path("corvid-a~/b"), "keywords/corvid-a~0~1b");
    }
}
