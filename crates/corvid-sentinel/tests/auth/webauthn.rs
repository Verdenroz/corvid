use corvid_sentinel::auth::soft::{BACKUP_ELIGIBLE, Soft, USER_PRESENT, USER_VERIFIED};
use corvid_sentinel::auth::{Policy, Refusal, Registration, RelyingParty};

const RP_ID: &str = "rig.test";
const ORIGIN: &str = "android:apk-key-hash:abc";
const DESKTOP_ORIGIN: &str = "https://mail.rig.test:4443";
const CHALLENGE: &[u8] = b"a challenge of some length";
const OTHER_CHALLENGE: &[u8] = b"somebody else's challenge";

fn party() -> RelyingParty {
    RelyingParty::new(RP_ID, vec![ORIGIN.to_owned(), DESKTOP_ORIGIN.to_owned()])
}

fn soft() -> Soft {
    Soft::new(RP_ID, ORIGIN)
}

fn register(soft: &mut Soft, policy: Policy) -> Result<Registration, Refusal> {
    party().register(&soft.create(CHALLENGE), CHALLENGE, policy)
}

fn enrolled(soft: &mut Soft) -> Registration {
    register(soft, Policy::Synced).unwrap()
}

fn assert_with(
    soft: &mut Soft,
    registration: &Registration,
    stored_count: u32,
    policy: Policy,
) -> Result<u32, Refusal> {
    party().assert(
        &soft.get(CHALLENGE),
        &registration.key,
        stored_count,
        CHALLENGE,
        policy,
    )
}

#[test]
fn a_registration_yields_the_credential_id_key_and_counter() {
    let mut soft = soft();
    soft.sign_count = 7;

    let registration = enrolled(&mut soft);

    assert_eq!(registration.credential_id, soft.credential_id);
    assert_eq!(registration.key, soft.public_key());
    assert_eq!(registration.sign_count, 7);
}

#[test]
fn an_assertion_from_the_registered_key_verifies_and_reports_its_counter() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Ok(1)
    );
}

#[test]
fn an_assertion_from_the_desktop_origin_verifies() {
    let mut soft = Soft::new(RP_ID, DESKTOP_ORIGIN);
    let registration = enrolled(&mut soft);

    assert!(assert_with(&mut soft, &registration, 0, Policy::Synced).is_ok());
}

#[test]
fn a_synced_passkey_that_always_reports_zero_keeps_verifying() {
    let mut soft = soft();
    soft.frozen_counter = true;
    let registration = enrolled(&mut soft);

    for _ in 0..2 {
        assert_eq!(
            assert_with(&mut soft, &registration, 0, Policy::Synced),
            Ok(0)
        );
    }
}

#[test]
fn a_registration_for_another_rp_id_is_refused() {
    let mut soft = soft();
    soft.rp_id = "other.test".to_owned();

    assert_eq!(
        register(&mut soft, Policy::Synced).err(),
        Some(Refusal::RpIdHash)
    );
}

#[test]
fn an_assertion_for_another_rp_id_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.rp_id = "other.test".to_owned();

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::RpIdHash)
    );
}

#[test]
fn a_registration_from_an_origin_outside_the_allowed_set_is_refused() {
    let mut soft = soft();
    soft.origin = "android:apk-key-hash:other".to_owned();

    assert_eq!(
        register(&mut soft, Policy::Synced).err(),
        Some(Refusal::Origin)
    );
}

#[test]
fn an_assertion_from_an_origin_outside_the_allowed_set_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.origin = "https://evil.test".to_owned();

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::Origin)
    );
}

#[test]
fn a_cross_origin_assertion_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.cross_origin = true;

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::CrossOrigin)
    );
}

#[test]
fn a_registration_with_the_assertion_ceremony_type_is_refused() {
    let mut soft = soft();
    soft.client_data_type = Some("webauthn.get".to_owned());

    assert_eq!(
        register(&mut soft, Policy::Synced).err(),
        Some(Refusal::ClientDataType)
    );
}

#[test]
fn an_assertion_with_the_registration_ceremony_type_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.client_data_type = Some("webauthn.create".to_owned());

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::ClientDataType)
    );
}

