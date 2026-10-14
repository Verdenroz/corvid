use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use subtle::ConstantTimeEq;

pub struct Signer {
    key: Vec<u8>,
}

impl Signer {
    pub fn new(key: impl Into<Vec<u8>>) -> Self {
        Self { key: key.into() }
    }

    pub fn mint(&self, url: &str) -> String {
        let payload = URL_SAFE_NO_PAD.encode(url);
        format!("{}/{}", self.tag(&payload), payload)
    }

    pub fn open(&self, signature: &str, payload: &str) -> Option<String> {
        let expected = self.tag(payload);
        if !bool::from(signature.as_bytes().ct_eq(expected.as_bytes())) {
            return None;
        }
        let decoded = URL_SAFE_NO_PAD.decode(payload).ok()?;
        String::from_utf8(decoded).ok()
    }

    fn tag(&self, payload: &str) -> String {
        let mut mac = <Hmac<Sha256>>::new_from_slice(&self.key).expect("hmac accepts any key size");
        mac.update(payload.as_bytes());
        URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
    }
}

/// Standard base64 of an HMAC-SHA256 over a request body, the `X-Signature` a caller sends.
pub fn sign_body(secret: &[u8], body: &[u8]) -> String {
    let mut mac = <Hmac<Sha256>>::new_from_slice(secret).expect("hmac accepts any key size");
    mac.update(body);
    STANDARD.encode(mac.finalize().into_bytes())
}

/// Whether `header` is the signature of `body` under `secret`, compared in constant time.
pub fn verify_body(secret: &[u8], body: &[u8], header: Option<&str>) -> bool {
    let Some(header) = header else { return false };
    let expected = sign_body(secret, body);
    bool::from(expected.as_bytes().ct_eq(header.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn signer() -> Signer {
        Signer::new(*b"a test key, not a real one")
    }

    #[test]
    fn round_trips_a_url() {
        let url = "https://example.net/pixel.gif?id=abc";
        let minted = signer().mint(url);
        let (signature, payload) = minted.split_once('/').unwrap();
        assert_eq!(signer().open(signature, payload).as_deref(), Some(url));
    }

    #[test]
    fn rejects_a_forged_signature() {
        let minted = signer().mint("https://example.net/pixel.gif");
        let (_, payload) = minted.split_once('/').unwrap();
        assert!(signer().open("Zm9yZ2Vk", payload).is_none());
    }

    #[test]
    fn rejects_a_payload_swapped_under_a_valid_signature() {
        let minted = signer().mint("https://example.net/one.gif");
        let (signature, _) = minted.split_once('/').unwrap();
        let other = URL_SAFE_NO_PAD.encode("https://evil.example/two.gif");
        assert!(signer().open(signature, &other).is_none());
    }

    #[test]
    fn a_different_key_does_not_open_it() {
        let minted = signer().mint("https://example.net/pixel.gif");
        let (signature, payload) = minted.split_once('/').unwrap();
        assert!(
            Signer::new(*b"a different key")
                .open(signature, payload)
                .is_none()
        );
    }
}
