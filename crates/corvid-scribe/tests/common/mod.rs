#![allow(dead_code)]

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::PathBuf;
use std::time::Duration;

use corvid_model::Written;
use corvid_scribe::{
    Changes, Cursors, Folder, Mail, Mailbox, Member, Settings, Sink, Summary, Upsert, Writer, run,
};

pub const INBOX: &str = "inbox";
pub const JUNK: &str = "junk";
pub const TRASH: &str = "trash";
pub const NOW: i64 = 1_800_000_000;

struct Stored {
    id: String,
    mailbox: String,
    mail: Mail,
}

/// A mailbox that reports as changed only the threads told to `touch`, as the real feeds do.
#[derive(Default)]
pub struct Fake {
    threads: RefCell<BTreeMap<String, Vec<Stored>>>,
    touched: RefCell<BTreeSet<String>>,
    adopted: Cell<bool>,
    pub bodies_fetched: RefCell<Vec<String>>,
}

impl Fake {
    pub fn deliver(&self, thread: &str, id: &str, mailbox: &str, body: &str) {
        let at = format!(
            "2026-10-01T09:{:02}:00Z",
            self.threads.borrow().values().map(Vec::len).sum::<usize>()
        );
        let mail = Mail {
            id: id.to_owned(),
            from: format!("{id}@x.test"),
            received_at: at,
            body: body.to_owned(),
        };
        self.threads
            .borrow_mut()
            .entry(thread.to_owned())
            .or_default()
            .push(Stored {
                id: id.to_owned(),
                mailbox: mailbox.to_owned(),
                mail,
            });
        self.touched.borrow_mut().insert(thread.to_owned());
    }

    pub fn thread_of(&self, thread: &str, count: usize, mailbox: &str) {
        for n in 0..count {
            self.deliver(thread, &format!("{thread}-{n}"), mailbox, "a short message");
        }
    }

    pub fn move_to(&self, id: &str, mailbox: &str) {
        for (thread, stored) in self.threads.borrow_mut().iter_mut() {
            if let Some(found) = stored.iter_mut().find(|stored| stored.id == id) {
                found.mailbox = mailbox.to_owned();
                self.touched.borrow_mut().insert(thread.clone());
            }
        }
    }

    pub fn destroy(&self, thread: &str) {
        self.threads.borrow_mut().remove(thread);
        self.touched.borrow_mut().insert(thread.to_owned());
    }
}

impl Mailbox for Fake {
    fn changes(&self, since: Option<&Cursors>) -> io::Result<Changes> {
        let threads = if since.is_none() && !self.adopted.replace(true) {
            self.touched.borrow_mut().clear();
            Vec::new()
        } else {
            std::mem::take(&mut *self.touched.borrow_mut())
                .into_iter()
                .collect()
        };
        Ok(Changes {
            threads,
            cursors: Cursors {
                email: "e".to_owned(),
                thread: "t".to_owned(),
            },
        })
    }

    fn folders(&self) -> io::Result<Vec<Folder>> {
        Ok([INBOX, JUNK, TRASH, "Promotions"]
            .into_iter()
            .map(|name| Folder {
                id: name.to_owned(),
                name: name.to_owned(),
                role: matches!(name, INBOX | JUNK | TRASH).then(|| name.to_owned()),
            })
            .collect())
    }

    fn members(&self, thread_id: &str) -> io::Result<Vec<Member>> {
        Ok(self
            .threads
            .borrow()
            .get(thread_id)
            .into_iter()
            .flatten()
            .map(|stored| Member {
                id: stored.id.clone(),
                mailbox_ids: BTreeSet::from([stored.mailbox.clone()]),
            })
            .collect())
    }

    fn mails(&self, ids: &[String]) -> io::Result<Vec<Mail>> {
        self.bodies_fetched.borrow_mut().extend(ids.iter().cloned());
        Ok(self
            .threads
            .borrow()
            .values()
            .flatten()
            .filter(|stored| ids.contains(&stored.id))
            .map(|stored| Mail {
                id: stored.mail.id.clone(),
                from: stored.mail.from.clone(),
                received_at: stored.mail.received_at.clone(),
                body: stored.mail.body.clone(),
            })
            .collect())
    }
}

