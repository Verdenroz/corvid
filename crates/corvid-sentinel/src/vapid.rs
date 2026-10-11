//! The server's VAPID identity (RFC 8292): one P-256 key that signs the `Authorization` header
//! a push service checks before it accepts a push for an endpoint registered under that key.

use std::fs::{self, OpenOptions};
use std::io::{self, ErrorKind, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use p256::ecdsa::SigningKey;
use p256::ecdsa::signature::Signer;
use serde_json::json;

use crate::webpush::random_bytes;

const LIFETIME_SECONDS: u64 = 12 * 3600;

pub struct Vapid {
    key: SigningKey,
}

impl Vapid {
    /// Reads the key at `path`, creating it read-only on first use. It is never replaced: every
    /// registration made under the old key would stop being accepted.
    pub fn open(path: &Path) -> io::Result<Self> {
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o400)
            .open(path)
        {
            Ok(mut file) => file.write_all(&Self::generate().to_bytes())?,
            Err(error) if error.kind() == ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error),
        }
        SigningKey::from_slice(&fs::read(path)?)
            .map(|key| Self { key })
            .map_err(|error| io::Error::new(ErrorKind::InvalidData, error))
    }

    fn generate() -> SigningKey {
        loop {
            if let Ok(key) = SigningKey::from_slice(&random_bytes::<32>()) {
                return key;
            }
        }
    }

    /// The uncompressed point, base64url, as a browser or app registers it.
    pub fn public_key(&self) -> String {
        URL_SAFE_NO_PAD.encode(self.key.verifying_key().to_sec1_point(false).as_bytes())
    }

    /// The `Authorization` value for a push to `audience`, valid for 12 hours from `now`.
    pub fn authorization(&self, audience: &str, contact: &str, now: SystemTime) -> String {
        let header = URL_SAFE_NO_PAD.encode(r#"{"typ":"JWT","alg":"ES256"}"#);
        let expires = now
            .duration_since(UNIX_EPOCH)
            .expect("the clock is after 1970")
            .as_secs()
            + LIFETIME_SECONDS;
        let claims = URL_SAFE_NO_PAD.encode(
            json!({"aud": audience, "exp": expires, "sub": format!("mailto:{contact}")})
                .to_string(),
        );
        let signed = format!("{header}.{claims}");
        let signature: p256::ecdsa::Signature = self.key.sign(signed.as_bytes());
        format!(
            "vapid t={signed}.{}, k={}",
            URL_SAFE_NO_PAD.encode(signature.to_bytes()),
            self.public_key()
        )
    }
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;
    use std::time::Duration;

    use p256::ecdsa::signature::Verifier;
    use p256::ecdsa::{Signature, VerifyingKey};

    use super::*;

    fn decode(text: &str) -> Vec<u8> {
        URL_SAFE_NO_PAD.decode(text).unwrap()
    }

    #[test]
    fn the_authorization_is_an_es256_jwt_that_verifies_under_the_public_key() {
        let dir = tempfile::tempdir().unwrap();
        let vapid = Vapid::open(&dir.path().join("vapid.key")).unwrap();
        let now = UNIX_EPOCH + Duration::from_secs(1_800_000_000);

        let header = vapid.authorization("https://fcm.googleapis.com", "me@elsewhere.example", now);

        let (token, key) = header
            .strip_prefix("vapid t=")
            .unwrap()
            .split_once(", k=")
            .unwrap();
        assert_eq!(key, vapid.public_key());
        let (signed, signature) = token.rsplit_once('.').unwrap();
        let public = VerifyingKey::from_sec1_bytes(&decode(key)).unwrap();
        public
            .verify(
                signed.as_bytes(),
                &Signature::from_slice(&decode(signature)).unwrap(),
            )
            .unwrap();
        let (head, claims) = signed.split_once('.').unwrap();
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&decode(head)).unwrap(),
            json!({"typ": "JWT", "alg": "ES256"})
        );
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&decode(claims)).unwrap(),
            json!({"aud": "https://fcm.googleapis.com", "exp": 1_800_043_200u64,
                   "sub": "mailto:me@elsewhere.example"})
        );
    }

    #[test]
    fn the_key_is_made_once_read_only_and_reopens_to_the_same_identity() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("vapid.key");

        let first = Vapid::open(&path).unwrap().public_key();

        assert_eq!(Vapid::open(&path).unwrap().public_key(), first);
        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o400
        );
    }

    #[test]
    fn a_key_file_that_is_not_a_key_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("vapid.key");
        fs::write(&path, b"short").unwrap();

        assert_eq!(
            Vapid::open(&path).err().unwrap().kind(),
            ErrorKind::InvalidData
        );
    }
}
