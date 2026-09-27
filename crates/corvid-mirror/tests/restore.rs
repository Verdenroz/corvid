mod common;

use corvid_mirror::{audit, restore, sync};

use common::{Server, identities, recipient, scratch};

fn message(msgid: &str) -> String {
    format!("Message-Id: {msgid}\r\nSubject: hi\r\n\r\nbody")
}

#[test]
fn restore_imports_every_held_message_with_its_metadata() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver_to("m0", &message("<m0@test>"), &["Inbox"]);
    source.set_keywords("m0", &["corvid-work"]);
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    let report = restore(&root, &ledger, false, &target, &identities()).unwrap();

    assert_eq!(report.imported, 1);
    assert!(report.missing.is_empty());
    let imported = target.imported();
    assert_eq!(imported.len(), 1);
    let record = imported.values().next().unwrap();
    assert!(record.keywords.contains("corvid-work"));
    assert_eq!(record.received_at, "2026-09-07T00:00:00Z");
    let inbox_id = target.mailbox_id("Inbox").unwrap();
    assert!(record.mailbox_ids.contains(&inbox_id));
}

#[test]
fn restore_twice_creates_no_duplicates() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    restore(&root, &ledger, false, &target, &identities()).unwrap();
    let report = restore(&root, &ledger, true, &target, &identities()).unwrap();

    assert_eq!(report.already_present, 1);
    assert_eq!(report.imported, 0);
    assert_eq!(
        target.imported().len(),
        1,
        "no duplicate landed on the target"
    );
}

#[test]
fn an_existing_ledger_without_resume_is_refused() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    restore(&root, &ledger, false, &target, &identities()).unwrap();

    let error = restore(&root, &ledger, false, &target, &identities()).unwrap_err();

    assert!(error.to_string().contains("resume"));
}

#[test]
fn two_messages_sharing_a_message_id_both_land() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<shared@test>"));
    source.deliver("m1", &message("<shared@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    let report = restore(&root, &ledger, false, &target, &identities()).unwrap();

    assert_eq!(report.imported, 2, "a shared Message-Id is not a duplicate");
    assert!(report.missing.is_empty());
    assert_eq!(target.imported().len(), 2);
}

#[test]
fn a_crash_after_some_imports_is_resumed_by_a_rerun() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    source.deliver("m1", &message("<m1@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    target.fail_import_at_call.set(Some(1));
    assert!(restore(&root, &ledger, false, &target, &identities()).is_err());
    assert_eq!(
        target.imported().len(),
        1,
        "the import before the crash landed and was recorded"
    );

    target.fail_import_at_call.set(None);
    let report = restore(&root, &ledger, true, &target, &identities()).unwrap();

    assert_eq!(
        report.imported, 1,
        "only the message the crash lost is retried"
    );
    assert_eq!(target.imported().len(), 2);
}

#[test]
fn a_target_email_deleted_after_import_fails_the_audit_by_archive_id() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    restore(&root, &ledger, false, &target, &identities()).unwrap();
    let target_id = target.imported().keys().next().unwrap().clone();
    target.delete_target_email(&target_id);

    let report = restore(&root, &ledger, true, &target, &identities()).unwrap();

    assert_eq!(report.missing, ["m0"]);
}

#[test]
fn restore_refuses_a_resumed_ledger_built_against_a_different_target() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let first = Server::default();
    first.set_identity("https://a.test", "alice", "acct-a");
    let ledger = dir.path().join("ledger.json");
    restore(&root, &ledger, false, &first, &identities()).unwrap();

    let second = Server::default();
    second.set_identity("https://b.test", "bob", "acct-b");
    let error = restore(&root, &ledger, true, &second, &identities()).unwrap_err();

    assert!(error.to_string().contains("a.test"));
}

#[test]
fn restore_falls_back_to_the_default_mailbox_when_none_is_recorded() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    restore(&root, &ledger, false, &target, &identities()).unwrap();

    let restored_id = target.mailbox_id("Restored").unwrap();
    let imported = target.imported();
    let record = imported.values().next().unwrap();
    assert!(record.mailbox_ids.contains(&restored_id));
}

#[test]
fn restore_audits_a_message_with_no_message_id_like_any_other() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", "no headers here, just a body");
    sync(&root, &source, &recipient()).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    let report = restore(&root, &ledger, false, &target, &identities()).unwrap();

    assert_eq!(report.unidentifiable, 1);
    assert_eq!(report.imported, 1);
    assert!(
        report.missing.is_empty(),
        "covered by the audit despite having no Message-Id"
    );
}

#[test]
fn restore_skips_a_vanished_message() {
    let (dir, root) = scratch();
    let source = Server::default();
    source.deliver("m0", &message("<m0@test>"));
    source.deliver("m1", &message("<m1@test>"));
    sync(&root, &source, &recipient()).unwrap();
    source.destroy("m1");
    audit(&root, &source).unwrap();

    let target = Server::default();
    let ledger = dir.path().join("ledger.json");
    let report = restore(&root, &ledger, false, &target, &identities()).unwrap();

    assert_eq!(report.imported, 1, "the vanished message is not restored");
}
