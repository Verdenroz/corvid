//! One implementation of the state file envelope: checksum, schema line, atomic write.
//!
//! **It owns the envelope; each consumer owns pinning its own payload.** This crate enforces
//! that a schema number is supplied and matched, and nothing more. It cannot notice that a
//! consumer's persisted shape changed, so every consumer needs its own compile-time pin over
//! the fields and variants it stores (see `pin_shape!`).
//!
//! The schema number is a parameter rather than a constant here, so one consumer bumping its
//! version cannot invalidate another's files.

use std::fs;
use std::io::{self, Write};
use std::path::Path;

use serde::Serialize;
use serde::de::DeserializeOwned;
use sha2::{Digest, Sha256};

/// The sanctioned way to start: a missing file is a first run, and anything else that cannot
/// be read is fatal.
///
/// Use this rather than `load(..).unwrap_or_default()` on the `Result`: that treats a corrupt
/// or foreign-schema file as a fresh start, which looks exactly like a clean first run.
pub fn open<T: DeserializeOwned + Default>(path: &Path, schema: u32) -> io::Result<T> {
    Ok(load(path, schema)?.unwrap_or_default())
}

/// Like `open`, but a file written by schema `previous` is read as `Old` and converted instead of
/// refused. Any other mismatch, or a corrupt file, still fails with the current schema's error.
///
/// The converted state is written back by the caller's next `save`, which is when the file moves
/// to the new schema.
pub fn open_migrating<T, Old>(
    path: &Path,
    schema: u32,
    previous: u32,
    migrate: impl FnOnce(Old) -> T,
) -> io::Result<T>
where
    T: DeserializeOwned + Default,
    Old: DeserializeOwned,
{
    match load(path, schema) {
        Ok(state) => Ok(state.unwrap_or_default()),
        Err(error) if error.kind() == io::ErrorKind::InvalidData => match load(path, previous) {
            Ok(Some(old)) => Ok(migrate(old)),
            _ => Err(error),
        },
        Err(error) => Err(error),
    }
}

