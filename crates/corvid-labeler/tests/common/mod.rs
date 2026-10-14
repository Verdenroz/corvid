#![allow(dead_code)]

use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::{Path, PathBuf};
use std::time::Duration;

use corvid_ingest::MODEL_DONE;
use corvid_labeler::{Changes, Mailbox, Message, Oracle, Roles};

pub const INBOX: &str = "inbox";
pub const ARCHIVE: &str = "archive";
pub const TRASH: &str = "trash";
pub const JUNK: &str = "junk";

const OURS: &str = "mail.corvid.test; dmarc=pass header.from=acme.test";

/// A cursor names a position in the delivery order, and only messages after it are new.
/// `replay` reproduces a corrupted cursor, which the real server answers by reporting old
/// messages as created rather than by erroring.
#[derive(Default)]
pub struct Fake {
    pub messages: RefCell<BTreeMap<String, Message>>,
    pub order: RefCell<Vec<String>>,
    pub written: RefCell<Vec<(String, BTreeSet<String>)>>,
    pub touched: RefCell<Vec<String>>,
    pub refuse_writes: Cell<bool>,
    pub replay: Cell<bool>,
    pub destroyed: RefCell<Vec<String>>,
}

impl Fake {
    pub fn deliver(&self, id: &str, from: &str, keywords: &[&str], day: f64) {
        self.messages.borrow_mut().insert(
            id.to_owned(),
            Message {
                id: id.to_owned(),
                from: from.to_owned(),
                subject: format!("about {id}"),
                list_id: None,
                authentication_results: vec![OURS.to_owned()],
                keywords: keywords.iter().map(|k| (*k).to_owned()).collect(),
                received_at_days: day,
                received_at_secs: day as i64 * 86400,
                thread_id: id.to_owned(),
                mailbox_ids: BTreeSet::from([INBOX.to_owned()]),
            },
        );
        self.order.borrow_mut().push(id.to_owned());
    }

    pub fn keywords_of(&self, id: &str) -> BTreeSet<String> {
        self.messages.borrow()[id].keywords.clone()
    }

    pub fn carrying(&self, keyword: &str) -> Vec<String> {
        self.messages
            .borrow()
            .values()
            .filter(|m| m.keywords.contains(keyword))
            .map(|m| m.id.clone())
            .collect()
    }

    /// Anything the user does to a message re-reports it as updated rather than created.
    pub fn touch(&self, id: &str) {
        self.touched.borrow_mut().push(id.to_owned());
    }
}

impl Mailbox for Fake {
    fn changes(&self, since: Option<&str>) -> io::Result<Changes> {
        let order = self.order.borrow();
        let new_state = format!("s{}", order.len());
        let created = match since {
            _ if self.replay.get() => order.clone(),
            None => Vec::new(),
            Some(cursor) => {
                let seen: usize = cursor.trim_start_matches('s').parse().unwrap_or(0);
                order.get(seen..).unwrap_or_default().to_vec()
            }
        };
        let updated = self.touched.borrow_mut().drain(..).collect();
        let destroyed = self.destroyed.borrow_mut().drain(..).collect();
        Ok(Changes {
            created,
            updated,
            destroyed,
            new_state,
        })
    }

    fn roles(&self) -> io::Result<Roles> {
        let id = |name: &str| Some(name.to_owned());
        Ok(Roles {
            inbox: id(INBOX),
            archive: id(ARCHIVE),
            trash: id(TRASH),
            junk: id(JUNK),
        })
    }

    fn fetch(&self, ids: &[String]) -> io::Result<Vec<Message>> {
        let messages = self.messages.borrow();
        Ok(ids
            .iter()
            .filter_map(|id| messages.get(id))
            .map(|m| Message {
                id: m.id.clone(),
                from: m.from.clone(),
                subject: m.subject.clone(),
                list_id: m.list_id.clone(),
                authentication_results: m.authentication_results.clone(),
                keywords: m.keywords.clone(),
                received_at_days: m.received_at_days,
                received_at_secs: m.received_at_secs,
                thread_id: m.thread_id.clone(),
                mailbox_ids: m.mailbox_ids.clone(),
            })
            .collect())
    }

    fn awaiting_model(&self, limit: usize) -> io::Result<Vec<Message>> {
        let ids: Vec<String> = self
            .order
            .borrow()
            .iter()
            .filter(|id| !self.messages.borrow()[*id].keywords.contains(MODEL_DONE))
            .take(limit)
            .cloned()
            .collect();
        self.fetch(&ids)
    }

