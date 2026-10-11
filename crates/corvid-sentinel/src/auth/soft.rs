//! A software authenticator that builds real WebAuthn bytes, for tests and the deploy rig. Each
//! public field is a way to make one part of a response wrong.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ciborium::Value;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use serde_json::json;
use sha2::{Digest, Sha256};

use super::webauthn::{ATTESTED, AssertionResponse, RegistrationResponse};
use crate::webpush::{random_bytes, random_secret};

pub use super::webauthn::{BACKUP_ELIGIBLE, USER_PRESENT, USER_VERIFIED};

pub fn signing_key() -> SigningKey {
    SigningKey::from(random_secret())
}

pub struct Soft {
    key: SigningKey,
    pub credential_id: Vec<u8>,
    pub rp_id: String,
    pub origin: String,
    pub flags: u8,
    pub sign_count: u32,
    /// Stay at zero, as a synced passkey does.
    pub frozen_counter: bool,
    pub client_data_type: Option<String>,
    pub challenge: Option<Vec<u8>>,
    pub cross_origin: bool,
    pub cose_algorithm: i64,
    pub attestation_format: String,
    pub corrupt_signature: bool,
}

impl Soft {
    pub fn new(rp_id: &str, origin: &str) -> Self {
        Self {
            key: signing_key(),
            credential_id: random_bytes::<16>().to_vec(),
            rp_id: rp_id.to_owned(),
            origin: origin.to_owned(),
            flags: USER_PRESENT | USER_VERIFIED,
            sign_count: 0,
            frozen_counter: false,
            client_data_type: None,
            challenge: None,
            cross_origin: false,
            cose_algorithm: -7,
            attestation_format: "none".to_owned(),
            corrupt_signature: false,
        }
    }

    /// The authenticator a previous run saved, from its `secret()`.
    pub fn restore(
        rp_id: &str,
        origin: &str,
        secret: &[u8],
        credential_id: Vec<u8>,
        sign_count: u32,
    ) -> Self {
        Self {
            key: SigningKey::from_slice(secret).expect("a saved key is a P-256 scalar"),
            credential_id,
            sign_count,
            ..Self::new(rp_id, origin)
        }
    }

    pub fn secret(&self) -> Vec<u8> {
        self.key.to_bytes().to_vec()
    }

    pub fn public_key(&self) -> VerifyingKey {
        *self.key.verifying_key()
    }

    pub fn create(&mut self, challenge: &[u8]) -> RegistrationResponse {
        let client_data_json = self.client_data("webauthn.create", challenge);
        let mut data = self.auth_data(self.flags | ATTESTED);
        data.extend([0; 16]);
        data.extend((self.credential_id.len() as u16).to_be_bytes());
        data.extend(&self.credential_id);
        data.extend(self.cose_key());
        let mut attestation_object = Vec::new();
        ciborium::into_writer(
            &Value::Map(vec![
                (
                    Value::Text("fmt".into()),
                    Value::Text(self.attestation_format.clone()),
                ),
                (Value::Text("attStmt".into()), Value::Map(Vec::new())),
                (Value::Text("authData".into()), Value::Bytes(data)),
            ]),
            &mut attestation_object,
        )
        .expect("a CBOR value serializes");
        RegistrationResponse {
            client_data_json,
            attestation_object,
        }
    }

    pub fn get(&mut self, challenge: &[u8]) -> AssertionResponse {
        if !self.frozen_counter {
            self.sign_count += 1;
        }
        let client_data_json = self.client_data("webauthn.get", challenge);
        let authenticator_data = self.auth_data(self.flags);
        let signed = [
            authenticator_data.as_slice(),
            Sha256::digest(&client_data_json).as_slice(),
        ]
        .concat();
        let signature: Signature = self.key.sign(&signed);
        let mut signature = signature.to_der().as_bytes().to_vec();
        if self.corrupt_signature {
            *signature.last_mut().expect("a signature has bytes") ^= 1;
        }
        AssertionResponse {
            client_data_json,
            authenticator_data,
            signature,
        }
    }

    fn client_data(&self, kind: &str, challenge: &[u8]) -> Vec<u8> {
        json!({
            "type": self.client_data_type.as_deref().unwrap_or(kind),
            "challenge": URL_SAFE_NO_PAD.encode(self.challenge.as_deref().unwrap_or(challenge)),
            "origin": self.origin,
            "crossOrigin": self.cross_origin,
        })
        .to_string()
        .into_bytes()
    }

    fn auth_data(&self, flags: u8) -> Vec<u8> {
        let mut data = Sha256::digest(&self.rp_id).to_vec();
        data.push(flags);
        data.extend(self.sign_count.to_be_bytes());
        data
    }

    fn cose_key(&self) -> Vec<u8> {
        let point = self.key.verifying_key().to_sec1_point(false);
        let bytes = point.as_bytes();
        let mut out = Vec::new();
        ciborium::into_writer(
            &Value::Map(vec![
                (Value::Integer(1.into()), Value::Integer(2.into())),
                (
                    Value::Integer(3.into()),
                    Value::Integer(self.cose_algorithm.into()),
                ),
                (Value::Integer((-1).into()), Value::Integer(1.into())),
                (
                    Value::Integer((-2).into()),
                    Value::Bytes(bytes[1..33].to_vec()),
                ),
                (
                    Value::Integer((-3).into()),
                    Value::Bytes(bytes[33..65].to_vec()),
                ),
            ]),
            &mut out,
        )
        .expect("a CBOR value serializes");
        out
    }
}
