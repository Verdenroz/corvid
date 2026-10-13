//! The relying-party side of WebAuthn, for ES256 credentials with `none` attestation.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ciborium::Value;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use serde::Deserialize;
use sha2::{Digest, Sha256};

use super::cose;

pub const USER_PRESENT: u8 = 0x01;
pub const USER_VERIFIED: u8 = 0x04;
pub const BACKUP_ELIGIBLE: u8 = 0x08;
const BACKED_UP: u8 = 0x10;
pub(super) const ATTESTED: u8 = 0x40;

const FIXED_LENGTH: usize = 37;
const AAGUID_LENGTH: usize = 16;
const MAX_CREDENTIAL_ID: usize = 1023;

/// Why a ceremony response was not accepted.
#[derive(Debug, PartialEq, Eq)]
pub enum Refusal {
    Malformed,
    ClientDataType,
    Challenge,
    Origin,
    CrossOrigin,
    RpIdHash,
    Attestation,
    UserPresence,
    UserVerification,
    Algorithm,
    Signature,
    CounterRegression,
}

pub struct RegistrationResponse {
    pub client_data_json: Vec<u8>,
    pub attestation_object: Vec<u8>,
}

pub struct AssertionResponse {
    pub client_data_json: Vec<u8>,
    pub authenticator_data: Vec<u8>,
    pub signature: Vec<u8>,
}

pub struct Registration {
    pub credential_id: Vec<u8>,
    pub key: VerifyingKey,
    pub sign_count: u32,
    pub backup_eligible: bool,
    pub aaguid: [u8; AAGUID_LENGTH],
}

pub struct RelyingParty {
    rp_id_hash: [u8; 32],
    origins: Vec<String>,
}

#[derive(Deserialize)]
struct ClientData {
    #[serde(rename = "type")]
    kind: String,
    challenge: String,
    origin: String,
    #[serde(rename = "crossOrigin", default)]
    cross_origin: bool,
}

struct AuthData<'a> {
    flags: u8,
    sign_count: u32,
    attested: &'a [u8],
}

impl RelyingParty {
    pub fn new(rp_id: &str, origins: Vec<String>) -> Self {
        Self {
            rp_id_hash: Sha256::digest(rp_id).into(),
            origins,
        }
    }

    pub fn register(
        &self,
        response: &RegistrationResponse,
        challenge: &[u8],
    ) -> Result<Registration, Refusal> {
        self.check_client_data(&response.client_data_json, "webauthn.create", challenge)?;
        let Value::Map(object) = ciborium::from_reader(response.attestation_object.as_slice())
            .map_err(|_| Refusal::Malformed)?
        else {
            return Err(Refusal::Malformed);
        };
        let field = |name: &str| {
            object
                .iter()
                .find(|(key, _)| key.as_text() == Some(name))
                .map(|(_, value)| value)
        };
        if field("fmt").and_then(Value::as_text) != Some("none") {
            return Err(Refusal::Attestation);
        }
        let bytes = field("authData")
            .and_then(Value::as_bytes)
            .ok_or(Refusal::Malformed)?;
        let data = self.auth_data(bytes)?;
        if data.flags & ATTESTED == 0 {
            return Err(Refusal::Malformed);
        }

        let aaguid = data
            .attested
            .get(..AAGUID_LENGTH)
            .ok_or(Refusal::Malformed)?;
        let rest = &data.attested[AAGUID_LENGTH..];
        let length = rest.get(..2).ok_or(Refusal::Malformed)?;
        let length = usize::from(u16::from_be_bytes([length[0], length[1]]));
        let credential_id = rest.get(2..2 + length).ok_or(Refusal::Malformed)?;
        if !(1..=MAX_CREDENTIAL_ID).contains(&length) {
            return Err(Refusal::Malformed);
        }
        let mut key = &rest[2 + length..];
        Ok(Registration {
            credential_id: credential_id.to_vec(),
            key: cose::read_key(&mut key)?,
            sign_count: data.sign_count,
            backup_eligible: data.flags & BACKUP_ELIGIBLE != 0,
            aaguid: aaguid.try_into().expect("sliced to the AAGUID length"),
        })
    }

    /// Verifies an assertion made with `key`, whose last accepted counter was `stored_count`,
    /// and answers with the counter it carried.
    pub fn assert(
        &self,
        response: &AssertionResponse,
        key: &VerifyingKey,
        stored_count: u32,
        challenge: &[u8],
    ) -> Result<u32, Refusal> {
        self.check_client_data(&response.client_data_json, "webauthn.get", challenge)?;
        let signed = [
            &response.authenticator_data,
            Sha256::digest(&response.client_data_json).as_slice(),
        ]
        .concat();
        let signature = Signature::from_der(&response.signature).map_err(|_| Refusal::Signature)?;
        key.verify(&signed, &signature)
            .map_err(|_| Refusal::Signature)?;
        let data = self.auth_data(&response.authenticator_data)?;
        // Synced passkeys report 0 forever, which is not a regression.
        if (data.sign_count != 0 || stored_count != 0) && data.sign_count <= stored_count {
            return Err(Refusal::CounterRegression);
        }
        Ok(data.sign_count)
    }

    fn check_client_data(&self, json: &[u8], kind: &str, challenge: &[u8]) -> Result<(), Refusal> {
        let data: ClientData = serde_json::from_slice(json).map_err(|_| Refusal::Malformed)?;
        if data.kind != kind {
            return Err(Refusal::ClientDataType);
        }
        if data.challenge != URL_SAFE_NO_PAD.encode(challenge) {
            return Err(Refusal::Challenge);
        }
        if !self.origins.contains(&data.origin) {
            return Err(Refusal::Origin);
        }
        if data.cross_origin {
            return Err(Refusal::CrossOrigin);
        }
        Ok(())
    }

    fn auth_data<'a>(&self, bytes: &'a [u8]) -> Result<AuthData<'a>, Refusal> {
        if bytes.len() < FIXED_LENGTH {
            return Err(Refusal::Malformed);
        }
        if bytes[..32] != self.rp_id_hash {
            return Err(Refusal::RpIdHash);
        }
        let flags = bytes[32];
        if flags & USER_PRESENT == 0 {
            return Err(Refusal::UserPresence);
        }
        if flags & USER_VERIFIED == 0 {
            return Err(Refusal::UserVerification);
        }
        if flags & BACKED_UP != 0 && flags & BACKUP_ELIGIBLE == 0 {
            return Err(Refusal::Malformed);
        }
        Ok(AuthData {
            flags,
            sign_count: u32::from_be_bytes([bytes[33], bytes[34], bytes[35], bytes[36]]),
            attested: &bytes[FIXED_LENGTH..],
        })
    }
}
