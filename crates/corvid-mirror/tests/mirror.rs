use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::PathBuf;

use corvid_mirror::{Archive, Changes, Listing, Record, Source, audit, sync, verify};

#[derive(Default)]
struct Server {
    bodies: RefCell<BTreeMap<String, Vec<u8>>>,
    records: RefCell<BTreeMap<String, Record>>,
    order: RefCell<Vec<String>>,
    touched: RefCell<Vec<String>>,
    short_listing: Cell<bool>,
    short_listing_once: Cell<bool>,
    lists_a_since_deleted_id: Cell<bool>,
    forget_records: Cell<bool>,
    truncate_bodies: Cell<bool>,
    refuse_blobs: Cell<bool>,
}

impl Server {
    fn deliver(&self, id: &str, body: &str) {
        self.records.borrow_mut().insert(
            id.to_owned(),
            Record {
                id: id.to_owned(),
                blob_id: format!("b-{id}"),
                received_at: "2026-09-07T00:00:00Z".to_owned(),
                size: body.len(),
                keywords: BTreeSet::new(),
            },
        );
        self.bodies
            .borrow_mut()
            .insert(format!("b-{id}"), body.as_bytes().to_vec());
        self.order.borrow_mut().push(id.to_owned());
    }

    fn destroy(&self, id: &str) {
        self.records.borrow_mut().remove(id);
        self.order.borrow_mut().retain(|held| held != id);
    }

    fn set_keywords(&self, id: &str, keywords: &[&str]) {
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

fn scratch() -> (tempfile::TempDir, PathBuf) {
    let directory = tempfile::tempdir().unwrap();
    let root = directory.path().join("archive");
    (directory, root)
}

#[test]
fn a_first_run_takes_the_whole_mailbox() {
    let (_dir, root) = scratch();
    let server = Server::default();
    for n in 0..5 {
        server.deliver(&format!("m{n}"), &format!("body {n}"));
    }

    let summary = sync(&root, &server).unwrap();

    assert_eq!(
        summary.fetched, 5,
        "an archive missing old mail is not an archive"
    );
    assert_eq!(Archive::open(&root).unwrap().len(), 5);
}

#[test]
fn a_later_run_takes_only_what_is_new() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "first");
    sync(&root, &server).unwrap();

    server.deliver("m1", "second");
    let summary = sync(&root, &server).unwrap();

    assert_eq!(summary.fetched, 1);
    assert_eq!(summary.already_held, 0);
}

#[test]
fn the_stored_body_is_the_body_that_was_sent() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "the original bytes");
    sync(&root, &server).unwrap();

    let archive = Archive::open(&root).unwrap();

    assert_eq!(archive.read("m0").unwrap(), b"the original bytes");
}

#[test]
fn a_message_deleted_on_the_server_is_kept_and_noted() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "kept");
    server.deliver("m1", "removed later");
    sync(&root, &server).unwrap();
    server.destroy("m1");

    let report = audit(&root, &server).unwrap();

    assert!(report.missing.is_empty());
    assert_eq!(report.vanished, ["m1"], "deletion is recorded, not obeyed");
    assert_eq!(
        Archive::open(&root).unwrap().read("m1").unwrap(),
        b"removed later"
    );
}

// Ids are not reused, so an id can vanish at most once.
#[test]
fn a_deletion_is_noted_once_rather_than_every_night() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "removed later");
    sync(&root, &server).unwrap();
    server.destroy("m0");
    audit(&root, &server).unwrap();

    assert!(audit(&root, &server).unwrap().vanished.is_empty());
}

#[test]
fn a_message_the_archive_never_got_is_reported() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "arrived");
    sync(&root, &server).unwrap();
    server.deliver("m1", "never fetched");

    let report = audit(&root, &server).unwrap();

    assert_eq!(report.missing, ["m1"]);
}

#[test]
fn a_listing_shorter_than_the_servers_own_count_is_refused() {
    let (_dir, root) = scratch();
    let server = Server::default();
    for n in 0..8 {
        server.deliver(&format!("m{n}"), "body");
    }
    sync(&root, &server).unwrap();
    server.short_listing.set(true);

    let error = audit(&root, &server).unwrap_err();

    assert!(error.to_string().contains("auditing a prefix"));
}

#[test]
fn a_refused_body_stops_the_run_before_the_cursor_moves() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    server.refuse_blobs.set(true);

    assert!(sync(&root, &server).is_err());

    server.refuse_blobs.set(false);
    assert_eq!(
        sync(&root, &server).unwrap().fetched,
        1,
        "retried, not skipped"
    );
}

#[test]
fn a_keyword_change_does_not_refetch_the_body() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server).unwrap();
    server.set_keywords("m0", &["corvid-work", "$seen"]);

    let summary = sync(&root, &server).unwrap();

    assert_eq!(summary.fetched, 0, "the body is immutable");
    assert_eq!(summary.already_held, 1);
    let archive = Archive::open(&root).unwrap();
    assert!(archive.held("m0").unwrap().keywords.contains("corvid-work"));
}

#[test]
fn verification_needs_no_server() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server).unwrap();

    assert!(verify(&root).unwrap().is_empty());
}

#[test]
fn a_damaged_body_is_reported_by_verification() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server).unwrap();
    let digest = Archive::open(&root)
        .unwrap()
        .held("m0")
        .unwrap()
        .digest
        .clone();
    let blob = root.join("blobs").join(&digest[..2]).join(&digest);
    std::fs::write(&blob, b"tampered").unwrap();

    assert_eq!(verify(&root).unwrap(), ["m0"]);
}

// A deletion mid-listing leaves it one short once; only a capped response stays short.
#[test]
fn a_listing_short_only_once_is_retried_rather_than_refused() {
    let (_dir, root) = scratch();
    let server = Server::default();
    for n in 0..8 {
        server.deliver(&format!("m{n}"), "body");
    }
    sync(&root, &server).unwrap();
    server.short_listing_once.set(true);

    let report = audit(&root, &server).unwrap();

    assert!(
        report.missing.is_empty(),
        "a mailbox settling is not a missing message"
    );
}

#[test]
fn a_body_that_does_not_match_the_servers_own_size_stops_the_run() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "the whole body");
    server.truncate_bodies.set(true);

    let error = sync(&root, &server).unwrap_err();

    assert!(error.to_string().contains("octets and"));
}

#[test]
fn a_listing_holding_a_since_deleted_id_is_not_refused() {
    let (_dir, root) = scratch();
    let server = Server::default();
    for n in 0..4 {
        server.deliver(&format!("m{n}"), "body");
    }
    sync(&root, &server).unwrap();
    server.lists_a_since_deleted_id.set(true);

    let report = audit(&root, &server).unwrap();

    assert_eq!(
        report.missing,
        ["read-before-it-was-deleted"],
        "an id the server listed is reported, not silently dropped"
    );
}

#[test]
fn a_listed_message_that_cannot_be_fetched_is_named() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    server.deliver("m1", "vanishes before the fetch");
    server.forget_records.set(true);

    let summary = sync(&root, &server).unwrap();

    assert_eq!(summary.fetched, 0);
    assert_eq!(
        summary.unavailable.len(),
        2,
        "silence and success must not look the same"
    );
}
