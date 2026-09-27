mod common;

use std::collections::{BTreeMap, BTreeSet};

use corvid_mirror::{Archive, sync, verify, verify_deep};
use serde::Serialize;

use common::{Server, files, identities, only_blob, recipient, scratch};

#[test]
fn a_stolen_archive_reveals_no_subject_keyword_or_mailbox() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver_to(
        "m0",
        "the subject line carries marker-9f3c1a in the clear",
        &["Personal/Taxes"],
    );
    server.set_keywords("m0", &["corvid-llm-finance"]);
    sync(&root, &server, &recipient()).unwrap();

    for file in files(&root) {
        let bytes = std::fs::read(&file).unwrap();
        for needle in [
            b"marker".as_slice(),
            b"corvid-llm-finance".as_slice(),
            b"Taxes".as_slice(),
        ] {
            assert!(
                !bytes.windows(needle.len()).any(|w| w == needle),
                "{file:?} carries {:?} in the clear",
                std::str::from_utf8(needle).unwrap()
            );
        }
    }
}

#[test]
fn a_flipped_ciphertext_byte_fails_the_nightly_verify() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server, &recipient()).unwrap();

    let blob = only_blob(&root);
    let mut bytes = std::fs::read(&blob).unwrap();
    bytes[0] ^= 0xff;
    std::fs::write(&blob, bytes).unwrap();

    assert_eq!(verify(&root).unwrap(), ["m0"]);
}

#[test]
fn verify_deep_confirms_a_healthy_archive_decrypts() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server, &recipient()).unwrap();

    assert!(verify_deep(&root, &identities()).unwrap().is_empty());
}

#[test]
fn verify_deep_also_catches_a_flipped_ciphertext_byte() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server, &recipient()).unwrap();

    let blob = only_blob(&root);
    let mut bytes = std::fs::read(&blob).unwrap();
    bytes[0] ^= 0xff;
    std::fs::write(&blob, bytes).unwrap();

    assert_eq!(verify_deep(&root, &identities()).unwrap(), ["m0"]);
}

// Schema 1: a single plaintext ledger, before the index/metadata split. Built by hand rather
// than reused from the crate, so this test still exercises a real schema 1 file after a
// future bump changes the current shape again.
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

#[test]
fn an_existing_plaintext_archive_is_refused_not_migrated() {
    let (_dir, root) = scratch();
    std::fs::create_dir_all(&root).unwrap();
    corvid_store::save(&root.join("ledger.json"), 1, &LegacyLedger::default()).unwrap();

    match Archive::open(&root) {
        Ok(_) => panic!("a plaintext archive was opened as encrypted"),
        Err(error) => assert!(error.to_string().contains("encrypt-archive")),
    }
}

#[test]
fn an_index_of_the_wrong_schema_is_refused() {
    let (_dir, root) = scratch();
    std::fs::create_dir_all(&root).unwrap();
    corvid_store::save(&root.join("index.json"), 99, &LegacyLedger::default()).unwrap();

    match Archive::open(&root) {
        Ok(_) => panic!("an index of the wrong schema was opened"),
        Err(error) => assert!(error.to_string().contains("different schema")),
    }
}
