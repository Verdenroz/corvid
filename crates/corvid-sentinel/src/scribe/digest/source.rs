use serde_json::{Value, json};

use super::build::Message;
use crate::dates::{rfc3339, seconds};
use crate::devices::keys::{Error, Keys, back, checked};

/// The newest messages kept when a window holds more.
const MAX_MESSAGES: u32 = 500;

/// The Inbox messages received after `from` up to and including `to`, newest first. Junk and
/// Trash are other mailboxes, so they are never read.
pub(super) async fn inbox(keys: &Keys, from: i64, to: i64) -> Result<Vec<Message>, Error> {
    let (account, inbox) = keys.inbox_id().await?;
    let answers = keys
        .batch(&[
            (
                "Email/query",
                json!({"accountId": account,
                       "filter": {"inMailbox": inbox, "after": rfc3339(from),
                                  "before": rfc3339(to + 1)},
                       "sort": [{"property": "receivedAt", "isAscending": false}],
                       "limit": MAX_MESSAGES}),
            ),
            (
                "Email/get",
                json!({"accountId": account,
                       "properties": ["id", "threadId", "from", "subject", "receivedAt", "keywords"],
                       "#ids": back("c0", "Email/query", "/ids")}),
            ),
        ])
        .await?;
    checked("Email/query", &answers[0])?;
    Ok(checked("Email/get", &answers[1])?["list"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(message)
        // Guards a server whose bounds differ from RFC 8621.
        .filter(|message| message.received_at > from && message.received_at <= to)
        .collect())
}

fn message(email: &Value) -> Option<Message> {
    let keywords = email["keywords"].as_object().into_iter().flatten();
    Some(Message {
        thread_id: email["threadId"].as_str()?.to_owned(),
        email_id: email["id"].as_str()?.to_owned(),
        from: email["from"][0]["email"]
            .as_str()
            .unwrap_or_default()
            .to_owned(),
        subject: email["subject"].as_str().unwrap_or_default().to_owned(),
        received_at: seconds(email["receivedAt"].as_str()?)?,
        keywords: keywords
            .filter(|(_, set)| *set == true)
            .map(|(keyword, _)| keyword.clone())
            .collect(),
    })
}
