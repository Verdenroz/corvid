#![allow(dead_code)]

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::{Path, PathBuf};

use corvid_mirror::{Changes, Identities, Listing, Record, Source};

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
