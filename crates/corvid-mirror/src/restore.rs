use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::archive::Archive;
use crate::crypto::Identities;
use crate::target::Target;

const SCHEMA: u32 = 1;

/// Where a held message with no recorded mailbox lands. Only archives written before mailbox
/// tracking existed produce these.
const FALLBACK_MAILBOX: &str = "Restored";

/// Archive id to the id the target assigned it, so a rerun after a partial restore imports
/// nothing twice. A `Message-Id` cannot serve this: two distinct messages routinely share one
/// (a sent copy and its bounce back, a message crossing two mailing lists), so keying on it
/// would silently drop the second.
#[derive(Default, Serialize, Deserialize)]
struct Ledger {
    /// The account this ledger was built against. A ledger applied to a different target
    /// would otherwise report `already_present` for ids that were never sent there.
    target: Option<String>,
    imported: BTreeMap<String, String>,
}

corvid_store::pin_shape!(Ledger { target, imported });

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Restored {
    pub imported: usize,
    pub already_present: usize,
    /// Held, imported, with no `Message-Id` header. Informational: the audit below covers
    /// these the same as any other held message.
    pub unidentifiable: usize,
    /// Imported with a `Message-Id` the target already had on some other message.
    /// Informational only: restore dedups by its ledger, never by this.
    pub shares_a_message_id: usize,
    /// Ids the ledger's target id no longer resolves to a message on the target, whether
    /// never imported, or imported and since deleted there.
    pub missing: Vec<String>,
}

/// Imports every held, non-vanished message into `target`, creating mailboxes by path and
/// recording each one's target id in a ledger at `ledger_path`, saved after every import so a
/// crash loses at most the message in flight. Ends by asking the target which of the ledger's
/// ids it still has, the same "compare ids, not counts" posture as the mirror's own audit,
/// covering every held id whether or not it carries a usable `Message-Id`.
pub fn restore(
    root: &Path,
    ledger_path: &Path,
    target: &impl Target,
    identities: &Identities,
) -> io::Result<Restored> {
    let archive = Archive::open(root)?;
    let meta = archive.meta(identities)?;
    let mut ledger: Ledger = corvid_store::open(ledger_path, SCHEMA)?;
    let account_id = target.account_id();
    match &ledger.target {
        Some(expected) if *expected != account_id => {
            return Err(io::Error::other(format!(
                "{}: this ledger was built against {expected}, not {account_id}",
                ledger_path.display()
            )));
        }
        _ => ledger.target = Some(account_id),
    }

    let present_message_ids = target.message_ids()?;
    let mut mailbox_ids: BTreeMap<String, String> = BTreeMap::new();
    let mut restored = Restored::default();

    for id in archive.held_ids() {
        if archive.is_vanished(id) {
            continue;
        }
        if ledger.imported.contains_key(id) {
            restored.already_present += 1;
            continue;
        }
        let record = meta.get(id).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("{id}: held but its metadata was never recorded"),
            )
        })?;
        let plaintext = archive.read_plaintext(id, identities)?;
        match message_id(&plaintext) {
            None => restored.unidentifiable += 1,
            Some(msgid) if present_message_ids.contains(&msgid) => {
                restored.shares_a_message_id += 1;
            }
            Some(_) => {}
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
        let target_id = target.import(&blob_id, &ids, &record.received_at, &record.keywords)?;
        ledger.imported.insert(id.to_owned(), target_id);
        corvid_store::save(ledger_path, SCHEMA, &ledger)?;
        restored.imported += 1;
    }

    let target_ids: Vec<String> = ledger.imported.values().cloned().collect();
    let present_on_target = target.existing(&target_ids)?;
    restored.missing = archive
        .held_ids()
        .filter(|id| !archive.is_vanished(id))
        .filter(|id| {
            !ledger
                .imported
                .get(*id)
                .is_some_and(|target_id| present_on_target.contains(target_id))
        })
        .map(str::to_owned)
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
