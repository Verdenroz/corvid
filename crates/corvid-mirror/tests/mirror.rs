mod common;

use corvid_mirror::{Archive, audit, sync, verify};

use common::{Server, identities, only_blob, recipient, scratch};

#[test]
fn a_first_run_takes_the_whole_mailbox() {
    let (_dir, root) = scratch();
    let server = Server::default();
    for n in 0..5 {
        server.deliver(&format!("m{n}"), &format!("body {n}"));
    }

    let summary = sync(&root, &server, &recipient()).unwrap();

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
    sync(&root, &server, &recipient()).unwrap();

    server.deliver("m1", "second");
    let summary = sync(&root, &server, &recipient()).unwrap();

    assert_eq!(summary.fetched, 1);
    assert_eq!(summary.already_held, 0);
}

#[test]
fn the_stored_body_is_the_body_that_was_sent() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "the original bytes");
    sync(&root, &server, &recipient()).unwrap();

    let archive = Archive::open(&root).unwrap();

    assert_eq!(
        archive.read_plaintext("m0", &common::identities()).unwrap(),
        b"the original bytes"
    );
}

#[test]
fn a_message_deleted_on_the_server_is_kept_and_noted() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "kept");
    server.deliver("m1", "removed later");
    sync(&root, &server, &recipient()).unwrap();
    server.destroy("m1");

    let report = audit(&root, &server).unwrap();

    assert!(report.missing.is_empty());
    assert_eq!(report.vanished, ["m1"], "deletion is recorded, not obeyed");
    assert_eq!(
        Archive::open(&root)
            .unwrap()
            .read_plaintext("m1", &common::identities())
            .unwrap(),
        b"removed later"
    );
}

// Ids are not reused, so an id can vanish at most once.
#[test]
fn a_deletion_is_noted_once_rather_than_every_night() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "removed later");
    sync(&root, &server, &recipient()).unwrap();
    server.destroy("m0");
    audit(&root, &server).unwrap();

    assert!(audit(&root, &server).unwrap().vanished.is_empty());
}

#[test]
fn a_message_the_archive_never_got_is_reported() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "arrived");
    sync(&root, &server, &recipient()).unwrap();
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
    sync(&root, &server, &recipient()).unwrap();
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

    assert!(sync(&root, &server, &recipient()).is_err());

    server.refuse_blobs.set(false);
    assert_eq!(
        sync(&root, &server, &recipient()).unwrap().fetched,
        1,
        "retried, not skipped"
    );
}

#[test]
fn a_keyword_change_does_not_refetch_the_body() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server, &recipient()).unwrap();
    server.set_keywords("m0", &["corvid-work", "$seen"]);

    let summary = sync(&root, &server, &recipient()).unwrap();

    assert_eq!(summary.fetched, 0, "the body is immutable");
    assert_eq!(summary.already_held, 1);
    let archive = Archive::open(&root).unwrap();
    let meta = archive.meta(&identities()).unwrap();
    assert!(meta["m0"].keywords.contains("corvid-work"));
}

#[test]
fn verification_needs_no_server() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server, &recipient()).unwrap();

    assert!(verify(&root).unwrap().is_empty());
}

#[test]
fn a_damaged_body_is_reported_by_verification() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server, &recipient()).unwrap();
    std::fs::write(only_blob(&root), b"tampered").unwrap();

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
    sync(&root, &server, &recipient()).unwrap();
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

    let error = sync(&root, &server, &recipient()).unwrap_err();

    assert!(error.to_string().contains("octets and"));
}

#[test]
fn a_listing_holding_a_since_deleted_id_is_not_refused() {
    let (_dir, root) = scratch();
    let server = Server::default();
    for n in 0..4 {
        server.deliver(&format!("m{n}"), "body");
    }
    sync(&root, &server, &recipient()).unwrap();
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

    let summary = sync(&root, &server, &recipient()).unwrap();

    assert_eq!(summary.fetched, 0);
    assert_eq!(
        summary.unavailable.len(),
        2,
        "silence and success must not look the same"
    );
}
