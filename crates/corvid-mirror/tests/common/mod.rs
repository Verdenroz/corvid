#![allow(dead_code)]

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::{Path, PathBuf};

use corvid_mirror::{Changes, Identities, Listing, Record, Source, Target};

/// Published test vectors from the age crate, not secrets used anywhere real.
pub const TEST_SK: &str =
    "AGE-SECRET-KEY-1GQ9778VQXMMJVE8SK7J6VT8UJ4HDQAJUVSFCWCM02D8GEWQ72PVQ2Y5J33";
pub const TEST_PK: &str = "age1t7rxyev2z3rw82stdlrrepyc39nvn86l5078zqkf5uasdy86jp6svpy7pa";

pub fn recipient() -> age::x25519::Recipient {
    TEST_PK.parse().unwrap()
}

pub fn identities() -> Identities {
    vec![Box::new(TEST_SK.parse::<age::x25519::Identity>().unwrap())]
}

/// Every file under a directory, recursively: walks the blob shards, or a whole archive root.
pub fn files(dir: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            found.extend(files(&path));
        } else {
            found.push(path);
        }
    }
    found
}

/// The one blob a single-message archive holds, for tests that tamper with its bytes.
pub fn only_blob(root: &Path) -> PathBuf {
    let mut blobs = files(&root.join("blobs"));
    assert_eq!(blobs.len(), 1, "expected exactly one stored blob");
    blobs.remove(0)
}

pub fn scratch() -> (tempfile::TempDir, PathBuf) {
    let directory = tempfile::tempdir().unwrap();
    let root = directory.path().join("archive");
    (directory, root)
}

pub struct Imported {
    pub body: Vec<u8>,
    pub mailbox_ids: BTreeSet<String>,
    pub received_at: String,
    pub keywords: BTreeSet<String>,
}

#[derive(Default)]
pub struct Server {
    bodies: RefCell<BTreeMap<String, Vec<u8>>>,
    records: RefCell<BTreeMap<String, Record>>,
    order: RefCell<Vec<String>>,
    touched: RefCell<Vec<String>>,
    pub short_listing: Cell<bool>,
    pub short_listing_once: Cell<bool>,
    pub lists_a_since_deleted_id: Cell<bool>,
    pub forget_records: Cell<bool>,
    pub truncate_bodies: Cell<bool>,
    pub refuse_blobs: Cell<bool>,
    url: RefCell<String>,
    user: RefCell<String>,
    account_id: RefCell<String>,
    uploads: RefCell<BTreeMap<String, Vec<u8>>>,
    mailboxes: RefCell<BTreeMap<String, String>>,
    imported: RefCell<BTreeMap<String, Imported>>,
    next_email_id: Cell<u32>,
    import_calls: Cell<usize>,
    /// 0-indexed call number to fail, simulating a crash partway through a restore.
    pub fail_import_at_call: Cell<Option<usize>>,
}

impl Server {
    pub fn deliver(&self, id: &str, body: &str) {
        self.deliver_to(id, body, &[]);
    }

    pub fn deliver_to(&self, id: &str, body: &str, mailboxes: &[&str]) {
        self.records.borrow_mut().insert(
            id.to_owned(),
            Record {
                id: id.to_owned(),
                blob_id: format!("b-{id}"),
                received_at: "2026-09-07T00:00:00Z".to_owned(),
                size: body.len(),
                keywords: BTreeSet::new(),
                mailboxes: mailboxes.iter().map(|m| (*m).to_owned()).collect(),
            },
        );
        self.bodies
            .borrow_mut()
            .insert(format!("b-{id}"), body.as_bytes().to_vec());
        self.order.borrow_mut().push(id.to_owned());
    }

    pub fn destroy(&self, id: &str) {
        self.records.borrow_mut().remove(id);
        self.order.borrow_mut().retain(|held| held != id);
    }

    pub fn set_keywords(&self, id: &str, keywords: &[&str]) {
        let mut records = self.records.borrow_mut();
        let record = records.get_mut(id).unwrap();
        record.keywords = keywords.iter().map(|k| (*k).to_owned()).collect();
        self.touched.borrow_mut().push(id.to_owned());
    }

    pub fn imported(&self) -> std::cell::Ref<'_, BTreeMap<String, Imported>> {
        self.imported.borrow()
    }

    pub fn mailbox_id(&self, path: &str) -> Option<String> {
        self.mailboxes.borrow().get(path).cloned()
    }

    pub fn set_identity(&self, url: &str, user: &str, account_id: &str) {
        *self.url.borrow_mut() = url.to_owned();
        *self.user.borrow_mut() = user.to_owned();
        *self.account_id.borrow_mut() = account_id.to_owned();
    }

    /// Simulates a message imported earlier being deleted on the target since.
    pub fn delete_target_email(&self, target_id: &str) {
        self.imported.borrow_mut().remove(target_id);
    }
}

