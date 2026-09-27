use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::source::Record;

const SCHEMA: u32 = 1;

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Held {
    pub digest: String,
    pub received_at: String,
    pub size: usize,
    /// As of the last sight. A keyword change is recorded and never a reason to re-fetch.
    pub keywords: BTreeSet<String>,
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
    received_at,
    size,
    keywords
});

/// A local copy of the mailbox that outlives the server.
///
/// **Append-only.** A message deleted on the server stays here. The audit therefore checks that
/// every server id is present rather than comparing counts, which differ after any deletion.
pub struct Archive {
    root: PathBuf,
    ledger: Ledger,
}

impl Archive {
    pub fn open(root: impl Into<PathBuf>) -> io::Result<Self> {
        let root = root.into();
        fs::create_dir_all(root.join("blobs"))?;
        let ledger = corvid_store::open(&Self::ledger_path(&root), SCHEMA)?;
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

    pub fn len(&self) -> usize {
        self.ledger.held.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ledger.held.is_empty()
    }

    /// Writes the body before the ledger names it, so a crash leaves an unreferenced blob
    /// rather than a ledger entry pointing at nothing.
    pub fn store(&mut self, record: &Record, body: &[u8]) -> io::Result<()> {
        let digest = hex::encode(Sha256::digest(body));
        let path = self.blob_path(&digest);
        if !path.exists() {
            fs::create_dir_all(path.parent().expect("blob paths have a parent"))?;
            let temporary = path.with_extension("tmp");
            let mut file = fs::File::create(&temporary)?;
            file.write_all(body)?;
            file.sync_all()?;
            fs::rename(&temporary, &path)?;
        }
        self.ledger.held.insert(
            record.id.clone(),
            Held {
                digest,
                received_at: record.received_at.clone(),
                size: body.len(),
                keywords: record.keywords.clone(),
            },
        );
        Ok(())
    }

    pub fn note_keywords(&mut self, id: &str, keywords: &BTreeSet<String>) {
        if let Some(held) = self.ledger.held.get_mut(id) {
            held.keywords = keywords.clone();
        }
    }

    pub fn save(&self) -> io::Result<()> {
        corvid_store::save(&Self::ledger_path(&self.root), SCHEMA, &self.ledger)
    }

    pub fn read(&self, id: &str) -> io::Result<Vec<u8>> {
        let held =
            self.ledger.held.get(id).ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotFound, format!("{id} is not held"))
            })?;
        fs::read(self.blob_path(&held.digest))
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

    /// Rehashes every held body, needing no server, since the archive is for when it is gone.
    pub fn verify(&self) -> io::Result<Vec<String>> {
        let mut damaged = Vec::new();
        for (id, held) in &self.ledger.held {
            match fs::read(self.blob_path(&held.digest)) {
                Ok(body) if hex::encode(Sha256::digest(&body)) == held.digest => {}
                _ => damaged.push(id.clone()),
            }
        }
        Ok(damaged)
    }

    fn ledger_path(root: &Path) -> PathBuf {
        root.join("ledger.json")
    }

    fn blob_path(&self, digest: &str) -> PathBuf {
        self.root.join("blobs").join(&digest[..2]).join(digest)
    }
}
