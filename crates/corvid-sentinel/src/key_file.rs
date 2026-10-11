use std::fs::{self, OpenOptions};
use std::io::{self, ErrorKind, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

use p256::ecdsa::SigningKey;

use crate::webpush::random_secret;

/// Reads the P-256 key at `path`, creating it read-only on first use. It is never replaced:
/// everything signed or registered under the old key would stop being accepted.
pub fn open(path: &Path) -> io::Result<SigningKey> {
    if !path.exists() {
        create(path)?;
    }
    SigningKey::from_slice(&fs::read(path)?)
        .map_err(|error| io::Error::new(ErrorKind::InvalidData, error))
}

fn create(path: &Path) -> io::Result<()> {
    let pending = path.with_extension("key.tmp");
    match fs::remove_file(&pending) {
        Err(error) if error.kind() != ErrorKind::NotFound => return Err(error),
        _ => {}
    }
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o400)
        .open(&pending)?;
    file.write_all(&SigningKey::from(random_secret()).to_bytes())?;
    file.sync_all()?;
    // A hard link fails when the key exists, and a reader never sees it half written.
    match fs::hard_link(&pending, path) {
        Err(error) if error.kind() != ErrorKind::AlreadyExists => return Err(error),
        _ => {}
    }
    fs::remove_file(&pending)
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use super::*;

    #[test]
    fn the_key_is_made_once_read_only_and_reopens_to_the_same_key() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("some.key");

        let first = open(&path).unwrap();

        assert_eq!(open(&path).unwrap().to_bytes(), first.to_bytes());
        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o400
        );
    }

    #[test]
    fn a_leftover_temporary_file_does_not_block_the_next_open() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("some.key");
        fs::write(dir.path().join("some.key.tmp"), b"half").unwrap();

        let key = open(&path).unwrap();

        assert_eq!(open(&path).unwrap().to_bytes(), key.to_bytes());
        assert!(!dir.path().join("some.key.tmp").exists());
    }

    #[test]
    fn a_key_file_that_is_not_a_key_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("some.key");
        fs::write(&path, b"short").unwrap();

        assert_eq!(open(&path).err().unwrap().kind(), ErrorKind::InvalidData);
    }
}