/// A sentinel that keeps what is posted, as the real one does.
#[derive(Default)]
pub struct Sentinel {
    pub held: RefCell<BTreeMap<String, Upsert>>,
    pub posts: RefCell<Vec<(Vec<Upsert>, Vec<String>)>>,
    pub refuse_posts: Cell<bool>,
}

impl Sink for Sentinel {
    fn manifest(&self, _: i64) -> io::Result<BTreeMap<String, String>> {
        Ok(self
            .held
            .borrow()
            .iter()
            .map(|(thread, upsert)| (thread.clone(), upsert.input_hash.clone()))
            .collect())
    }

    fn post(&self, _: i64, upserts: &[Upsert], deletes: &[String]) -> io::Result<()> {
        if self.refuse_posts.get() {
            return Err(io::Error::other("the sentinel is down"));
        }
        let mut held = self.held.borrow_mut();
        for thread in deletes {
            held.remove(thread);
        }
        for upsert in upserts {
            held.insert(upsert.thread_id.clone(), upsert.clone());
        }
        self.posts
            .borrow_mut()
            .push((upserts.to_vec(), deletes.to_vec()));
        Ok(())
    }
}

pub enum Reply {
    Says(String),
    Unfinished(String),
    Down,
}

/// A model that answers every call the same way, until told otherwise.
pub struct Model {
    pub reply: RefCell<Reply>,
    pub calls: RefCell<Vec<(String, String)>>,
}

impl Model {
    pub fn says(text: &str) -> Self {
        Self {
            reply: RefCell::new(Reply::Says(text.to_owned())),
            calls: RefCell::default(),
        }
    }

    pub fn answer(&self, reply: Reply) {
        *self.reply.borrow_mut() = reply;
    }
}

impl Writer for Model {
    fn write(&self, system: &str, user: &str, _: u32) -> io::Result<Written> {
        self.calls
            .borrow_mut()
            .push((system.to_owned(), user.to_owned()));
        match &*self.reply.borrow() {
            Reply::Says(text) => Ok(Written {
                text: text.clone(),
                finished: true,
            }),
            Reply::Unfinished(text) => Ok(Written {
                text: text.clone(),
                finished: false,
            }),
            Reply::Down => Err(io::Error::new(io::ErrorKind::ConnectionRefused, "no route")),
        }
    }
}

pub fn settings() -> Settings {
    Settings {
        exclude_mailboxes: Vec::new(),
        min_messages: 3,
        long_body_chars: 3000,
        max_input_chars: 12_000,
        model: "m".to_owned(),
        max_tokens: 300,
        budget: 10,
        deadline: Duration::from_secs(300),
    }
}

pub struct Rig {
    pub dir: tempfile::TempDir,
    pub mailbox: Fake,
    pub sentinel: Sentinel,
    pub model: Model,
    pub settings: Settings,
}

impl Rig {
    pub fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            mailbox: Fake::default(),
            sentinel: Sentinel::default(),
            model: Model::says("They settled it."),
            settings: settings(),
        }
    }

    /// The first pass has already adopted the mailbox, so what is delivered next is news.
    pub fn adopted() -> Self {
        let rig = Self::new();
        rig.pass();
        rig
    }

    pub fn state(&self) -> PathBuf {
        self.dir.path().join("scribe.json")
    }

    pub fn pass(&self) -> Summary {
        self.try_pass().unwrap()
    }

    pub fn try_pass(&self) -> io::Result<Summary> {
        run(
            &self.state(),
            &self.mailbox,
            &self.sentinel,
            &self.model,
            &self.settings,
            NOW,
        )
    }

    pub fn asked(&self) -> usize {
        self.model.calls.borrow().len()
    }
}