/// Reads saved state, rejecting a file that does not match its own checksum or was written by
/// a different schema.
///
/// The checksum catches a partially written file, which would otherwise pass downstream: the
/// server accepts a truncated cursor as a valid one.
pub fn load<T: DeserializeOwned>(path: &Path, schema: u32) -> io::Result<Option<T>> {
    let contents = match fs::read_to_string(path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let (checksum, body) = contents
        .split_once('\n')
        .ok_or_else(|| invalid("state file has no checksum"))?;
    if checksum != digest(body) {
        return Err(invalid(
            "state file does not match its checksum; it was written partially",
        ));
    }
    let (version, payload) = body
        .split_once('\n')
        .ok_or_else(|| invalid("state file has no schema version"))?;
    if version.parse::<u32>().ok() != Some(schema) {
        return Err(invalid("state file was written by a different schema"));
    }
    serde_json::from_str(payload)
        .map(Some)
        .map_err(io::Error::other)
}

/// Writes state so that a crash leaves either the previous file or the new one, never half of
/// either.
pub fn save<T: Serialize>(path: &Path, schema: u32, value: &T) -> io::Result<()> {
    let payload = serde_json::to_string(value).map_err(io::Error::other)?;
    let body = format!("{schema}\n{payload}");
    let temporary = path.with_extension("tmp");
    let mut file = fs::File::create(&temporary)?;
    writeln!(file, "{}", digest(&body))?;
    file.write_all(body.as_bytes())?;
    file.sync_all()?;
    fs::rename(&temporary, path)?;
    // The rename is directory metadata, and is not durable until the directory is synced.
    if let Some(parent) = path.parent() {
        fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}

/// Pins a persisted struct's fields, so adding one stops the crate compiling.
///
/// Write it beside the `save` call rather than in a test module. The expansion is an ordinary
/// item, so a new field fails `cargo build`, not only `cargo test`.
///
/// It cannot force adoption, only make it one line. Once adopted it cannot be quietly weakened:
/// the `..` the compiler suggests for a missing field is rejected by the macro's grammar.
///
/// ```
/// # use corvid_store::pin_shape;
/// #[derive(serde::Serialize)]
/// struct Ledger { cursor: Option<String>, held: u64 }
/// pin_shape!(Ledger { cursor, held });
/// ```
#[macro_export]
macro_rules! pin_shape {
    ($shape:path { $($field:ident),* $(,)? }) => {
        const _: () = {
            // Rebuilt as well as destructured, so even a `..` in the pattern would leave the
            // constructor a field short.
            #[allow(dead_code)]
            fn pinned(value: $shape) -> $shape {
                let $shape { $($field),* } = value;
                $shape { $($field),* }
            }
        };
    };
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

fn digest(payload: &str) -> String {
    hex::encode(Sha256::digest(payload.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default, PartialEq, Eq, Debug, serde::Serialize, serde::Deserialize)]
    struct Held {
        cursor: Option<String>,
        count: u64,
    }

    fn scratch() -> (tempfile::TempDir, std::path::PathBuf) {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("state");
        (directory, path)
    }

    #[test]
    fn round_trips() {
        let (_dir, path) = scratch();
        let held = Held {
            cursor: Some("s1".into()),
            count: 3,
        };
        save(&path, 1, &held).unwrap();

        assert_eq!(load::<Held>(&path, 1).unwrap(), Some(held));
    }

    #[test]
    fn a_truncated_file_is_refused() {
        let (_dir, path) = scratch();
        save(&path, 1, &Held::default()).unwrap();
        let whole = fs::read_to_string(&path).unwrap();
        fs::write(&path, &whole[..whole.len() - 5]).unwrap();

        assert!(load::<Held>(&path, 1).is_err());
    }

    #[test]
    fn another_schema_is_refused_in_both_directions() {
        let (_dir, path) = scratch();
        save(&path, 2, &Held::default()).unwrap();

        assert!(
            load::<Held>(&path, 1).is_err(),
            "an older reader must refuse a newer file"
        );
        assert!(
            load::<Held>(&path, 3).is_err(),
            "and a newer reader an older file"
        );
    }

    #[derive(Default, PartialEq, Eq, Debug, serde::Serialize, serde::Deserialize)]
    struct Grown {
        count: u64,
        label: String,
    }

    fn grow(old: Held) -> Grown {
        Grown {
            count: old.count,
            label: "migrated".into(),
        }
    }

    #[test]
    fn the_previous_schema_is_migrated_not_refused() {
        let (_dir, path) = scratch();
        save(
            &path,
            1,
            &Held {
                cursor: None,
                count: 7,
            },
        )
        .unwrap();

        let grown: Grown = open_migrating(&path, 2, 1, grow).unwrap();

        assert_eq!(
            grown,
            Grown {
                count: 7,
                label: "migrated".into()
            }
        );
    }

    #[test]
    fn the_current_schema_loads_without_migrating() {
        let (_dir, path) = scratch();
        let kept = Grown {
            count: 2,
            label: "kept".into(),
        };
        save(&path, 2, &kept).unwrap();

        assert_eq!(open_migrating(&path, 2, 1, grow).unwrap(), kept);
    }

    #[test]
    fn a_schema_two_behind_is_still_refused() {
        let (_dir, path) = scratch();
        save(&path, 1, &Held::default()).unwrap();

        assert!(open_migrating::<Grown, Held>(&path, 3, 2, grow).is_err());
    }

    #[test]
    fn a_corrupt_file_is_not_migrated() {
        let (_dir, path) = scratch();
        save(&path, 1, &Held::default()).unwrap();
        let whole = fs::read_to_string(&path).unwrap();
        fs::write(&path, &whole[..whole.len() - 5]).unwrap();

        assert!(open_migrating::<Grown, Held>(&path, 2, 1, grow).is_err());
    }

    #[test]
    fn a_missing_file_is_a_first_run() {
        let (_dir, path) = scratch();

        assert_eq!(open::<Held>(&path, 1).unwrap(), Held::default());
    }

    #[test]
    fn a_corrupt_file_stops_the_run_rather_than_starting_over() {
        let (_dir, path) = scratch();
        save(
            &path,
            1,
            &Held {
                cursor: Some("s1".into()),
                count: 9,
            },
        )
        .unwrap();
        let whole = fs::read_to_string(&path).unwrap();
        fs::write(&path, &whole[..whole.len() - 5]).unwrap();

        assert!(open::<Held>(&path, 1).is_err());
    }
}
