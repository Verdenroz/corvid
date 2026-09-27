use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::crypto::{self, Identities};
use crate::meta::{self, Entry, Meta};
use crate::source::Record;

const SCHEMA: u32 = 2;

/// The operational index: what sync, audit and the nightly verify need, and nothing a stolen
/// copy could read as content. Subjects, keywords and mailbox names live in `meta` instead.
#[derive(Debug, Default, Serialize, Deserialize)]
struct Index {
    cursor: Option<String>,
    /// Server id to the blob holding it, named by its own ciphertext digest so the filename
    /// carries no digest of the plaintext an attacker could match against known mail.
    held: BTreeMap<String, String>,
    /// Ids the server no longer has. The body stays; this records the deletion so the user can
    /// act on it.
    vanished: BTreeSet<String>,
}

corvid_store::pin_shape!(Index {
    cursor,
    held,
    vanished
});

/// A local copy of the mailbox that outlives the server.
///
/// **Append-only.** A message deleted on the server stays here. The audit therefore checks that
/// every server id is present rather than comparing counts, which differ after any deletion.
///
/// **Encrypted at rest.** A pre-encryption archive has a `ledger.json` this type refuses on
/// sight, and a foreign or newer index schema is refused by `corvid_store` itself.
/// `encrypt-archive` is the only path from one to the other.
pub struct Archive {
    root: PathBuf,
    index: Index,
    /// This run's metadata, written as one segment by `save`. Nothing here is read back
    /// before then, so building it needs no identity.
    pending: BTreeMap<String, Entry>,
}

impl Archive {
    pub fn open(root: impl Into<PathBuf>) -> io::Result<Self> {
        let root = root.into();
        // `ledger.json` is the pre-encryption archive's only state file. Its absence is not
        // proof of a fresh archive on its own, but its presence always means one that
        // `encrypt_archive` has not yet touched, before an index has even been considered.
        if root.join("ledger.json").exists() {
            return Err(io::Error::other(format!(
                "{}: a plaintext archive exists (ledger.json); run encrypt-archive first",
                root.display()
            )));
        }
        fs::create_dir_all(root.join("blobs"))?;
        let index = corvid_store::open(&index_path(&root), SCHEMA)?;
        Ok(Self {
            root,
            index,
            pending: BTreeMap::new(),
        })
    }

    pub fn cursor(&self) -> Option<&str> {
        self.index.cursor.as_deref()
    }

    pub fn set_cursor(&mut self, cursor: impl Into<String>) {
        self.index.cursor = Some(cursor.into());
    }

    pub fn holds(&self, id: &str) -> bool {
        self.index.held.contains_key(id)
    }

    pub fn is_vanished(&self, id: &str) -> bool {
        self.index.vanished.contains(id)
    }

    pub fn held_ids(&self) -> impl Iterator<Item = &str> {
        self.index.held.keys().map(String::as_str)
    }

    pub fn len(&self) -> usize {
        self.index.held.len()
    }

    pub fn is_empty(&self) -> bool {
        self.index.held.is_empty()
    }

    /// Encrypts and writes the body under its own ciphertext digest before the index names
    /// it, so a crash leaves an unreferenced blob rather than an index entry pointing at
    /// nothing. Encryption is not deterministic, so unlike a plaintext archive this keeps no
    /// dedup by content: two copies of the same message cost two blobs.
    pub fn store(
        &mut self,
        record: &Record,
        body: &[u8],
        recipient: &age::x25519::Recipient,
    ) -> io::Result<()> {
        let ciphertext = crypto::encrypt(recipient, body)?;
        let digest = hex::encode(Sha256::digest(&ciphertext));
        write_blob(&self.root, &digest, &ciphertext)?;
        self.index.held.insert(record.id.clone(), digest);
        self.pending.insert(
            record.id.clone(),
            Entry::New(Meta {
                digest: hex::encode(Sha256::digest(body)),
                size: body.len(),
                received_at: record.received_at.clone(),
                keywords: record.keywords.clone(),
                mailboxes: record.mailboxes.clone(),
            }),
        );
        Ok(())
    }

    /// Records a keyword or mailbox change for an already-held message. The change lands in
    /// this run's segment as a touch on whatever earlier segment introduced the message, so
    /// it costs no read and no identity.
    pub fn note_seen(
        &mut self,
        id: &str,
        keywords: &BTreeSet<String>,
        mailboxes: &BTreeSet<String>,
    ) {
        self.pending.insert(
            id.to_owned(),
            Entry::Touch {
                keywords: keywords.clone(),
                mailboxes: mailboxes.clone(),
            },
        );
    }