    fn apply_keywords(
        &self,
        id: &str,
        add: &BTreeSet<String>,
        remove: &BTreeSet<String>,
    ) -> io::Result<()> {
        if self.refuse_writes.get() {
            return Err(io::Error::other("the server refused"));
        }
        self.written.borrow_mut().push((id.to_owned(), add.clone()));
        let mut messages = self.messages.borrow_mut();
        let keywords = &mut messages.get_mut(id).unwrap().keywords;
        keywords.extend(add.clone());
        keywords.retain(|keyword| !remove.contains(keyword));
        Ok(())
    }
}

pub fn scratch() -> (tempfile::TempDir, PathBuf) {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    (directory, path)
}

/// A scripted model. Each entry answers every rotation of one message, in order; once the
/// script runs out, `then` answers every remaining message.
pub struct Voice {
    script: RefCell<VecDeque<Utterance>>,
    then: Utterance,
    delay: Duration,
    current: RefCell<Option<(String, Utterance)>>,
    pub prompts: RefCell<Vec<String>>,
}

#[derive(Clone, Copy)]
pub enum Utterance {
    /// All probability on the letter offering this label, or "none of these labels".
    Choose(&'static str),
    /// Probability spread evenly over the offered letters.
    Hesitate,
    /// All probability on tokens that are not offered letters.
    Wander,
    /// A little probability on this label's letter and the rest off the offered letters, so
    /// the letters alone would read as certain.
    Drift(&'static str),
    Unreachable,
    /// Dies rather than returning, the way a panic or an OOM kill does.
    Killed,
}

pub const NONE: &str = "none of these labels";

impl Voice {
    pub fn always(utterance: Utterance) -> Self {
        Self::saying(&[], utterance)
    }

    pub fn saying(script: &[Utterance], then: Utterance) -> Self {
        Self {
            script: RefCell::new(script.iter().copied().collect()),
            then,
            delay: Duration::ZERO,
            current: RefCell::new(None),
            prompts: RefCell::new(Vec::new()),
        }
    }

    /// A model that answers, but slowly. Slow calls never error, so nothing but the clock
    /// stops a pass of them.
    pub fn taking(mut self, delay: Duration) -> Self {
        self.delay = delay;
        self
    }

    /// The distinct subjects asked about, in order.
    pub fn subjects(&self) -> Vec<String> {
        let mut seen: Vec<String> = Vec::new();
        for prompt in self.prompts.borrow().iter() {
            let subject = subject_of(prompt);
            if seen.last() != Some(&subject) {
                seen.push(subject);
            }
        }
        seen
    }

    fn utterance_for(&self, subject: &str) -> Utterance {
        let mut current = self.current.borrow_mut();
        match &*current {
            Some((asked, utterance)) if asked == subject => *utterance,
            _ => {
                let next = self.script.borrow_mut().pop_front().unwrap_or(self.then);
                *current = Some((subject.to_owned(), next));
                next
            }
        }
    }
}

fn subject_of(prompt: &str) -> String {
    prompt
        .lines()
        .find_map(|line| line.strip_prefix("Subject: "))
        .unwrap_or_default()
        .to_owned()
}

impl Oracle for Voice {
    fn choose(&self, prompt: &str, letters: &[char]) -> io::Result<Vec<f64>> {
        std::thread::sleep(self.delay);
        self.prompts.borrow_mut().push(prompt.to_owned());
        let offering = |label: &str| {
            letters
                .iter()
                .map(|l| {
                    let offered = prompt.lines().any(|line| line == format!("{l}: {label}"));
                    if offered { 1.0 } else { 0.0 }
                })
                .collect()
        };
        match self.utterance_for(&subject_of(prompt)) {
            Utterance::Choose(label) => Ok(offering(label)),
            Utterance::Hesitate => Ok(vec![1.0 / letters.len() as f64; letters.len()]),
            Utterance::Wander => Ok(vec![0.0; letters.len()]),
            Utterance::Drift(label) => {
                Ok(offering(label).into_iter().map(|p: f64| p * 0.3).collect())
            }
            Utterance::Unreachable => Err(io::Error::other("the model host is off")),
            Utterance::Killed => panic!("killed"),
        }
    }
}

/// Delivers and processes a sender's history, so the prior knows a label exists.
pub fn taught(path: &Path, mailbox: &Fake) {
    corvid_labeler::poll(path, mailbox).unwrap();
    for day in 0..12 {
        mailbox.deliver(
            &format!("m{day}"),
            "billing@acme.test",
            &["receipts"],
            day as f64,
        );
    }
    corvid_labeler::poll(path, mailbox).unwrap();
}

/// Historical mail nobody has labelled.
pub fn backlog(mailbox: &Fake, from: &str, prefix: &str, count: usize) {
    for n in 0..count {
        mailbox.deliver(&format!("{prefix}{n}"), from, &[], 12.0 + n as f64);
    }
}
