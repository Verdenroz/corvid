use std::io;
use std::path::Path;

use crate::Ingest;

/// Bump this whenever the persisted shape OR the meaning of a persisted number changes.
///
/// A meaning change still parses. A new `Abstain` variant, a threshold in `Prior` or the
/// census's sampling rate leaves old files deserializing cleanly, and their counts pool with new
/// ones computed under a different rule: no error, valid checksum, wrong numbers. Those values
/// are pinned by tests where they are defined, which fail and name this constant.
const SCHEMA: u32 = 4;

/// A missing file is a first run; anything else unreadable is fatal. See [`corvid_store::open`].
pub fn open(path: &Path) -> io::Result<Ingest> {
    corvid_store::open(path, SCHEMA)
}

pub fn load(path: &Path) -> io::Result<Option<Ingest>> {
    corvid_store::load(path, SCHEMA)
}

corvid_store::pin_shape!(Ingest {
    prior,
    census,
    learned,
    cold_started,
    counted,
    sightings,
    cursor,
    corrections,
    repeats,
    writes,
    unchanged
});

pub fn save(path: &Path, ingest: &Ingest) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, ingest)
}

#[cfg(test)]
mod tests {
    use corvid_label::Abstain;

    /// Pins each abstain reason's wire name to `SCHEMA` at test-compile time.
    ///
    /// The match is exhaustive, so a new reason forces a decision here; the assertion catches a
    /// `serde(rename)`, which the match cannot see. The successor in each arm enumerates reasons
    /// without a second list. An arm pointing at `None` too early goes undetected; a cycle is
    /// caught. A saved fixture would catch none of this, since old data never names a new variant.
    fn pinned(reason: Abstain) -> (&'static str, Option<Abstain>) {
        match reason {
            Abstain::UnseenSender => ("UnseenSender", Some(Abstain::TooFewMessages)),
            Abstain::TooFewMessages => ("TooFewMessages", Some(Abstain::TooFewToWithhold)),
            Abstain::TooFewToWithhold => ("TooFewToWithhold", Some(Abstain::Ambiguous)),
            Abstain::Ambiguous => ("Ambiguous", None),
        }
    }

    fn every_reason() -> Vec<Abstain> {
        let mut reasons = vec![Abstain::UnseenSender];
        while let Some(next) = pinned(*reasons.last().unwrap()).1 {
            assert!(!reasons.contains(&next), "the successor chain loops");
            reasons.push(next);
        }
        reasons
    }

    #[test]
    fn every_abstain_reason_is_pinned_to_the_schema() {
        for reason in every_reason() {
            let written = serde_json::to_string(&reason).unwrap();
            assert_eq!(written, format!("\"{}\"", pinned(reason).0));
        }
    }
}
