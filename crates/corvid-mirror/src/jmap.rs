use std::collections::BTreeMap;
use std::io;

use corvid_jmap::{Client, ids, missing, text};
use serde_json::{Value, json};

use crate::source::{Changes, Listing, Record, Source};

/// The session advertises `maxObjectsInGet: 500`, so property fetches are chunked. A fetch that
/// silently returned a prefix would look like a mailbox with fewer messages.
const PER_GET: usize = 500;
/// Deliberately small enough that the paging loop runs every night, rather than first running
/// unattended the night the mailbox outgrows one window. Matches `maxObjectsInGet`.
const PER_QUERY: usize = 500;
const MAX_CHANGES: u32 = 500;

pub struct Mailbox {
    client: Client,
}

impl Mailbox {
    pub fn connect(base_url: &str, user: &str, password: &str) -> io::Result<Self> {
        Ok(Self {
            client: Client::connect(base_url, user, password)?,
        })
    }

    fn account(&self) -> &str {
        self.client.account_id()
    }
}

impl Source for Mailbox {
    fn changes(&self, since: Option<&str>) -> io::Result<Changes> {
        let since = since.ok_or_else(|| missing("a cursor to poll from"))?;
        let changes = self.client.call(
            "Email/changes",
            json!({"accountId": self.account(), "sinceState": since, "maxChanges": MAX_CHANGES}),
        )?;
        Ok(Changes {
            created: ids(&changes["created"]),
            updated: ids(&changes["updated"]),
            new_state: text(&changes["newState"]).ok_or_else(|| missing("a new state"))?,
        })
    }

    fn records(&self, wanted: &[String]) -> io::Result<Vec<Record>> {
        let mailboxes = self.mailbox_names()?;
        let mut records = Vec::with_capacity(wanted.len());
        for chunk in wanted.chunks(PER_GET) {
            let result = self.client.call(
                "Email/get",
                json!({
                    "accountId": self.account(),
                    "ids": chunk,
                    "properties": ["id", "blobId", "receivedAt", "size", "keywords", "mailboxIds"],
                }),
            )?;
            let list = result["list"]
                .as_array()
                .ok_or_else(|| missing("a message list"))?;
            records.extend(list.iter().filter_map(|value| record(value, &mailboxes)));
        }
        Ok(records)
    }

    fn blob(&self, blob_id: &str) -> io::Result<Vec<u8>> {
        self.client.download(blob_id)
    }

    fn state(&self) -> io::Result<String> {
        let result = self.client.call(
            "Email/get",
            json!({"accountId": self.account(), "ids": [], "properties": ["id"]}),
        )?;
        text(&result["state"]).ok_or_else(|| missing("a mailbox state"))
    }

    /// Every id, paged by anchor rather than by position.
    ///
    /// Position paging skips one live message for every deletion behind the cursor, and a
    /// count check misses it whenever an arrival replaces the deletion. An anchor is an
    /// identity, so deletions elsewhere cannot move the window; a deleted anchor fails loudly
    /// with `anchorNotFound`.
    fn all_ids(&self) -> io::Result<Listing> {
        let first = self.page(json!({"position": 0}))?;
        let mut ids = first.ids;
        let mut total = first.total;
        while let Some(anchor) = ids.last().cloned() {
            let page = self.page(json!({"anchor": anchor, "anchorOffset": 1}))?;
            total = page.total;
            if page.ids.is_empty() {
                break;
            }
            // A window still containing its anchor has not advanced; extending would loop.
            if page.ids.contains(&anchor) {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("the listing did not advance past {anchor}"),
                ));
            }
            ids.extend(page.ids);
        }
        Ok(Listing { ids, total })
    }
}

impl Mailbox {
    /// Every mailbox's id mapped to its name, refetched each call: cheap next to a page of
    /// messages, and a rename must not stick to whatever name was cached from an earlier run.
    fn mailbox_names(&self) -> io::Result<BTreeMap<String, String>> {
        let result = self.client.call(
            "Mailbox/get",
            json!({"accountId": self.account(), "ids": null}),
        )?;
        let list = result["list"]
            .as_array()
            .ok_or_else(|| missing("a mailbox list"))?;
        Ok(list
            .iter()
            .filter_map(|value| Some((text(&value["id"])?, text(&value["name"])?)))
            .collect())
    }

    fn page(&self, window: Value) -> io::Result<Listing> {
        let mut arguments = json!({
            "accountId": self.account(),
            "calculateTotal": true,
            "limit": PER_QUERY,
        });
        let object = arguments
            .as_object_mut()
            .expect("the argument literal is an object");
        for (key, value) in window.as_object().expect("the window literal is an object") {
            object.insert(key.clone(), value.clone());
        }
        let page = self.client.call("Email/query", arguments)?;
        Ok(Listing {
            ids: ids(&page["ids"]),
            total: page["total"].as_u64().ok_or_else(|| missing("a total"))? as usize,
        })
    }
}

fn record(value: &Value, mailboxes: &BTreeMap<String, String>) -> Option<Record> {
    Some(Record {
        id: text(&value["id"])?,
        blob_id: text(&value["blobId"])?,
        received_at: text(&value["receivedAt"])?,
        size: value["size"].as_u64()? as usize,
        keywords: value["keywords"]
            .as_object()
            .map(|map| map.keys().cloned().collect())
            .unwrap_or_default(),
        mailboxes: value["mailboxIds"]
            .as_object()
            .map(|ids| {
                ids.keys()
                    .filter_map(|id| mailboxes.get(id).cloned())
                    .collect()
            })
            .unwrap_or_default(),
    })
}
