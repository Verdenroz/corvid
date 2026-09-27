mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use corvid_mirror::{Archive, encrypt_archive, verify};
use serde::Serialize;
use sha2::{Digest, Sha256};

use common::{Server, files, identities, recipient, scratch};

#[derive(Serialize)]
struct LegacyHeld {
    digest: String,
    received_at: String,
    size: usize,
    keywords: BTreeSet<String>,
}

#[derive(Default, Serialize)]
struct LegacyLedger {
    cursor: Option<String>,
    held: BTreeMap<String, LegacyHeld>,
    vanished: BTreeSet<String>,
}

/// Writes an archive the way it looked before encryption existed: plaintext blobs, `ledger.json`.
fn plaintext_archive(root: &Path, messages: &[(&str, &str)]) {
    std::fs::create_dir_all(root.join("blobs")).unwrap();
    let mut held = BTreeMap::new();
    for (id, body) in messages {
        let digest = hex::encode(Sha256::digest(body.as_bytes()));
        let path = root.join("blobs").join(&digest[..2]).join(&digest);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, body.as_bytes()).unwrap();
        held.insert(
            (*id).to_owned(),
            LegacyHeld {
                digest,
                received_at: "2026-09-07T00:00:00Z".to_owned(),
                size: body.len(),
                keywords: BTreeSet::new(),
            },
        );
    }
    let ledger = LegacyLedger {
        cursor: Some("s1".to_owned()),
        held,
        vanished: BTreeSet::new(),
    };
    corvid_store::save(&root.join("ledger.json"), 1, &ledger).unwrap();
}

#[test]
fn encrypt_archive_migrates_bodies_and_ledger() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "the body")]);

    let migrated = encrypt_archive(&root, &Server::default(), &recipient()).unwrap();

    assert_eq!(migrated.total, 1);
    let archive = Archive::open(&root).unwrap();
    assert_eq!(
        archive.read_plaintext("m0", &identities()).unwrap(),
        b"the body"
    );
    assert_eq!(archive.meta(&identities()).unwrap()["m0"].size, 8);
    assert!(verify(&root).unwrap().is_empty());
}

#[test]
fn encrypt_archive_fetches_current_mailboxes_and_keywords_from_the_server() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "the body")]);
    let server = Server::default();
    server.deliver_to("m0", "the body", &["Inbox"]);
    server.set_keywords("m0", &["corvid-work"]);

    encrypt_archive(&root, &server, &recipient()).unwrap();

    let archive = Archive::open(&root).unwrap();
    let meta = archive.meta(&identities()).unwrap();
    assert!(meta["m0"].mailboxes.contains("Inbox"));
    assert!(meta["m0"].keywords.contains("corvid-work"));
}

#[test]
fn encrypt_archive_counts_ids_the_server_no_longer_has() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "the body")]);

    let migrated = encrypt_archive(&root, &Server::default(), &recipient()).unwrap();

    assert_eq!(migrated.without_mailboxes, 1);
    let archive = Archive::open(&root).unwrap();
    assert!(
        archive.meta(&identities()).unwrap()["m0"]
            .mailboxes
            .is_empty()
    );
}

#[test]
fn encrypt_archive_leaves_no_plaintext_anywhere_on_disk() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "marker-c0ffee in the body")]);

    encrypt_archive(&root, &Server::default(), &recipient()).unwrap();

    assert!(!root.join("blobs.plaintext").exists());
    assert!(!root.join("ledger.json").exists());
    for file in files(&root) {
        let bytes = std::fs::read(&file).unwrap();
        assert!(!bytes.windows(6).any(|w| w == b"marker"), "{file:?}");
    }
}

#[test]
fn encrypt_archive_run_twice_is_refused() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "body")]);
    encrypt_archive(&root, &Server::default(), &recipient()).unwrap();

    let error = encrypt_archive(&root, &Server::default(), &recipient()).unwrap_err();

    assert!(error.to_string().contains("already encrypted"));
}

#[test]
fn encrypt_archive_on_an_empty_directory_is_refused() {
    let (_dir, root) = scratch();

    let error = encrypt_archive(&root, &Server::default(), &recipient()).unwrap_err();

    assert!(error.to_string().contains("no archive to migrate"));
}

// A crash between committing the index and removing ledger.json: a rerun must finish that
// cleanup rather than refuse or re-encrypt already-encrypted content.
#[test]
fn a_leftover_ledger_after_a_committed_migration_is_cleaned_up_on_rerun() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "body")]);
    encrypt_archive(&root, &Server::default(), &recipient()).unwrap();
    plaintext_archive(&root, &[("m0", "body")]);

    encrypt_archive(&root, &Server::default(), &recipient()).unwrap();

    assert!(!root.join("ledger.json").exists());
    Archive::open(&root).unwrap();
}

// A crash after some blobs were encrypted but before the index committed: a rerun re-encrypts
// everything fresh, and the earlier attempt's blobs are simply never referenced.
#[test]
fn orphan_ciphertext_blobs_from_an_earlier_attempt_are_harmless() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "body")]);
    std::fs::create_dir_all(root.join("blobs").join("ab")).unwrap();
    std::fs::write(root.join("blobs").join("ab").join("ab1234"), b"orphan").unwrap();

    let migrated = encrypt_archive(&root, &Server::default(), &recipient()).unwrap();

    assert_eq!(migrated.total, 1);
    assert!(verify(&root).unwrap().is_empty());
}
