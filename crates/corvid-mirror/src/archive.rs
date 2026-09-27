use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::crypto::{self, Identities};
use crate::source::Record;

const SCHEMA: u32 = 2;

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Held {
    /// Sha256 of the plaintext. Unchanged by encryption, so dedup and dedup-by-content still
    /// work without decrypting anything.
    pub digest: String,
    /// Sha256 of the bytes actually on disk. The nightly verify checks this alone, with no
    /// key; `verify --deep` decrypts and checks `digest` and `size` against the plaintext.
    pub ciphertext_digest: String,
    pub received_at: String,
    pub size: usize,
    /// As of the last sight. A keyword change is recorded and never a reason to re-fetch.
    pub keywords: BTreeSet<String>,
    /// As of the last sight, by name. Empty for anything archived before mailbox tracking
    /// existed; restore falls back to a default mailbox for those.
    pub mailboxes: BTreeSet<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Ledger {
    cursor: Option<String>,
    held: BTreeMap<String, Held>,
    /// Ids the server no longer has. The body stays; this records the deletion so the user can
    /// act on it.
    vanished: BTreeSet<String>,
}

// `Held` is pinned in its own right; anything deeper fails only at load time.
corvid_store::pin_shape!(Ledger {
    cursor,
    held,
    vanished
});
corvid_store::pin_shape!(Held {
    digest,
    ciphertext_digest,
    received_at,
    size,
    keywords,
    mailboxes
});

/// A local copy of the mailbox that outlives the server.
///
/// **Append-only.** A message deleted on the server stays here. The audit therefore checks that
/// every server id is present rather than comparing counts, which differ after any deletion.
///
/// **Bodies are encrypted at rest.** An archive written before encryption existed is schema 1
/// and this type is schema 2, so `corvid_store` refuses to open it: `encrypt-archive` is the
/// only path from one to the other, and a partly-migrated archive is not a schema this type
/// will ever recognise.
pub struct Archive {
    root: PathBuf,
    ledger: Ledger,
}

impl Archive {
    pub fn open(root: impl Into<PathBuf>) -> io::Result<Self> {
        let root = root.into();
        fs::create_dir_all(root.join("blobs"))?;
        let ledger = corvid_store::open(&ledger_path(&root), SCHEMA)?;
        Ok(Self { root, ledger })
    }

    pub fn cursor(&self) -> Option<&str> {
        self.ledger.cursor.as_deref()
    }

    pub fn set_cursor(&mut self, cursor: impl Into<String>) {
        self.ledger.cursor = Some(cursor.into());
    }

    pub fn holds(&self, id: &str) -> bool {
        self.ledger.held.contains_key(id)
    }

    pub fn held(&self, id: &str) -> Option<&Held> {
        self.ledger.held.get(id)
    }

    pub fn is_vanished(&self, id: &str) -> bool {
        self.ledger.vanished.contains(id)
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, &Held)> {
        self.ledger
            .held
            .iter()
            .map(|(id, held)| (id.as_str(), held))
    }

    pub fn len(&self) -> usize {
        self.ledger.held.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ledger.held.is_empty()
    }

    /// Encrypts and writes the body before the ledger names it, so a crash leaves an
    /// unreferenced blob rather than a ledger entry pointing at nothing.
    pub fn store(
        &mut self,
        record: &Record,
        body: &[u8],
        recipient: &age::x25519::Recipient,
    ) -> io::Result<()> {
        let digest = hex::encode(Sha256::digest(body));
        let path = self.blob_path(&digest);
        // Dedup is keyed on the plaintext digest; encryption is not deterministic, so a
        // duplicate is skipped rather than re-encrypted, and its digest is read back off disk.
        let ciphertext_digest = if path.exists() {
            hex::encode(Sha256::digest(fs::read(&path)?))
        } else {
            let ciphertext = crypto::encrypt(recipient, body)?;
            let ciphertext_digest = hex::encode(Sha256::digest(&ciphertext));
            fs::create_dir_all(path.parent().expect("blob paths have a parent"))?;
            let temporary = path.with_extension("tmp");
            let mut file = fs::File::create(&temporary)?;
            file.write_all(&ciphertext)?;
            file.sync_all()?;
            fs::rename(&temporary, &path)?;
            ciphertext_digest
        };
        self.ledger.held.insert(
            record.id.clone(),
            Held {
                digest,
                ciphertext_digest,
                received_at: record.received_at.clone(),
                size: body.len(),
                keywords: record.keywords.clone(),
                mailboxes: record.mailboxes.clone(),
            },
        );
        Ok(())
    }

    pub fn note_seen(
        &mut self,
        id: &str,
        keywords: &BTreeSet<String>,
        mailboxes: &BTreeSet<String>,
    ) {
        if let Some(held) = self.ledger.held.get_mut(id) {
            held.keywords = keywords.clone();
            held.mailboxes = mailboxes.clone();
        }
    }

    pub fn save(&self) -> io::Result<()> {
        corvid_store::save(&ledger_path(&self.root), SCHEMA, &self.ledger)
    }

    /// The raw stored bytes: ciphertext, not the message.
    pub fn read(&self, id: &str) -> io::Result<Vec<u8>> {
        let held =
            self.ledger.held.get(id).ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotFound, format!("{id} is not held"))
            })?;
        fs::read(self.blob_path(&held.digest))
    }

    /// Decrypts a held message. Needs an identity, so never called on the nightly path.
    pub fn read_plaintext(&self, id: &str, identities: &Identities) -> io::Result<Vec<u8>> {
        crypto::decrypt(identities, &self.read(id)?)
    }

    /// Server ids with no local copy.
    pub fn missing_from<'a>(&self, ids: &'a [String]) -> Vec<&'a String> {
        ids.iter().filter(|id| !self.holds(id)).collect()
    }

    /// Records which held ids the server no longer lists, and returns the newly noted ones.
    pub fn note_vanished(&mut self, present: &[String]) -> Vec<String> {
        let present: BTreeSet<&String> = present.iter().collect();
        let gone: Vec<String> = self
            .ledger
            .held
            .keys()
            .filter(|id| !present.contains(id) && !self.ledger.vanished.contains(*id))
            .cloned()
            .collect();
        self.ledger.vanished.extend(gone.iter().cloned());
        gone
    }

    pub fn vanished(&self) -> impl Iterator<Item = &String> {
        self.ledger.vanished.iter()
    }

    /// Rehashes every held ciphertext, needing no server and no identity.
    pub fn verify(&self) -> io::Result<Vec<String>> {
        let mut damaged = Vec::new();
        for (id, held) in &self.ledger.held {
            match fs::read(self.blob_path(&held.digest)) {
                Ok(bytes) if hex::encode(Sha256::digest(&bytes)) == held.ciphertext_digest => {}
                _ => damaged.push(id.clone()),
            }
        }
        Ok(damaged)
    }

    /// Decrypts every held body and checks it against the plaintext digest and size the
    /// server reported at fetch time. Proves recoverability, not just that the disk agrees
    /// with itself.
    pub fn verify_deep(&self, identities: &Identities) -> io::Result<Vec<String>> {
        let mut damaged = Vec::new();
        for (id, held) in &self.ledger.held {
            let matches = fs::read(self.blob_path(&held.digest))
                .ok()
                .and_then(|ciphertext| crypto::decrypt(identities, &ciphertext).ok())
                .is_some_and(|plaintext| {
                    plaintext.len() == held.size
                        && hex::encode(Sha256::digest(&plaintext)) == held.digest
                });
            if !matches {
                damaged.push(id.clone());
            }
        }
        Ok(damaged)
    }

    fn blob_path(&self, digest: &str) -> PathBuf {
        blob_path(&self.root, digest)
    }
}

/// Shared with the `encrypt-archive` migration, which writes blobs and reads the legacy
/// ledger before an `Archive` of the new schema can be opened.
pub(crate) fn blob_path(root: &Path, digest: &str) -> PathBuf {
    root.join("blobs").join(&digest[..2]).join(digest)
}

pub(crate) fn ledger_path(root: &Path) -> PathBuf {
    root.join("ledger.json")
}