#[test]
fn a_registration_answering_another_challenge_is_refused() {
    let mut soft = soft();
    soft.challenge = Some(OTHER_CHALLENGE.to_vec());

    assert_eq!(
        register(&mut soft, Policy::Synced).err(),
        Some(Refusal::Challenge)
    );
}

#[test]
fn an_assertion_answering_another_challenge_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.challenge = Some(OTHER_CHALLENGE.to_vec());

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::Challenge)
    );
}

#[test]
fn a_registration_without_user_verification_is_refused() {
    let mut soft = soft();
    soft.flags = USER_PRESENT;

    assert_eq!(
        register(&mut soft, Policy::Synced).err(),
        Some(Refusal::UserVerification)
    );
}

#[test]
fn an_assertion_without_user_verification_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.flags = USER_PRESENT;

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::UserVerification)
    );
}

#[test]
fn an_assertion_without_user_presence_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.flags = USER_VERIFIED;

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::UserPresence)
    );
}

#[test]
fn a_backup_eligible_credential_is_refused_for_a_device_bound_role() {
    let mut soft = soft();
    soft.flags |= BACKUP_ELIGIBLE;

    assert_eq!(
        register(&mut soft, Policy::DeviceBound).err(),
        Some(Refusal::NotDeviceBound)
    );
}

#[test]
fn a_backup_eligible_credential_is_accepted_for_a_synced_role() {
    let mut soft = soft();
    soft.flags |= BACKUP_ELIGIBLE;

    assert!(register(&mut soft, Policy::Synced).is_ok());
}

#[test]
fn a_master_assertion_that_turns_backup_eligible_is_refused() {
    let mut soft = soft();
    let registration = register(&mut soft, Policy::DeviceBound).unwrap();
    soft.flags |= BACKUP_ELIGIBLE;

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::DeviceBound),
        Err(Refusal::NotDeviceBound)
    );
}

#[test]
fn a_counter_that_goes_backwards_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);

    assert_eq!(
        assert_with(&mut soft, &registration, 5, Policy::Synced),
        Err(Refusal::CounterRegression)
    );
}

#[test]
fn a_counter_that_repeats_its_stored_value_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);

    assert_eq!(
        assert_with(&mut soft, &registration, 1, Policy::Synced),
        Err(Refusal::CounterRegression)
    );
}

#[test]
fn a_credential_key_other_than_es256_is_refused() {
    let mut soft = soft();
    soft.cose_algorithm = -257;

    assert_eq!(
        register(&mut soft, Policy::Synced).err(),
        Some(Refusal::Algorithm)
    );
}

#[test]
fn an_attestation_format_other_than_none_is_refused() {
    let mut soft = soft();
    soft.attestation_format = "packed".to_owned();

    assert_eq!(
        register(&mut soft, Policy::Synced).err(),
        Some(Refusal::Attestation)
    );
}

#[test]
fn a_tampered_signature_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    soft.corrupt_signature = true;

    assert_eq!(
        assert_with(&mut soft, &registration, 0, Policy::Synced),
        Err(Refusal::Signature)
    );
}

#[test]
fn an_assertion_signed_by_another_key_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    let mut intruder = Soft::new(RP_ID, ORIGIN);

    assert_eq!(
        assert_with(&mut intruder, &registration, 0, Policy::Synced),
        Err(Refusal::Signature)
    );
}

#[test]
fn an_assertion_whose_client_data_was_altered_after_signing_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    let mut response = soft.get(CHALLENGE);
    response.client_data_json.push(b' ');

    assert_eq!(
        party().assert(&response, &registration.key, 0, CHALLENGE, Policy::Synced),
        Err(Refusal::Signature)
    );
}

#[test]
fn authenticator_data_cut_short_is_refused() {
    let mut soft = soft();
    let registration = enrolled(&mut soft);
    let mut response = soft.get(CHALLENGE);
    response.authenticator_data.truncate(20);

    assert_eq!(
        party().assert(&response, &registration.key, 0, CHALLENGE, Policy::Synced),
        Err(Refusal::Malformed)
    );
}
