use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io;
use std::path::Path;

use serde::Deserialize;
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::archive::{self, CURRENT_SCHEMA};
use crate::crypto;
use crate::meta::{self, Entry, Meta};

const LEGACY_SCHEMA: u32 = 1;

#[derive(Deserialize)]
struct LegacyHeld {
    digest: String,
    received_at: String,
    size: usize,
    keywords: BTreeSet<String>,
}

#[derive(Default, Deserialize)]
struct LegacyLedger {
    cursor: Option<String>,
    held: BTreeMap<String, LegacyHeld>,
    vanished: BTreeSet<String>,
}

corvid_store::pin_shape!(LegacyLedger {
    cursor,
    held,
    vanished
});
corvid_store::pin_shape!(LegacyHeld {
    digest,
    received_at,
    size,
    keywords
});

#[derive(Default, Serialize)]
struct NewIndex {
    cursor: Option<String>,
    held: BTreeMap<String, String>,
    vanished: BTreeSet<String>,
}

/// One-shot migration of a plaintext archive (`ledger.json`) to an encrypted one. Encrypts
/// every blob into a fresh directory, then swaps it in: a crash before the swap leaves the
/// original archive untouched, and a crash after it fails loudly on retry rather than
/// re-encrypting ciphertext as if it were plaintext.
///
/// "Already encrypted" is decided from the index alone, never from whether `ledger.json` is
/// still there: a crash between committing the index and removing it must not make a second
/// run re-migrate. If that happens, `Archive::open` still refuses until the leftover
/// `ledger.json` is removed by hand.
pub fn encrypt_archive(root: &Path, recipient: &age::x25519::Recipient) -> io::Result<usize> {
    let index_path = archive::index_path(root);
    if index_schema(&index_path)? == Some(CURRENT_SCHEMA) {
        return Err(io::Error::other(format!(
            "{}: already encrypted; nothing to migrate",
            root.display()
        )));
    }
    let ledger_path = root.join("ledger.json");
    let legacy: LegacyLedger =
        corvid_store::load(&ledger_path, LEGACY_SCHEMA)?.ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("{}: no archive to migrate", root.display()),
            )
        })?;

    let staging = root.join("blobs.enc");
    let mut held = BTreeMap::new();
    let mut entries = BTreeMap::new();
    for (id, legacy_held) in &legacy.held {
        let plaintext = fs::read(archive::blob_path(root, &legacy_held.digest))?;
        let ciphertext = crypto::encrypt(recipient, &plaintext)?;
        let ciphertext_digest = hex::encode(Sha256::digest(&ciphertext));
        archive::write_blob_into(&staging, &ciphertext_digest, &ciphertext)?;
        held.insert(id.clone(), ciphertext_digest);
        entries.insert(
            id.clone(),
            Entry::New(Meta {
                digest: legacy_held.digest.clone(),
                size: legacy_held.size,
                received_at: legacy_held.received_at.clone(),
                keywords: legacy_held.keywords.clone(),
                mailboxes: BTreeSet::new(),
            }),
        );
    }
    let migrated = held.len();
    let new_index = NewIndex {
        cursor: legacy.cursor,
        held,
        vanished: legacy.vanished,
    };

    let plaintext_dir = root.join("blobs");
    let backup = root.join("blobs.plaintext");
    fs::rename(&plaintext_dir, &backup)?;
    fs::rename(&staging, &plaintext_dir)?;
    meta::write_segment(root, &entries, recipient)?;
    corvid_store::save(&index_path, CURRENT_SCHEMA, &new_index)?;
    // The index is the commit point. `ledger.json` goes next, since its mere presence is what
    // `Archive::open` refuses on; the plaintext backup is the last, lowest-priority cleanup,
    // since leaving it a little longer costs disk, not correctness.
    fs::remove_file(&ledger_path)?;
    fs::remove_dir_all(&backup)?;

    Ok(migrated)
}

/// Peeks at a state file's schema line without validating its checksum, so the caller can
/// decide which schema to fully (and validated-ly) load with `corvid_store`.
fn index_schema(path: &Path) -> io::Result<Option<u32>> {
    match fs::read_to_string(path) {
        Ok(contents) => contents
            .lines()
            .nth(1)
            .and_then(|line| line.parse().ok())
            .map(Some)
            .ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    "state file has no schema version",
                )
            }),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error),
    }
}
