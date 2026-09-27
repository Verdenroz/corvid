//! Per-message metadata, stored apart from the plaintext index because it is what a stolen
//! archive must not read: subjects imply nothing here, but keywords and mailbox names do.
//! Segments are age-encrypted and append-only, one written per sync run; sync itself never
//! reads one back, so the identity never touches that path.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{self, Write};
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::crypto::{self, Identities};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Meta {
    /// Sha256 of the plaintext, for `verify --deep`.
    pub digest: String,
    pub size: usize,
    pub received_at: String,
    /// As of the last sight. A keyword change is recorded and never a reason to re-fetch.
    pub keywords: BTreeSet<String>,
    /// As of the last sight, by full path. Empty for anything archived before mailbox
    /// tracking existed; restore falls back to a default mailbox for those.
    pub mailboxes: BTreeSet<String>,
}

/// A new message carries its whole record; an already-known one only what can change, so a
/// keyword or mailbox move needs no digest or size to write.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Entry {
    New(Meta),
    Touch {
        keywords: BTreeSet<String>,
        mailboxes: BTreeSet<String>,
    },
}

corvid_store::pin_shape!(Meta {
    digest,
    size,
    received_at,
    keywords,
    mailboxes
});

/// Writes this run's entries as one new segment, or does nothing if there are none: a no-op
/// sync leaves no empty file behind.
pub fn write_segment(
    root: &Path,
    entries: &BTreeMap<String, Entry>,
    recipient: &age::x25519::Recipient,
) -> io::Result<()> {
    if entries.is_empty() {
        return Ok(());
    }
    let dir = root.join("meta");
    fs::create_dir_all(&dir)?;
    let seq = next_seq(&dir)?;
    let payload = serde_json::to_vec(entries).map_err(io::Error::other)?;
    let ciphertext = crypto::encrypt(recipient, &payload)?;
    let path = dir.join(format!("{seq}.age"));
    let temporary = path.with_extension("tmp");
    let mut file = fs::File::create(&temporary)?;
    file.write_all(&ciphertext)?;
    file.sync_all()?;
    fs::rename(&temporary, &path)
}

/// Decrypts every segment in sequence order and replays it into one metadata map, later
/// segments overriding earlier ones for the same id.
///
/// A segment that fails to decrypt is a refusal, not a skip: silently dropping one would
/// misfile a restored message into the fallback mailbox, or serve stale keywords, with
/// nobody told.
pub fn replay(root: &Path, identities: &Identities) -> io::Result<BTreeMap<String, Meta>> {
    let dir = root.join("meta");
    let mut segments: Vec<(u64, std::path::PathBuf)> = match fs::read_dir(&dir) {
        Ok(entries) => entries
            .filter_map(|entry| entry.ok())
            .filter_map(|entry| {
                let path = entry.path();
                segment_seq(&path).map(|seq| (seq, path))
            })
            .collect(),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Vec::new(),
        Err(error) => return Err(error),
    };
    segments.sort_by_key(|(seq, _)| *seq);

    let mut meta: BTreeMap<String, Meta> = BTreeMap::new();
    for (_, path) in segments {
        let ciphertext = fs::read(&path)?;
        let plaintext = crypto::decrypt(identities, &ciphertext)
            .map_err(|error| io::Error::other(format!("{}: {error}", path.display())))?;
        let entries: BTreeMap<String, Entry> =
            serde_json::from_slice(&plaintext).map_err(io::Error::other)?;
        for (id, entry) in entries {
            match entry {
                Entry::New(record) => {
                    meta.insert(id, record);
                }
                Entry::Touch {
                    keywords,
                    mailboxes,
                } => {
                    let existing = meta.get_mut(&id).ok_or_else(|| {
                        io::Error::new(
                            io::ErrorKind::InvalidData,
                            format!("{id}: a keyword or mailbox update with no earlier record"),
                        )
                    })?;
                    existing.keywords = keywords;
                    existing.mailboxes = mailboxes;
                }
            }
        }
    }
    Ok(meta)
}

fn next_seq(dir: &Path) -> io::Result<u64> {
    let mut max = None;
    for entry in fs::read_dir(dir)? {
        if let Some(seq) = segment_seq(&entry?.path()) {
            max = Some(max.map_or(seq, |current: u64| current.max(seq)));
        }
    }
    Ok(max.map_or(0, |current| current + 1))
}

/// A segment's sequence number, or `None` for anything that is not a committed segment: in
/// particular the `.tmp` a crash mid-write can leave behind, which must count toward neither
/// the next sequence number nor a replay.
fn segment_seq(path: &Path) -> Option<u64> {
    if path.extension().and_then(|ext| ext.to_str()) != Some("age") {
        return None;
    }
    path.file_stem()?.to_str()?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_SK: &str =
        "AGE-SECRET-KEY-1GQ9778VQXMMJVE8SK7J6VT8UJ4HDQAJUVSFCWCM02D8GEWQ72PVQ2Y5J33";
    const TEST_PK: &str = "age1t7rxyev2z3rw82stdlrrepyc39nvn86l5078zqkf5uasdy86jp6svpy7pa";

    fn identities() -> Identities {
        vec![Box::new(TEST_SK.parse::<age::x25519::Identity>().unwrap())]
    }

    fn recipient() -> age::x25519::Recipient {
        TEST_PK.parse().unwrap()
    }

    fn new_entry(size: usize) -> Entry {
        Entry::New(Meta {
            digest: "d".to_owned(),
            size,
            received_at: "2026-09-07T00:00:00Z".to_owned(),
            keywords: BTreeSet::new(),
            mailboxes: BTreeSet::new(),
        })
    }

    #[test]
    fn a_stray_tmp_segment_does_not_break_replay() {
        let root = tempfile::tempdir().unwrap();
        let entries = BTreeMap::from([("m0".to_owned(), new_entry(4))]);
        write_segment(root.path(), &entries, &recipient()).unwrap();
        std::fs::write(root.path().join("meta").join("3.tmp"), b"garbage").unwrap();

        let replayed = replay(root.path(), &identities()).unwrap();

        assert_eq!(replayed["m0"].size, 4);
    }

    #[test]
    fn a_touch_with_no_earlier_record_is_refused() {
        let root = tempfile::tempdir().unwrap();
        let touch = Entry::Touch {
            keywords: BTreeSet::new(),
            mailboxes: BTreeSet::new(),
        };
        let entries = BTreeMap::from([("m0".to_owned(), touch)]);
        write_segment(root.path(), &entries, &recipient()).unwrap();

        let error = replay(root.path(), &identities()).unwrap_err();

        assert!(error.to_string().contains("no earlier record"));
    }
}
