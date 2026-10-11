//! Message encryption for Web Push (RFC 8291, `aes128gcm` from RFC 8188), so a push carries
//! nothing the relay or anyone between it and the device can read.

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes128Gcm, Nonce};
use hkdf::Hkdf;
use p256::ecdh::diffie_hellman;
use p256::elliptic_curve::sec1::ToSec1Point;
use p256::{PublicKey, SecretKey};
use ring::rand::{SecureRandom, SystemRandom};
use sha2::Sha256;

const RECORD_SIZE: u32 = 4096;
const LAST_RECORD: u8 = 2;

/// A device's push keys: its P-256 public key, uncompressed, and its 16-byte auth secret.
pub struct Receiver {
    public: PublicKey,
    auth: [u8; 16],
}

impl Receiver {
    /// Both arrive base64url-encoded from the device; anything that is not a valid key is `None`.
    pub fn new(p256dh: &[u8], auth: &[u8]) -> Option<Self> {
        Some(Self {
            public: PublicKey::from_sec1_bytes(p256dh).ok()?,
            auth: auth.try_into().ok()?,
        })
    }
}

/// One `aes128gcm` body for [`Receiver`], under a fresh sender key and salt.
pub fn encrypt(receiver: &Receiver, plaintext: &[u8]) -> Vec<u8> {
    let sender = loop {
        if let Ok(key) = SecretKey::from_slice(&random_bytes::<32>()) {
            break key;
        }
    };
    encrypt_with(receiver, plaintext, &sender, random_bytes())
}

pub(crate) fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0; N];
    SystemRandom::new()
        .fill(&mut bytes)
        .expect("the system has no randomness");
    bytes
}

fn encrypt_with(
    receiver: &Receiver,
    plaintext: &[u8],
    sender: &SecretKey,
    salt: [u8; 16],
) -> Vec<u8> {
    let sender_public = sender.public_key().to_sec1_point(false);
    let receiver_public = receiver.public.to_sec1_point(false);
    let shared = diffie_hellman(sender.to_nonzero_scalar(), receiver.public.as_affine());

    let mut key_info = b"WebPush: info\0".to_vec();
    key_info.extend_from_slice(receiver_public.as_bytes());
    key_info.extend_from_slice(sender_public.as_bytes());
    let mut ikm = [0; 32];
    Hkdf::<Sha256>::new(Some(&receiver.auth), shared.raw_secret_bytes())
        .expand(&key_info, &mut ikm)
        .expect("32 bytes is a valid HKDF length");

    let content = Hkdf::<Sha256>::new(Some(&salt), &ikm);
    let mut key = [0; 16];
    let mut nonce = [0; 12];
    content
        .expand(b"Content-Encoding: aes128gcm\0", &mut key)
        .expect("16 bytes is a valid HKDF length");
    content
        .expand(b"Content-Encoding: nonce\0", &mut nonce)
        .expect("12 bytes is a valid HKDF length");

    let mut record = plaintext.to_vec();
    record.push(LAST_RECORD);
    let sealed = Aes128Gcm::new(&key.into())
        .encrypt(&Nonce::from(nonce), record.as_slice())
        .expect("one record never exceeds the AES-GCM limit");

    let sender_public = sender_public.as_bytes();
    let mut body = salt.to_vec();
    body.extend_from_slice(&RECORD_SIZE.to_be_bytes());
    body.push(u8::try_from(sender_public.len()).expect("an uncompressed P-256 key is 65 bytes"));
    body.extend_from_slice(sender_public);
    body.extend_from_slice(&sealed);
    body
}

#[cfg(test)]
mod tests {
    use base64::Engine;
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;

    use super::*;

    fn b64(text: &str) -> Vec<u8> {
        URL_SAFE_NO_PAD.decode(text).unwrap()
    }

    #[test]
    fn the_rfc_8291_example_encrypts_to_its_published_result() {
        let receiver = Receiver::new(
            &b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            &b64("BTBZMqHH6r4Tts7J_aSIgg"),
        )
        .unwrap();
        let sender =
            SecretKey::from_slice(&b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")).unwrap();
        let salt: [u8; 16] = b64("DGv6ra1nlYgDCS1FRnbzlw").try_into().unwrap();

        let body = encrypt_with(
            &receiver,
            b"When I grow up, I want to be a watermelon",
            &sender,
            salt,
        );

        assert_eq!(
            URL_SAFE_NO_PAD.encode(body),
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
        );
    }

    #[test]
    fn a_key_that_is_not_a_p256_point_or_a_short_secret_is_refused() {
        let auth = b64("BTBZMqHH6r4Tts7J_aSIgg");
        assert!(Receiver::new(&[4; 65], &auth).is_none());
        let key = b64(
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
        );
        assert!(Receiver::new(&key, &auth[..8]).is_none());
    }

    #[test]
    fn two_pushes_of_the_same_text_differ() {
        let receiver = Receiver::new(
            &b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
            &b64("BTBZMqHH6r4Tts7J_aSIgg"),
        )
        .unwrap();
        assert_ne!(encrypt(&receiver, b"same"), encrypt(&receiver, b"same"));
    }
}