    /// Saves the index alone. Used where nothing this run could have touched metadata, such
    /// as the audit.
    pub fn save_index(&self) -> io::Result<()> {
        corvid_store::save(&index_path(&self.root), SCHEMA, &self.index)
    }

    /// Writes this run's metadata segment, then the index: the index is the commit point, so
    /// a crash between the two leaves a segment nothing yet points at, not an index entry
    /// pointing at nothing.
    pub fn save(&self, recipient: &age::x25519::Recipient) -> io::Result<()> {
        meta::write_segment(&self.root, &self.pending, recipient)?;
        self.save_index()
    }

    /// The raw stored bytes: ciphertext, not the message.
    pub fn read(&self, id: &str) -> io::Result<Vec<u8>> {
        let digest =
            self.index.held.get(id).ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotFound, format!("{id} is not held"))
            })?;
        fs::read(blob_path(&self.root, digest))
    }

    /// Decrypts a held message. Needs an identity, so never called on the nightly path.
    pub fn read_plaintext(&self, id: &str, identities: &Identities) -> io::Result<Vec<u8>> {
        crypto::decrypt(identities, &self.read(id)?)
    }

    /// This archive's metadata, decrypted. Needs an identity, so never called on the nightly
    /// path.
    pub fn meta(&self, identities: &Identities) -> io::Result<BTreeMap<String, Meta>> {
        meta::replay(&self.root, identities)
    }

    /// Server ids with no local copy.
    pub fn missing_from<'a>(&self, ids: &'a [String]) -> Vec<&'a String> {
        ids.iter().filter(|id| !self.holds(id)).collect()
    }

    /// Records which held ids the server no longer lists, and returns the newly noted ones.
    pub fn note_vanished(&mut self, present: &[String]) -> Vec<String> {
        let present: BTreeSet<&String> = present.iter().collect();
        let gone: Vec<String> = self
            .index
            .held
            .keys()
            .filter(|id| !present.contains(id) && !self.index.vanished.contains(*id))
            .cloned()
            .collect();
        self.index.vanished.extend(gone.iter().cloned());
        gone
    }

    pub fn vanished(&self) -> impl Iterator<Item = &String> {
        self.index.vanished.iter()
    }

    /// Rehashes every held blob against its own name, needing no server and no identity: a
    /// blob is named by its ciphertext digest, so this alone catches damaged ciphertext.
    pub fn verify(&self) -> io::Result<Vec<String>> {
        let mut damaged = Vec::new();
        for (id, digest) in &self.index.held {
            match fs::read(blob_path(&self.root, digest)) {
                Ok(bytes) if hex::encode(Sha256::digest(&bytes)) == *digest => {}
                _ => damaged.push(id.clone()),
            }
        }
        Ok(damaged)
    }

    /// Decrypts every held body and checks it against the plaintext digest and size recorded
    /// when it was fetched. Proves recoverability, not just that the disk agrees with itself.
    pub fn verify_deep(&self, identities: &Identities) -> io::Result<Vec<String>> {
        let meta = meta::replay(&self.root, identities)?;
        let mut damaged = Vec::new();
        for (id, digest) in &self.index.held {
            let matches = fs::read(blob_path(&self.root, digest))
                .ok()
                .and_then(|ciphertext| crypto::decrypt(identities, &ciphertext).ok())
                .zip(meta.get(id))
                .is_some_and(|(plaintext, meta)| {
                    plaintext.len() == meta.size
                        && hex::encode(Sha256::digest(&plaintext)) == meta.digest
                });
            if !matches {
                damaged.push(id.clone());
            }
        }
        Ok(damaged)
    }
}

fn write_blob(root: &Path, digest: &str, bytes: &[u8]) -> io::Result<()> {
    write_blob_into(&root.join("blobs"), digest, bytes)
}

/// Shared with the `encrypt-archive` migration, which builds a fresh blob directory before
/// swapping it in as `blobs`, so it names it by hand rather than through an `Archive`.
pub(crate) fn write_blob_into(blobs_dir: &Path, digest: &str, bytes: &[u8]) -> io::Result<()> {
    let path = shard(blobs_dir, digest);
    fs::create_dir_all(path.parent().expect("blob paths have a parent"))?;
    let temporary = path.with_extension("tmp");
    let mut file = fs::File::create(&temporary)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    fs::rename(&temporary, &path)
}

pub(crate) fn blob_path(root: &Path, digest: &str) -> PathBuf {
    shard(&root.join("blobs"), digest)
}

fn shard(blobs_dir: &Path, digest: &str) -> PathBuf {
    blobs_dir.join(&digest[..2]).join(digest)
}

pub(crate) fn index_path(root: &Path) -> PathBuf {
    root.join("index.json")
}

pub(crate) const CURRENT_SCHEMA: u32 = SCHEMA;
