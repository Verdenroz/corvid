use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use subtle::ConstantTimeEq;

/// Checks the `X-Signature` header Stalwart sends: standard base64 of an HMAC-SHA256 over the
/// raw request body, verified against a real server's output rather than assumed from docs.
pub fn verify(secret: &[u8], body: &[u8], header: Option<&str>) -> bool {
    let Some(header) = header else { return false };
    let mut mac = <Hmac<Sha256>>::new_from_slice(secret).expect("hmac accepts any key size");
    mac.update(body);
    let expected = STANDARD.encode(mac.finalize().into_bytes());
    bool::from(expected.as_bytes().ct_eq(header.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &[u8] = b"test-sentinel-hmac-secret-0123456789";

    fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!("tests/fixtures/{name}")).unwrap()
    }

    #[test]
    fn a_real_captured_signature_verifies() {
        let body = fixture("auth-success-primary-jmap.json");
        assert!(verify(
            SECRET,
            &body,
            Some("Nie35lfnNKNtCAJtO0zLzTQXFEcni1lggHKdReAc+ao=")
        ));
    }

    #[test]
    fn a_missing_header_is_rejected() {
        let body = fixture("auth-success-primary-jmap.json");
        assert!(!verify(SECRET, &body, None));
    }

    #[test]
    fn a_wrong_secret_is_rejected() {
        let body = fixture("auth-success-primary-jmap.json");
        assert!(!verify(
            b"a different secret",
            &body,
            Some("Nie35lfnNKNtCAJtO0zLzTQXFEcni1lggHKdReAc+ao=")
        ));
    }

    #[test]
    fn a_body_edited_after_signing_is_rejected() {
        let mut body = fixture("auth-success-primary-jmap.json");
        body.push(b' ');
        assert!(!verify(
            SECRET,
            &body,
            Some("Nie35lfnNKNtCAJtO0zLzTQXFEcni1lggHKdReAc+ao=")
        ));
    }
}
