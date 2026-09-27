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
use crate::source::{Record, Source};

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

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Migrated {
    pub total: usize,
    /// Migrated with no mailbox, because the server no longer has the message to ask. Restore
    /// falls back to the default mailbox for these.
    pub without_mailboxes: usize,
}

/// One-shot migration of a plaintext archive (`ledger.json`) to an encrypted one.
///
/// Ciphertext blobs are written into the same `blobs` the plaintext ones already occupy,
/// never a staging directory: a ciphertext digest and its plaintext's digest are different
/// values, so the two kinds never collide, and nothing needs renaming into place before the
/// index can reference it. Saving the index is the only commit point; everything before it can
/// be redone from `ledger.json` with no harm beyond orphaned (but never referenced) blobs and
/// segments, and everything after it is cleanup that a rerun resumes rather than refuses.
pub fn encrypt_archive(
    root: &Path,
    source: &impl Source,
    recipient: &age::x25519::Recipient,
) -> io::Result<Migrated> {
    let index_path = archive::index_path(root);
    let ledger_path = root.join("ledger.json");
    let committed = index_schema(&index_path)? == Some(CURRENT_SCHEMA);

    if !ledger_path.exists() {
        return if committed {
            Err(io::Error::other(format!(
                "{}: already encrypted; nothing to migrate",
                root.display()
            )))
        } else {
            Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("{}: no archive to migrate", root.display()),
            ))
        };
    }
    let legacy: LegacyLedger =
        corvid_store::load(&ledger_path, LEGACY_SCHEMA)?.ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::NotFound,
                format!("{}: no archive to migrate", root.display()),
            )
        })?;

    if committed {
        let total = retire_plaintext(root, &ledger_path, &legacy)?;
        return Ok(Migrated {
            total,
            without_mailboxes: 0,
        });
    }

    let live_ids: Vec<String> = legacy
        .held
        .keys()
        .filter(|id| !legacy.vanished.contains(*id))
        .cloned()
        .collect();
    let current: BTreeMap<String, Record> = source
        .records(&live_ids)?
        .into_iter()
        .map(|record| (record.id.clone(), record))
        .collect();

    let mut held = BTreeMap::new();
    let mut entries = BTreeMap::new();
    let mut without_mailboxes = 0;
    for (id, legacy_held) in &legacy.held {
        let plaintext = fs::read(archive::blob_path(root, &legacy_held.digest))?;
        let ciphertext = crypto::encrypt(recipient, &plaintext)?;
        let ciphertext_digest = hex::encode(Sha256::digest(&ciphertext));
        archive::write_blob(root, &ciphertext_digest, &ciphertext)?;
        held.insert(id.clone(), ciphertext_digest);

        let (keywords, mailboxes) = match current.get(id) {
            Some(record) => (record.keywords.clone(), record.mailboxes.clone()),
            None => {
                without_mailboxes += 1;
                (legacy_held.keywords.clone(), BTreeSet::new())
            }
        };
        entries.insert(
            id.clone(),
            Entry::New(Meta {
                digest: legacy_held.digest.clone(),
                size: legacy_held.size,
                received_at: legacy_held.received_at.clone(),
                keywords,
                mailboxes,
            }),
        );
    }
    let total = held.len();
    let new_index = NewIndex {
        cursor: legacy.cursor.clone(),
        held,
        vanished: legacy.vanished.clone(),
    };

    meta::write_segment(root, &entries, recipient)?;
    corvid_store::save(&index_path, CURRENT_SCHEMA, &new_index)?;
    retire_plaintext(root, &ledger_path, &legacy)?;

    Ok(Migrated {
        total,
        without_mailboxes,
    })
}

/// Deletes the plaintext blobs `ledger.json` names, then `ledger.json` itself, tolerating
/// either already being gone: this runs once right after a fresh migration commits, and again
/// on a rerun that finds the index already committed but this cleanup unfinished.
fn retire_plaintext(root: &Path, ledger_path: &Path, legacy: &LegacyLedger) -> io::Result<usize> {
    for legacy_held in legacy.held.values() {
        match fs::remove_file(archive::blob_path(root, &legacy_held.digest)) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
    }
    fs::remove_file(ledger_path)?;
    Ok(legacy.held.len())
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
