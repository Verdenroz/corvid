mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use corvid_mirror::{Archive, sync, verify};
use serde::Serialize;

use common::{Server, recipient, scratch};

#[test]
fn a_stolen_archive_reveals_no_plaintext_bytes() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "the subject line carries marker-9f3c1a in the clear");
    sync(&root, &server, &recipient()).unwrap();

    for blob in blobs(&root.join("blobs")) {
        let bytes = std::fs::read(&blob).unwrap();
        assert!(
            !bytes.windows(6).any(|w| w == b"marker"),
            "{blob:?} carries a plaintext marker"
        );
    }
}

fn blobs(dir: &Path) -> Vec<PathBuf> {
    let mut files = Vec::new();
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            files.extend(blobs(&path));
        } else {
            files.push(path);
        }
    }
    files
}

#[test]
fn a_flipped_ciphertext_byte_fails_the_nightly_verify() {
    let (_dir, root) = scratch();
    let server = Server::default();
    server.deliver("m0", "body");
    sync(&root, &server, &recipient()).unwrap();

    let digest = Archive::open(&root)
        .unwrap()
        .held("m0")
        .unwrap()
        .digest
        .clone();
    let blob = root.join("blobs").join(&digest[..2]).join(&digest);
    let mut bytes = std::fs::read(&blob).unwrap();
    bytes[0] ^= 0xff;
    std::fs::write(&blob, bytes).unwrap();

    assert_eq!(verify(&root).unwrap(), ["m0"]);
}

// Schema 1, before ciphertext_digest and mailboxes existed. Built by hand rather than reused
// from the crate, so this test still exercises a real schema 1 file after a future bump.
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
        Ok(_) => panic!("a schema 1 archive was opened as schema 2"),
        Err(error) => assert!(error.to_string().contains("different schema")),
    }
}
