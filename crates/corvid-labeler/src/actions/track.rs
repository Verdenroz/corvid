use std::collections::HashSet;
use std::mem;

use corvid_label::SenderKey;

use super::Delta;
use super::state::{Actions, Exit, Observation, Pending};
use crate::mailbox::{Message, Roles};

const DAY: i64 = 86400;
const FRESH: i64 = 15 * 60;
const SETTLE: i64 = 3 * DAY;
const THREAD_MEMORY: i64 = 30 * DAY;
const WINDOW: i64 = 180 * DAY;
const KEPT: usize = 200;
const SEEN: &str = "$seen";

impl Actions {
    /// Whether anything was admitted or moved.
    pub fn observe(&mut self, now: i64, user: &str, roles: &Roles, delta: &Delta) -> bool {
        let created: HashSet<&str> = delta.created.iter().map(String::as_str).collect();
        let mut changed = false;
        for message in delta.messages {
            if let Some(pending) = self.pending.get_mut(&message.id) {
                let before = pending.clone();
                follow(pending, message, roles);
                changed |= *pending != before;
            } else if created.contains(message.id.as_str()) {
                changed |= self.admit(now, user, roles, message);
            }
        }
        changed
    }

    /// A destroyed arrival left as Trash unless it was read first.
    pub fn expunge(&mut self, destroyed: &[String]) -> bool {
        let mut changed = false;
        for id in destroyed {
            if let Some(pending) = self.pending.get_mut(id)
                && (pending.left.is_some() || !pending.seen)
                && pending.left != Some(Exit::Trash)
            {
                pending.left = Some(Exit::Trash);
                changed = true;
            }
        }
        changed
    }

    pub fn settle(&mut self, now: i64) -> bool {
        let (due, waiting) = mem::take(&mut self.pending)
            .into_iter()
            .partition(|(_, pending)| now - pending.first_seen >= SETTLE);
        self.pending = waiting;
        let threads = self.threads.len();
        self.threads.retain(|_, seen| now - *seen < THREAD_MEMORY);
        let settled = !due.is_empty() || self.threads.len() != threads;
        for (_, pending) in due {
            record(
                self.keys.entry(pending.key).or_default(),
                pending.first_seen / DAY,
                pending.left,
            );
        }
        settled
    }

    pub fn forget_before(&mut self, now: i64) {
        for history in self.keys.values_mut() {
            history.retain(|seen| seen.day * DAY > now - WINDOW);
        }
        self.keys.retain(|_, history| !history.is_empty());
    }

    fn admit(&mut self, now: i64, user: &str, roles: &Roles, message: &Message) -> bool {
        if !in_inbox(message, roles)
            || now - message.received_at_secs > FRESH
            || message.from.eq_ignore_ascii_case(user)
        {
            return false;
        }
        let Some(key) = key_of(message) else {
            return false;
        };
        let thread = format!("{}\0{key}", message.thread_id);
        if self.threads.contains_key(&thread) {
            return false;
        }
        self.threads.insert(thread, now);
        self.pending.insert(
            message.id.clone(),
            Pending {
                key,
                first_seen: now,
                seen: message.keywords.contains(SEEN),
                left: None,
            },
        );
        true
    }
}

fn in_inbox(message: &Message, roles: &Roles) -> bool {
    message.mailbox_ids.contains(&roles.inbox)
}

fn follow(pending: &mut Pending, message: &Message, roles: &Roles) {
    let seen = message.keywords.contains(SEEN);
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
    } else if let Some(id) = message.mailbox_ids.iter().next() {
        Exit::Folder(id.clone())
    } else {
        Exit::Trash
    }
}

fn record(history: &mut Vec<Observation>, day: i64, left: Option<Exit>) {
    if left == Some(Exit::Junk) {
        return;
    }
    history.push(Observation { day, exit: left });
    if history.len() > KEPT {
        history.remove(0);
    }
}

/// The exact From address rather than a domain, so a rule built on it cannot sweep up a
/// different sender at the same provider.
fn key_of(message: &Message) -> Option<String> {
    match message.sender_key()? {
        list @ SenderKey::ListId(_) => Some(list.to_string()),
        SenderKey::Domain(_) => Some(format!("from:{}", message.from.to_ascii_lowercase())),
    }
}
