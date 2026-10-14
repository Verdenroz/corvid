use std::collections::BTreeSet;
use std::io;

use corvid_jmap::{Client, ids, missing, text};
use serde_json::{Value, json};

use crate::body;
use crate::mailbox::{Changes, Cursors, Folder, Mail, Mailbox};

const MAX_CHANGES: u32 = 500;

/// Per `Email/get`, which servers cap at their own `maxObjectsInGet`.
const GET_CHUNK: usize = 100;

/// A body is cut here by the server; a thread's input is cut far shorter anyway.
const MAX_BODY_BYTES: u32 = 65536;

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

    fn current_state(&self, method: &str) -> io::Result<String> {
        let result = self.client.call(
            method,
            json!({"accountId": self.account(), "ids": [], "properties": ["id"]}),
        )?;
        text(&result["state"]).ok_or_else(|| missing("a state"))
    }

    fn thread_ids_of(&self, emails: &[String]) -> io::Result<Vec<String>> {
        let mut threads = Vec::new();
        for chunk in emails.chunks(GET_CHUNK) {
            let result = self.client.call(
                "Email/get",
                json!({"accountId": self.account(), "ids": chunk, "properties": ["threadId"]}),
            )?;
            let list = result["list"]
                .as_array()
                .ok_or_else(|| missing("an email list"))?;
            threads.extend(list.iter().filter_map(|email| text(&email["threadId"])));
        }
        Ok(threads)
    }
}

impl Mailbox for Jmap {
    fn changes(&self, since: Option<&Cursors>) -> io::Result<Changes> {
        let Some(since) = since else {
            return Ok(Changes {
                threads: Vec::new(),
                cursors: Cursors {
                    email: self.current_state("Email/get")?,
                    thread: self.current_state("Thread/get")?,
                },
            });
        };

        let emails = self.client.call(
            "Email/changes",
            json!({
                "accountId": self.account(),
                "sinceState": since.email,
                "maxChanges": MAX_CHANGES,
            }),
        )?;
        let touched: Vec<String> = ids(&emails["created"])
            .into_iter()
            .chain(ids(&emails["updated"]))
            .collect();
        let mut threads: BTreeSet<String> = self.thread_ids_of(&touched)?.into_iter().collect();

        let changed = self.client.call(
            "Thread/changes",
            json!({
                "accountId": self.account(),
                "sinceState": since.thread,
                "maxChanges": MAX_CHANGES,
            }),
        )?;
        for key in ["created", "updated", "destroyed"] {
            threads.extend(ids(&changed[key]));
        }

        Ok(Changes {
            threads: threads.into_iter().collect(),
            cursors: Cursors {
                email: text(&emails["newState"]).ok_or_else(|| missing("a new email state"))?,
                thread: text(&changed["newState"]).ok_or_else(|| missing("a new thread state"))?,
            },
        })
    }

    fn folders(&self) -> io::Result<Vec<Folder>> {
        let result = self.client.call(
            "Mailbox/get",
            json!({"accountId": self.account(), "ids": null, "properties": ["id", "name", "role"]}),
        )?;
        let list = result["list"]
            .as_array()
            .ok_or_else(|| missing("a mailbox list"))?;
        Ok(list.iter().filter_map(folder).collect())
    }

    fn thread(&self, id: &str) -> io::Result<Vec<Mail>> {
        let thread = self.client.call(
            "Thread/get",
            json!({"accountId": self.account(), "ids": [id]}),
        )?;
        let emails = ids(&thread["list"][0]["emailIds"]);

        let mut mails = Vec::new();
        for chunk in emails.chunks(GET_CHUNK) {
            let result = self.client.call(
                "Email/get",
                json!({
                    "accountId": self.account(),
                    "ids": chunk,
                    "properties": ["id", "mailboxIds", "from", "receivedAt", "textBody", "bodyValues"],
                    "fetchTextBodyValues": true,
                    "maxBodyValueBytes": MAX_BODY_BYTES,
                }),
            )?;
            let list = result["list"]
                .as_array()
                .ok_or_else(|| missing("an email list"))?;
            mails.extend(list.iter().filter_map(mail));
        }
        Ok(mails)
    }
}

