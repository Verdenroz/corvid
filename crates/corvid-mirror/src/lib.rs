mod archive;
pub mod crypto;
mod jmap;
mod mailpath;
mod meta;
mod migrate;
mod restore;
pub mod source;
mod target;

use std::io;
use std::path::Path;

pub use archive::Archive;
pub use crypto::Identities;
pub use jmap::Mailbox;
pub use meta::Meta;
pub use migrate::{Migrated, encrypt_archive};
pub use restore::{Restored, restore};
pub use source::{Changes, Listing, Record, Source};
pub use target::Target;

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Audit {
    /// Held by the server, absent here.
    pub missing: Vec<String>,
    /// Held here, no longer on the server. Newly noticed deletions are recorded, not acted on:
    /// the body stays so the user can decide.
    pub vanished: Vec<String>,
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Summary {
    pub fetched: usize,
    pub already_held: usize,
    /// Listed, then not returned when the bodies were asked for.
    ///
    /// Ordinarily a message deleted between the listing and the fetch. A transient failure
    /// looks identical and self-corrects, since the next audit finds it missing.
    pub unavailable: Vec<String>,
}

/// Pulls everything new since the last run.
///
/// A first run takes the whole mailbox, unlike the labeler, which adopts the position and
/// labels nothing historical.
pub fn sync(
    root: &Path,
    source: &impl Source,
    recipient: &age::x25519::Recipient,
) -> io::Result<Summary> {
    let mut archive = Archive::open(root)?;
    let mut summary = Summary::default();

    let (ids, cursor) = match archive.cursor() {
        Some(cursor) => {
            let changes = source.changes(Some(cursor))?;
            let mut ids = changes.created;
            ids.extend(changes.updated);
            ids.sort();
            ids.dedup();
            (ids, changes.new_state)
        }
        // Position BEFORE listing: a message arriving between the two then shows up as created
        // next run. The other order would lose it for good.
        None => {
            let cursor = source.state()?;
            (listing(source)?.ids, cursor)
        }
    };

    let records = source.records(&ids)?;
    let returned: std::collections::BTreeSet<&String> = records.iter().map(|r| &r.id).collect();
    summary.unavailable = ids
        .iter()
        .filter(|id| !returned.contains(id))
        .cloned()
        .collect();

    for record in records {
        if archive.holds(&record.id) {
            archive.note_seen(&record.id, &record.keywords, &record.mailboxes);
            summary.already_held += 1;
            continue;
        }
        let body = source.blob(&record.blob_id)?;
        // A digest of the stored bytes would accept a truncated download; the size is asserted
        // independently by the server.
        if body.len() != record.size {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!(
                    "{}: the server says {} octets and {} arrived",
                    record.id,
                    record.size,
                    body.len()
                ),
            ));
        }
        archive.store(&record, &body, recipient)?;
        summary.fetched += 1;
    }

    archive.set_cursor(cursor);
    archive.save(recipient)?;
    Ok(summary)
}

/// What the archive is missing, and what the server no longer has.
///
/// The sync path cannot notice an absence, since a message that never arrives leaves no trace.
///
/// **Not covered:** a message delivered and deleted between two runs. The change feed collapses
/// a create and a destroy in one window to nothing, and this asks only what the server holds
/// now. The guarantee is *every message that survived until a run*; the stronger claim needs
/// capture at arrival, which puts a spool on the delivery path.
pub fn audit(root: &Path, source: &impl Source) -> io::Result<Audit> {
    let mut archive = Archive::open(root)?;
    let present = listing(source)?;
    let missing = archive
        .missing_from(&present.ids)
        .into_iter()
        .cloned()
        .collect();
    let vanished = archive.note_vanished(&present.ids);
    archive.save_index()?;
    Ok(Audit { missing, vanished })
}

/// Lists every id, refusing one that comes back short of the server's own count.
///
/// One-directional: anchored paging can return ids deleted while it ran, so *more* than the
/// total is ordinary churn. Fewer means something live was never listed.
///
/// A message arriving during the listing also leaves it short, but a second listing includes
/// it. A capped response stays short, so this refuses only a listing that is short twice.
fn listing(source: &impl Source) -> io::Result<Listing> {
    let first = source.all_ids()?;
    if first.ids.len() >= first.total {
        return Ok(first);
    }
    let second = source.all_ids()?;
    if second.ids.len() >= second.total {
        return Ok(second);
    }
    Err(io::Error::new(
        io::ErrorKind::InvalidData,
        format!(
            "the server listed {} of {} messages twice over; auditing a prefix would report success",
            second.ids.len(),
            second.total
        ),
    ))
}

/// Rehashes every held ciphertext. Needs no server and no identity.
pub fn verify(root: &Path) -> io::Result<Vec<String>> {
    Archive::open(root)?.verify()
}

/// Decrypts every held body and checks it against the plaintext digest and size recorded at
/// fetch time. Needs no server, but does need the identity, unlike the nightly `verify`.
pub fn verify_deep(root: &Path, identities: &Identities) -> io::Result<Vec<String>> {
    Archive::open(root)?.verify_deep(identities)
}
