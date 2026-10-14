use corvid_label::SenderKey;

use super::Delta;
use super::state::{Actions, Exit, Pending, Tally};
use crate::mailbox::{Message, Roles};

const DAY: i64 = 86400;
const FRESH: i64 = 15 * 60;
const SETTLE: i64 = 3 * DAY;
const THREAD_MEMORY: i64 = 30 * DAY;
const KEPT_DAYS: usize = 200;

impl Actions {
    pub fn observe(&mut self, now: i64, user: &str, roles: &Roles, delta: &Delta) {
        for message in delta.messages {
            if let Some(pending) = self.pending.get_mut(&message.id) {
                follow(pending, message, roles);
            } else if delta.created.contains(&message.id) {
                self.admit(now, user, roles, message);
            }
        }
        for id in delta.destroyed {
            if let Some(pending) = self.pending.get_mut(id)
                && (pending.left.is_some() || !pending.seen)
            {
                pending.left = Some(Exit::Trash);
            }
        }
    }

    pub fn settle(&mut self, now: i64) {
        let due: Vec<String> = self
            .pending
            .iter()
            .filter(|(_, pending)| now - pending.first_seen >= SETTLE)
            .map(|(id, _)| id.clone())
            .collect();
        for id in due {
            let Some(pending) = self.pending.remove(&id) else {
                continue;
            };
            if pending.left == Some(Exit::Junk) {
                continue;
            }
            let tally = self.keys.entry(pending.key).or_default();
            record(tally, pending.first_seen / DAY, pending.left);
        }
        self.threads.retain(|_, seen| now - *seen < THREAD_MEMORY);
    }

    fn admit(&mut self, now: i64, user: &str, roles: &Roles, message: &Message) {
        if !in_inbox(message, roles)
            || now - message.received_at_secs > FRESH
            || message.keywords.contains("$seen")
            || message.from.eq_ignore_ascii_case(user)
        {
            return;
        }
        let Some(key) = key_of(message) else {
            return;
        };
        let thread = format!("{}\0{key}", message.thread_id);
        if self.threads.contains_key(&thread) {
            return;
        }
        self.threads.insert(thread, now);
        self.pending.insert(
            message.id.clone(),
            Pending {
                key,
                first_seen: now,
                seen: false,
                left: None,
            },
        );
    }
}

fn in_inbox(message: &Message, roles: &Roles) -> bool {
    roles
        .inbox
        .as_ref()
        .is_some_and(|inbox| message.mailbox_ids.contains(inbox))
}

fn follow(pending: &mut Pending, message: &Message, roles: &Roles) {
    let seen = message.keywords.contains("$seen");
    if in_inbox(message, roles) {
        pending.left = None;
        pending.seen |= seen;
    } else if pending.left.is_some() || !(pending.seen || seen) {
        pending.left = Some(exit_of(message, roles));
    } else {
        pending.seen = true;
    }
}

fn exit_of(message: &Message, roles: &Roles) -> Exit {
    let has = |role: &Option<String>| {
        role.as_ref()
            .is_some_and(|id| message.mailbox_ids.contains(id))
    };
    if has(&roles.trash) {
        Exit::Trash
    } else if has(&roles.junk) {
        Exit::Junk
    } else if has(&roles.archive) {
        Exit::Archive
    } else {
        Exit::Folder(
            message
                .mailbox_ids
                .iter()
                .next()
                .cloned()
                .unwrap_or_default(),
        )
    }
}

fn record(tally: &mut Tally, day: i64, left: Option<Exit>) {
    tally.settled += 1;
    if let Some(exit) = left {
        *tally.exits.entry(exit.label()).or_default() += 1;
    }
    tally.days.insert(day);
    while tally.days.len() > KEPT_DAYS {
        tally.days.pop_first();
    }
}

/// The exact From address rather than a domain, so a rule built on it cannot sweep up a
/// different sender at the same provider.
fn key_of(message: &Message) -> Option<String> {
    let results: Vec<&str> = message
        .authentication_results
        .iter()
        .map(String::as_str)
        .collect();
    match corvid_ingest::sender_key(&message.from, message.list_id.as_deref(), &results)? {
        SenderKey::ListId(id) => Some(format!("list:{id}")),
        SenderKey::Domain(_) => Some(format!("from:{}", message.from.to_ascii_lowercase())),
    }
}