impl Source for Server {
    fn changes(&self, since: Option<&str>) -> io::Result<Changes> {
        let order = self.order.borrow();
        let seen: usize = since
            .and_then(|s| s.trim_start_matches('s').parse().ok())
            .unwrap_or(0);
        Ok(Changes {
            created: order.get(seen..).unwrap_or_default().to_vec(),
            updated: self.touched.borrow_mut().drain(..).collect(),
            new_state: format!("s{}", order.len()),
        })
    }

    fn records(&self, ids: &[String]) -> io::Result<Vec<Record>> {
        if self.forget_records.get() {
            return Ok(Vec::new());
        }
        let records = self.records.borrow();
        Ok(ids
            .iter()
            .filter_map(|id| records.get(id).cloned())
            .collect())
    }

    fn blob(&self, blob_id: &str) -> io::Result<Vec<u8>> {
        if self.refuse_blobs.get() {
            return Err(io::Error::other("the server refused"));
        }
        let body = self
            .bodies
            .borrow()
            .get(blob_id)
            .cloned()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no such blob"))?;
        Ok(if self.truncate_bodies.get() {
            body[..body.len() / 2].to_vec()
        } else {
            body
        })
    }

    fn state(&self) -> io::Result<String> {
        Ok(format!("s{}", self.order.borrow().len()))
    }

    fn all_ids(&self) -> io::Result<Listing> {
        let ids: Vec<String> = self.records.borrow().keys().cloned().collect();
        let total = ids.len();
        let short = self.short_listing.get() || self.short_listing_once.replace(false);
        let mut ids = if short {
            ids[..ids.len() / 2].to_vec()
        } else {
            ids
        };
        if self.lists_a_since_deleted_id.get() {
            ids.push("read-before-it-was-deleted".to_owned());
        }
        Ok(Listing { ids, total })
    }
}

impl Target for Server {
    fn url(&self) -> String {
        self.url.borrow().clone()
    }

    fn user(&self) -> String {
        self.user.borrow().clone()
    }

    fn account_id(&self) -> String {
        self.account_id.borrow().clone()
    }

    fn message_ids(&self) -> io::Result<BTreeSet<String>> {
        Ok(self
            .imported
            .borrow()
            .values()
            .filter_map(|entry| message_id(&entry.body))
            .collect())
    }

    fn ensure_mailbox(&self, path: &str) -> io::Result<String> {
        let mut mailboxes = self.mailboxes.borrow_mut();
        if let Some(id) = mailboxes.get(path) {
            return Ok(id.clone());
        }
        let id = format!("mb{}", mailboxes.len());
        mailboxes.insert(path.to_owned(), id.clone());
        Ok(id)
    }

    fn upload(&self, bytes: &[u8]) -> io::Result<String> {
        let mut uploads = self.uploads.borrow_mut();
        let id = format!("blob{}", uploads.len());
        uploads.insert(id.clone(), bytes.to_vec());
        Ok(id)
    }

    fn import(
        &self,
        blob_id: &str,
        mailbox_ids: &BTreeSet<String>,
        received_at: &str,
        keywords: &BTreeSet<String>,
    ) -> io::Result<String> {
        let call = self.import_calls.get();
        self.import_calls.set(call + 1);
        if self.fail_import_at_call.get() == Some(call) {
            return Err(io::Error::other("simulated crash"));
        }
        let body = self
            .uploads
            .borrow()
            .get(blob_id)
            .cloned()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no such blob"))?;
        let id = format!("email{}", self.next_email_id.get());
        self.next_email_id.set(self.next_email_id.get() + 1);
        self.imported.borrow_mut().insert(
            id.clone(),
            Imported {
                body,
                mailbox_ids: mailbox_ids.clone(),
                received_at: received_at.to_owned(),
                keywords: keywords.clone(),
            },
        );
        Ok(id)
    }

    fn existing(&self, ids: &[String]) -> io::Result<BTreeSet<String>> {
        let imported = self.imported.borrow();
        Ok(ids
            .iter()
            .filter(|id| imported.contains_key(*id))
            .cloned()
            .collect())
    }
}

fn message_id(body: &[u8]) -> Option<String> {
    let text = String::from_utf8_lossy(body);
    text.lines()
        .take_while(|line| !line.is_empty())
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.trim()
                .eq_ignore_ascii_case("message-id")
                .then(|| value.trim().to_owned())
        })
}
