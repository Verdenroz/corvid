mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use corvid_mirror::{Archive, encrypt_archive, verify};
use serde::Serialize;
use sha2::{Digest, Sha256};

use common::{files, identities, recipient, scratch};

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

    let migrated = encrypt_archive(&root, &recipient()).unwrap();

    assert_eq!(migrated, 1);
    let archive = Archive::open(&root).unwrap();
    assert_eq!(
        archive.read_plaintext("m0", &identities()).unwrap(),
        b"the body"
    );
    assert_eq!(archive.meta(&identities()).unwrap()["m0"].size, 8);
    assert!(verify(&root).unwrap().is_empty());
}

#[test]
fn encrypt_archive_leaves_no_plaintext_or_ledger_on_disk() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "marker-c0ffee in the body")]);

    encrypt_archive(&root, &recipient()).unwrap();

    assert!(!root.join("blobs.plaintext").exists());
    assert!(!root.join("ledger.json").exists());
    for blob in files(&root.join("blobs")) {
        let bytes = std::fs::read(&blob).unwrap();
        assert!(!bytes.windows(6).any(|w| w == b"marker"), "{blob:?}");
    }
}

#[test]
fn encrypt_archive_run_twice_is_refused() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "body")]);
    encrypt_archive(&root, &recipient()).unwrap();

    let error = encrypt_archive(&root, &recipient()).unwrap_err();

    assert!(error.to_string().contains("already encrypted"));
}

#[test]
fn encrypt_archive_on_an_empty_directory_is_refused() {
    let (_dir, root) = scratch();

    let error = encrypt_archive(&root, &recipient()).unwrap_err();

    assert!(error.to_string().contains("no archive to migrate"));
}

// A committed migration that crashed before removing ledger.json: the index is already
// current, so a second run must not re-migrate, and Archive::open must keep refusing until
// the leftover file is removed by hand.
#[test]
fn a_leftover_ledger_after_a_committed_migration_is_not_re_migrated() {
    let (_dir, root) = scratch();
    plaintext_archive(&root, &[("m0", "body")]);
    encrypt_archive(&root, &recipient()).unwrap();
    plaintext_archive(&root, &[("m0", "body")]);

    let error = encrypt_archive(&root, &recipient()).unwrap_err();

    assert!(error.to_string().contains("already encrypted"));
    assert!(
        Archive::open(&root).is_err(),
        "ledger.json still blocks opening the archive"
    );
}
