mod common;

use corvid_mirror::{audit, restore, sync};

use common::{Server, identities, recipient, scratch};

fn message(msgid: &str) -> String {
    format!("Message-Id: {msgid}\r\nSubject: hi\r\n\r\nbody")
}

#[test]
fn restore_imports_every_held_message_with_its_metadata() {
    let (_dir, root) = scratch();
    let source = Server::default();
    source.deliver_to("m0", &message("<m0@test>"), &["Inbox"]);
    source.set_keywords("m0", &["corvid-work"]);
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let report = restore(&root, &target, &identities()).unwrap();

    assert_eq!(report.imported, 1);
    assert!(report.missing.is_empty());
    let imported = target.imported();
    assert_eq!(imported.len(), 1);
    assert!(imported[0].keywords.contains("corvid-work"));
    assert_eq!(imported[0].received_at, "2026-09-07T00:00:00Z");
    let inbox_id = target.mailbox_id("Inbox").unwrap();
    assert!(imported[0].mailbox_ids.contains(&inbox_id));
}

#[test]
fn restore_twice_creates_no_duplicates() {
    let (_dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    restore(&root, &target, &identities()).unwrap();
    let report = restore(&root, &target, &identities()).unwrap();

    assert_eq!(report.already_present, 1);
    assert_eq!(report.imported, 0);
    assert_eq!(
        target.imported().len(),
        1,
        "no duplicate landed on the target"
    );
}

#[test]
fn restore_falls_back_to_the_default_mailbox_when_none_is_recorded() {
    let (_dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    restore(&root, &target, &identities()).unwrap();

    let restored_id = target.mailbox_id("Restored").unwrap();
    assert!(target.imported()[0].mailbox_ids.contains(&restored_id));
}

#[test]
fn restore_counts_but_still_imports_a_message_with_no_message_id() {
    let (_dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", "no headers here, just a body");
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let report = restore(&root, &target, &identities()).unwrap();

    assert_eq!(report.unidentifiable, 1);
    assert_eq!(report.imported, 1);
}

#[test]
fn restore_skips_a_vanished_message() {
    let (_dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    source.deliver("m1", &message("<m1@test>"));
    sync(&root, &source, &recipient()).unwrap();
    source.destroy("m1");
    audit(&root, &source).unwrap();

    let target = Server::default();
    let report = restore(&root, &target, &identities()).unwrap();

    assert_eq!(report.imported, 1, "the vanished message is not restored");
}
