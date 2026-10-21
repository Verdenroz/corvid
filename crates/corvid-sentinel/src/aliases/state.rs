use std::collections::BTreeMap;
use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// Bump whenever `Ledger`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 1;

/// What Stalwart does not keep about an alias. An entry is written before the alias exists on the
/// server, so one with no alias there is an alias a create or a wipe lost.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub created_at: i64,
    pub disabled_at: Option<i64>,
}

/// Minted aliases by name.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Ledger {
    pub aliases: BTreeMap<String, Entry>,
}

impl Ledger {
    pub fn created_since(&self, since: i64) -> usize {
        self.aliases
            .values()
            .filter(|entry| entry.created_at > since)
            .count()
    }
}

corvid_store::pin_shape!(Entry {
    created_at,
    disabled_at
});
corvid_store::pin_shape!(Ledger { aliases });

pub fn open(path: &Path) -> io::Result<Ledger> {
    corvid_store::open(path, SCHEMA)
}

pub fn save(path: &Path, ledger: &Ledger) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, ledger)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_saved_ledger_opens_as_it_was_written() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("aliases.json");
        let mut ledger = Ledger::default();
        ledger.aliases.insert(
            "shop.abcdefgh".to_owned(),
            Entry {
                created_at: 100,
                disabled_at: Some(200),
            },
        );

        save(&path, &ledger).unwrap();

        assert_eq!(open(&path).unwrap(), ledger);
    }

    #[test]
    fn a_missing_file_opens_empty() {
        let dir = tempfile::tempdir().unwrap();

        assert_eq!(
            open(&dir.path().join("aliases.json")).unwrap(),
            Ledger::default()
        );
    }

    #[test]
    fn only_entries_created_after_the_cutoff_count_as_recent() {
        let entry = |created_at| Entry {
            created_at,
            disabled_at: None,
        };
        let ledger = Ledger {
            aliases: [("a".to_owned(), entry(10)), ("b".to_owned(), entry(20))].into(),
        };

        assert_eq!(ledger.created_since(10), 1);
    }
}