fn folder(value: &Value) -> Option<Folder> {
    Some(Folder {
        id: text(&value["id"])?,
        name: text(&value["name"])?,
        role: text(&value["role"]),
    })
}

/// A message whose `textBody` lists an html part gets that part stripped to text, since the
/// server may or may not have converted it.
fn mail(value: &Value) -> Option<Mail> {
    let parts = value["textBody"].as_array().map_or(&[][..], Vec::as_slice);
    let body: Vec<String> = parts
        .iter()
        .filter_map(|part| {
            let content = text(&value["bodyValues"][text(&part["partId"])?]["value"])?;
            Some(body::plain(
                part["type"].as_str().unwrap_or("text/plain"),
                &content,
            ))
        })
        .collect();

    Some(Mail {
        id: text(&value["id"])?,
        mailbox_ids: value["mailboxIds"]
            .as_object()
            .map(|map| map.keys().cloned().collect())
            .unwrap_or_default(),
        from: text(&value["from"][0]["email"]).unwrap_or_default(),
        received_at: text(&value["receivedAt"])?,
        body: body.join("\n").trim().to_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_plain_text_message_is_read_with_its_mailboxes() {
        let email = json!({
            "id": "e1",
            "mailboxIds": {"m1": true, "m2": true},
            "from": [{"name": "Ann", "email": "ann@x.test"}],
            "receivedAt": "2026-10-01T09:00:00Z",
            "textBody": [{"partId": "1", "type": "text/plain"}],
            "bodyValues": {"1": {"value": "Hello\n> quoted", "isTruncated": false}},
        });

        let mail = mail(&email).unwrap();

        assert_eq!(mail.id, "e1");
        assert_eq!(mail.mailbox_ids, BTreeSet::from(["m1".into(), "m2".into()]));
        assert_eq!(mail.from, "ann@x.test");
        assert_eq!(mail.body, "Hello");
    }

    #[test]
    fn an_html_only_message_is_stripped_to_text() {
        let email = json!({
            "id": "e1",
            "mailboxIds": {"m1": true},
            "from": [{"email": "ann@x.test"}],
            "receivedAt": "2026-10-01T09:00:00Z",
            "textBody": [{"partId": "1", "type": "text/html"}],
            "bodyValues": {"1": {"value": "<p>Hi <b>there</b></p><blockquote>old</blockquote>"}},
        });

        assert_eq!(mail(&email).unwrap().body, "Hi there");
    }

    #[test]
    fn several_text_parts_are_joined_in_order() {
        let email = json!({
            "id": "e1",
            "mailboxIds": {},
            "receivedAt": "2026-10-01T09:00:00Z",
            "textBody": [{"partId": "1", "type": "text/plain"}, {"partId": "2", "type": "text/plain"}],
            "bodyValues": {"1": {"value": "one"}, "2": {"value": "two"}},
        });

        let mail = mail(&email).unwrap();

        assert_eq!(mail.body, "one\ntwo");
        assert_eq!(mail.from, "");
    }

    #[test]
    fn an_email_without_a_receipt_date_is_skipped() {
        assert!(mail(&json!({"id": "e1"})).is_none());
    }

    #[test]
    fn a_folder_carries_its_role_when_it_has_one() {
        let inbox = folder(&json!({"id": "a", "name": "Inbox", "role": "inbox"})).unwrap();
        let plain = folder(&json!({"id": "b", "name": "Receipts", "role": null})).unwrap();

        assert_eq!(inbox.role.as_deref(), Some("inbox"));
        assert_eq!(plain.role, None);
        assert_eq!(plain.name, "Receipts");
    }
}
