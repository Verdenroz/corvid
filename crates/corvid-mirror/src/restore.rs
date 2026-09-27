use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use crate::archive::Archive;
use crate::crypto::Identities;
use crate::target::Target;

/// Where a held message with no recorded mailbox lands. Only archives written before mailbox
/// tracking existed produce these.
const FALLBACK_MAILBOX: &str = "Restored";

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Restored {
    pub imported: usize,
    pub already_present: usize,
    /// Held, imported, but with no `Message-Id` header to identify it by. Not covered by
    /// `missing`, and not deduplicated on a rerun.
    pub unidentifiable: usize,
    /// Ids the archive holds whose `Message-Id` did not turn up on the target after import.
    pub missing: Vec<String>,
}

/// Imports every held, non-vanished message into `target`, creating mailboxes by path and
/// skipping anything already there by `Message-Id`. Ends by asking the target again for what
/// it holds, the same "compare ids, not counts" posture as the mirror's own audit.
pub fn restore(root: &Path, target: &impl Target, identities: &Identities) -> io::Result<Restored> {
    let archive = Archive::open(root)?;
    let meta = archive.meta(identities)?;
    let mut present = target.message_ids()?;
    let mut mailbox_ids: BTreeMap<String, String> = BTreeMap::new();
    let mut restored = Restored::default();
    let mut expected: BTreeMap<String, String> = BTreeMap::new();

    for id in archive.held_ids() {
        if archive.is_vanished(id) {
            continue;
        }
        let record = meta.get(id).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("{id}: held but its metadata was never recorded"),
            )
        })?;
        let plaintext = archive.read_plaintext(id, identities)?;
        let msgid = message_id(&plaintext);

        if let Some(msgid) = &msgid {
            if present.contains(msgid) {
                restored.already_present += 1;
                expected.insert(id.to_owned(), msgid.clone());
                continue;
            }
        } else {
            restored.unidentifiable += 1;
        }

        let paths: BTreeSet<&str> = if record.mailboxes.is_empty() {
            std::iter::once(FALLBACK_MAILBOX).collect()
        } else {
            record.mailboxes.iter().map(String::as_str).collect()
        };
        let mut ids = BTreeSet::new();
        for path in paths {
            let mailbox_id = match mailbox_ids.get(path) {
                Some(id) => id.clone(),
                None => {
                    let id = target.ensure_mailbox(path)?;
                    mailbox_ids.insert(path.to_owned(), id.clone());
                    id
                }
            };
            ids.insert(mailbox_id);
        }

        let blob_id = target.upload(&plaintext)?;
        target.import(&blob_id, &ids, &record.received_at, &record.keywords)?;
        restored.imported += 1;
        if let Some(msgid) = msgid {
            present.insert(msgid.clone());
            expected.insert(id.to_owned(), msgid);
        }
    }

    let landed = target.message_ids()?;
    restored.missing = expected
        .into_iter()
        .filter(|(_, msgid)| !landed.contains(msgid))
        .map(|(id, _)| id)
        .collect();
    Ok(restored)
}

/// The unfolded value of the first `Message-Id` header, or `None` if the message has none.
/// Header lines fold onto a continuation line starting with whitespace, per RFC 5322 §2.2.3.
fn message_id(body: &[u8]) -> Option<String> {
    let text = String::from_utf8_lossy(body);
    let mut lines = text.lines().take_while(|line| !line.is_empty()).peekable();
    while let Some(line) = lines.next() {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        if !name.trim().eq_ignore_ascii_case("message-id") {
            continue;
        }
        let mut value = value.trim().to_owned();
        while let Some(continuation) = lines.next_if(|l| l.starts_with(' ') || l.starts_with('\t'))
        {
            value.push(' ');
            value.push_str(continuation.trim());
        }
        return Some(value);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::message_id;

    #[test]
    fn the_message_id_header_is_read_regardless_of_case() {
        let body = b"From: a@example.com\r\nMESSAGE-ID: <abc@example.com>\r\n\r\nbody";

        assert_eq!(message_id(body).as_deref(), Some("<abc@example.com>"));
    }

    #[test]
    fn a_folded_message_id_is_unfolded() {
        let body = b"Message-Id: <abc\r\n @example.com>\r\n\r\nbody";

        assert_eq!(message_id(body).as_deref(), Some("<abc @example.com>"));
    }

    #[test]
    fn a_message_with_no_message_id_has_none() {
        let body = b"From: a@example.com\r\n\r\nbody";

        assert_eq!(message_id(body), None);
    }
}
