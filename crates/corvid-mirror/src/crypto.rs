use std::io::{self, Read, Write};
use std::iter;
use std::path::Path;

/// A loaded set of identities, boxed since a file may hold more than one.
pub type Identities = Vec<Box<dyn age::Identity + Send + Sync>>;

pub fn parse_recipient(value: &str) -> io::Result<age::x25519::Recipient> {
    value.parse().map_err(|error| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{value:?} is not an age recipient: {error}"),
        )
    })
}

/// Reads an age identity file (`age-keygen` format: one `AGE-SECRET-KEY-1...` per line,
/// comments and blank lines ignored). Never called on the nightly sync path.
pub fn load_identities(path: &Path) -> io::Result<Identities> {
    age::IdentityFile::from_file(path.display().to_string())?
        .into_identities()
        .map_err(io::Error::other)
}

pub fn encrypt(recipient: &age::x25519::Recipient, plaintext: &[u8]) -> io::Result<Vec<u8>> {
    let encryptor = age::Encryptor::with_recipients(iter::once(recipient as &dyn age::Recipient))
        .map_err(io::Error::other)?;
    let mut ciphertext = Vec::new();
    let mut writer = encryptor.wrap_output(&mut ciphertext)?;
    writer.write_all(plaintext)?;
    writer.finish()?;
    Ok(ciphertext)
}

pub fn decrypt(identities: &Identities, ciphertext: &[u8]) -> io::Result<Vec<u8>> {
    let decryptor = age::Decryptor::new(ciphertext).map_err(io::Error::other)?;
    let mut reader = decryptor
        .decrypt(
            identities
                .iter()
                .map(|identity| identity.as_ref() as &dyn age::Identity),
        )
        .map_err(io::Error::other)?;
    let mut plaintext = Vec::new();
    reader.read_to_end(&mut plaintext)?;
    Ok(plaintext)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Published test vector from the age crate, not a secret used anywhere real.
    const TEST_SK: &str =
        "AGE-SECRET-KEY-1GQ9778VQXMMJVE8SK7J6VT8UJ4HDQAJUVSFCWCM02D8GEWQ72PVQ2Y5J33";
    const TEST_PK: &str = "age1t7rxyev2z3rw82stdlrrepyc39nvn86l5078zqkf5uasdy86jp6svpy7pa";

    fn identities() -> Identities {
        vec![Box::new(TEST_SK.parse::<age::x25519::Identity>().unwrap())]
    }

    #[test]
    fn a_message_encrypted_to_the_recipient_decrypts_with_its_identity() {
        let recipient = parse_recipient(TEST_PK).unwrap();
        let ciphertext = encrypt(&recipient, b"the body").unwrap();

        assert_eq!(decrypt(&identities(), &ciphertext).unwrap(), b"the body");
    }

    #[test]
    fn ciphertext_carries_no_plaintext_bytes() {
        let recipient = parse_recipient(TEST_PK).unwrap();
        let ciphertext = encrypt(&recipient, b"a secret marker").unwrap();

        assert!(!ciphertext.windows(6).any(|w| w == b"secret"));
    }

    #[test]
    fn a_string_that_is_not_bech32_is_refused() {
        assert!(parse_recipient("not-a-recipient").is_err());
    }
}
