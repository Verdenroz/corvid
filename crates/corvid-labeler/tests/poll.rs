mod common;

use std::path::Path;

use common::{Fake, scratch, taught};
use corvid_labeler::poll;

#[test]
fn a_first_run_adopts_the_cursor_and_labels_nothing() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    mailbox.deliver("old", "billing@acme.test", &["receipts"], 0.0);

    let summary = poll(&path, &mailbox).unwrap();

    assert_eq!(
        summary.labelled, 0,
        "historical mail belongs to the cold-start pass"
    );
}

#[test]
fn labels_a_message_from_a_sender_it_has_learned_about() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);

    mailbox.deliver("new", "billing@acme.test", &[], 12.0);
    let summary = poll(&path, &mailbox).unwrap();

    assert_eq!(summary.written, 1);
    assert!(mailbox.keywords_of("new").contains("corvid-receipts"));
}

#[test]
fn a_refused_write_stops_the_poll_before_the_cursor_moves() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    mailbox.deliver("new", "billing@acme.test", &[], 12.0);
    mailbox.refuse_writes.set(true);

    assert!(poll(&path, &mailbox).is_err());

    mailbox.refuse_writes.set(false);
    assert_eq!(
        poll(&path, &mailbox).unwrap().written,
        1,
        "retried, not skipped"
    );
}

// The server accepts a truncated cursor and replays; the signal is a counted id reported as
// created, not how many arrived.
#[test]
fn a_message_reported_created_twice_stops_the_run() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);

    mailbox.replay.set(true);
    let error = poll(&path, &mailbox).unwrap_err();

    assert!(error.to_string().contains("has been seen before"));
}

#[test]
fn a_large_genuinely_new_arrival_is_not_a_replay() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    poll(&path, &mailbox).unwrap();
    for n in 0..5000 {
        mailbox.deliver(&format!("bulk{n}"), "list@acme.test", &[], 0.0);
    }

    assert!(poll(&path, &mailbox).is_ok());
}

#[test]
fn a_message_with_no_usable_sender_is_counted_not_labelled() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    poll(&path, &mailbox).unwrap();
    mailbox.deliver("m0", "not-an-address", &[], 0.0);

    let summary = poll(&path, &mailbox).unwrap();

    assert_eq!(summary.unkeyed, 1);
    assert_eq!(summary.labelled, 0);
}

#[test]
fn a_second_poll_over_the_same_message_writes_nothing_new() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    mailbox.deliver("new", "billing@acme.test", &[], 12.0);
    poll(&path, &mailbox).unwrap();
    let before = mailbox.written.borrow().len();

    poll(&path, &mailbox).unwrap();

    assert_eq!(
        mailbox.written.borrow().len(),
        before,
        "our own write must not re-trigger one"
    );
}

#[test]
fn a_label_the_prior_no_longer_applies_is_removed() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    mailbox.deliver("target", "billing@acme.test", &[], 12.0);
    poll(&path, &mailbox).unwrap();
    assert!(mailbox.keywords_of("target").contains("corvid-receipts"));

    for day in 12..24 {
        mailbox.deliver(
            &format!("w{day}"),
            "billing@acme.test",
            &["work"],
            day as f64,
        );
    }
    poll(&path, &mailbox).unwrap();
    mailbox.touch("target");
    poll(&path, &mailbox).unwrap();

    assert!(
        !mailbox.keywords_of("target").contains("corvid-receipts"),
        "a stale label must be taken off, not left behind"
    );
}

// Uses a state file a real run left behind; building it here would test the format against
// this code's own reading of it.
#[test]
fn a_real_replayed_cursor_stops_the_run() {
    let (directory, path) = scratch();
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../corvid-ingest/tests/fixtures/replayed-cursor.state");
    std::fs::copy(&fixture, &path).unwrap();
    let mailbox = Fake::default();
    for id in ["nqaaaadm", "nuaaaadn"] {
        mailbox.deliver(id, "someone@norm1.example", &[], 0.0);
    }

    let error = poll(&path, &mailbox).unwrap_err();

    assert!(
        error.to_string().contains("nqaaaadm"),
        "the offending id is named"
    );
    drop(directory);
}
