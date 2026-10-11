//! The ES256 `none` attestation vector from WebAuthn Level 3 section 16.2, produced outside this
//! crate. It carries no UV flag, so a UV-requiring verifier refuses it after everything else
//! has parsed.

use corvid_sentinel::auth::{
    AssertionResponse, Policy, Refusal, RegistrationResponse, RelyingParty,
};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use sha2::{Digest, Sha256};

const ORIGIN: &str = "https://example.org";
const X: &str = "afefa16f97ca9b2d23eb86ccb64098d20db90856062eb249c33a9b672f26df61";
const Y: &str = "930a56b87a2fca66334b03458abf879717c12cc68ed73290af2e2664796b9220";

const REG_CHALLENGE: &str = "00c30fb78531c464d2b6771dab8d7b603c01162f2fa486bea70f283ae556e130";

const REG_CLIENT_DATA: &str = concat!(
    "7b2274797065223a22776562617574686e2e637265617465222c226368616c6c",
    "656e6765223a22414d4d507434557878475453746e6364713431375944774246",
    "6938767049612d7077386f4f755657345441222c226f726967696e223a226874",
    "7470733a2f2f6578616d706c652e6f7267222c2263726f73734f726967696e22",
    "3a66616c73652c22657874726144617461223a22636c69656e74446174614a53",
    "4f4e206d617920626520657874656e6465642077697468206164646974696f6e",
    "616c206669656c647320696e20746865206675747572652c2073756368206173",
    "20746869733a20426b5165446a646354427258426941774a544c453551227d"
);

const ATTESTATION: &str = concat!(
    "a363666d74646e6f6e656761747453746d74a068617574684461746158a4bfab",
    "c37432958b063360d3ad6461c9c4735ae7f8edd46592a5e0f01452b2e4b55900",
    "0000008446ccb9ab1db374750b2367ff6f3a1f0020f91f391db4c9b2fde0ea70",
    "189cba3fb63f579ba6122b33ad94ff3ec330084be4a5010203262001215820af",
    "efa16f97ca9b2d23eb86ccb64098d20db90856062eb249c33a9b672f26df6122",
    "5820930a56b87a2fca66334b03458abf879717c12cc68ed73290af2e2664796b",
    "9220"
);

const AUTH_CHALLENGE: &str = "39c0e7521417ba54d43e8dc95174f423dee9bf3cd804ff6d65c857c9abf4d408";

const AUTH_DATA: &str = concat!(
    "bfabc37432958b063360d3ad6461c9c4735ae7f8edd46592a5e0f01452b2e4b5",
    "1900000000"
);

const AUTH_CLIENT_DATA: &str = concat!(
    "7b2274797065223a22776562617574686e2e676574222c226368616c6c656e67",
    "65223a224f63446e55685158756c5455506f334a5558543049393770767a7a59",
    "425039745a63685879617630314167222c226f726967696e223a226874747073",
    "3a2f2f6578616d706c652e6f7267222c2263726f73734f726967696e223a6661",
    "6c73657d"
);

const SIGNATURE: &str = concat!(
    "3046022100f50a4e2e4409249c4a853ba361282f09841df4dd4547a13a877802",
    "18deffcd380221008480ac0f0b93538174f575bf11a1dd5d78c6e486013f9372",
    "95ea13653e331e87"
);

const CREDENTIAL_ID: &str = "f91f391db4c9b2fde0ea70189cba3fb63f579ba6122b33ad94ff3ec330084be4";

const AAGUID: &str = "8446ccb9ab1db374750b2367ff6f3a1f";

fn party() -> RelyingParty {
    RelyingParty::new("example.org", vec![ORIGIN.to_owned()])
}

fn bytes(hex: &str) -> Vec<u8> {
    hex::decode(hex).unwrap()
}

fn key() -> VerifyingKey {
    VerifyingKey::from_sec1_bytes(&bytes(&format!("04{X}{Y}"))).unwrap()
}

fn registration(user_verified: bool) -> RegistrationResponse {
    let mut attestation_object = bytes(ATTESTATION);
    if user_verified {
        let rp_id_hash = Sha256::digest("example.org");
        let at = attestation_object
            .windows(32)
            .position(|window| window == rp_id_hash.as_slice())
            .unwrap();
        attestation_object[at + 32] |= 0x04;
    }
    RegistrationResponse {
        client_data_json: bytes(REG_CLIENT_DATA),
        attestation_object,
    }
}

fn assertion() -> AssertionResponse {
    AssertionResponse {
        client_data_json: bytes(AUTH_CLIENT_DATA),
        authenticator_data: bytes(AUTH_DATA),
        signature: bytes(SIGNATURE),
    }
}

#[test]
fn the_published_registration_is_read_down_to_the_missing_user_verification() {
    assert_eq!(
        party()
            .register(&registration(false), &bytes(REG_CHALLENGE), Policy::Synced)
            .err(),
        Some(Refusal::UserVerification)
    );
}

#[test]
fn the_published_registration_yields_its_credential_key_and_flags_once_verified_is_set() {
    let registration = party()
        .register(&registration(true), &bytes(REG_CHALLENGE), Policy::Synced)
        .unwrap();

    assert_eq!(registration.credential_id, bytes(CREDENTIAL_ID));
    assert_eq!(registration.aaguid.to_vec(), bytes(AAGUID));
    assert_eq!(registration.key, key());
    assert_eq!(registration.sign_count, 0);
    assert!(registration.backup_eligible);
}

#[test]
fn the_published_registration_is_backup_eligible_so_a_device_bound_role_refuses_it() {
    assert_eq!(
        party()
            .register(
                &registration(true),
                &bytes(REG_CHALLENGE),
                Policy::DeviceBound
            )
            .err(),
        Some(Refusal::NotDeviceBound)
    );
}

#[test]
fn the_published_signature_verifies_over_authenticator_data_and_the_client_data_hash() {
    let response = assertion();
    let signed = [
        response.authenticator_data.as_slice(),
        Sha256::digest(&response.client_data_json).as_slice(),
    ]
    .concat();

    key()
        .verify(&signed, &Signature::from_der(&response.signature).unwrap())
        .unwrap();
}

#[test]
fn the_published_assertion_passes_the_signature_and_stops_at_the_missing_user_verification() {
    assert_eq!(
        party().assert(
            &assertion(),
            &key(),
            0,
            &bytes(AUTH_CHALLENGE),
            Policy::Synced
        ),
        Err(Refusal::UserVerification)
    );
}

#[test]
fn the_published_assertion_with_its_client_data_altered_fails_the_signature() {
    let mut response = assertion();
    response.client_data_json.push(b' ');

    assert_eq!(
        party().assert(&response, &key(), 0, &bytes(AUTH_CHALLENGE), Policy::Synced),
        Err(Refusal::Signature)
    );
}
