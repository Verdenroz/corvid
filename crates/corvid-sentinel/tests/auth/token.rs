use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use corvid_sentinel::auth::soft::signing_key;
use corvid_sentinel::auth::{Audience, Invalid, Issuer, MasterRefusal};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

const URL: &str = "http://127.0.0.1:11333/internal/issuer";
const NOW: i64 = 1_800_000_000;
const MASTER: &str = "mailuser-admin";

fn issuer() -> Issuer {
    Issuer::new(signing_key(), URL.to_owned())
}

fn decode(part: &str) -> Value {
    serde_json::from_slice(&URL_SAFE_NO_PAD.decode(part).unwrap()).unwrap()
}

fn claims_of(token: &str) -> Value {
    decode(token.split('.').nth(1).unwrap())
}

#[test]
fn a_minted_token_verifies_against_the_published_key_set() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Daily, "handle", "mailuser", NOW);

    let jwks = issuer.jwks();
    let jwk = &jwks["keys"][0];
    let point = [
        &[4][..],
        &URL_SAFE_NO_PAD.decode(jwk["x"].as_str().unwrap()).unwrap(),
        &URL_SAFE_NO_PAD.decode(jwk["y"].as_str().unwrap()).unwrap(),
    ]
    .concat();
    let (signed, signature) = token.rsplit_once('.').unwrap();
    VerifyingKey::from_sec1_bytes(&point)
        .unwrap()
        .verify(
            signed.as_bytes(),
            &Signature::from_slice(&URL_SAFE_NO_PAD.decode(signature).unwrap()).unwrap(),
        )
        .unwrap();
    let header = decode(signed.split_once('.').unwrap().0);
    assert_eq!(header["kid"], jwk["kid"]);
    assert_eq!(header["alg"], "ES256");
    assert_eq!(header["typ"], "at+jwt");
    assert_eq!(jwk["crv"], "P-256");
}

#[test]
fn the_key_id_is_the_rfc_7638_thumbprint() {
    let jwks = issuer().jwks();
    let jwk = &jwks["keys"][0];
    let canonical = format!(
        r#"{{"crv":"P-256","kty":"EC","x":"{}","y":"{}"}}"#,
        jwk["x"].as_str().unwrap(),
        jwk["y"].as_str().unwrap()
    );

    assert_eq!(
        jwk["kid"].as_str().unwrap(),
        URL_SAFE_NO_PAD.encode(Sha256::digest(canonical))
    );
}

#[test]
fn a_daily_token_carries_the_claims_stalwart_reads() {
    let claims = claims_of(&issuer().mint(Audience::Daily, "handle", "mailuser", NOW));

    assert_eq!(claims["iss"], URL);
    assert_eq!(claims["aud"], "corvid");
    assert_eq!(claims["sub"], "handle");
    assert_eq!(claims["preferred_username"], "mailuser");
    assert_eq!(claims["scope"], "openid profile");
    assert_eq!(claims["iat"], NOW);
    assert_eq!(claims["exp"], NOW + 15 * 60);
    assert_eq!(claims.get("auth_time"), None);
}

#[test]
fn a_master_token_is_for_the_admin_audience_and_lasts_five_minutes() {
    let claims = claims_of(&issuer().mint(Audience::Master, "handle", MASTER, NOW));

    assert_eq!(claims["aud"], "corvid-admin");
    assert_eq!(claims["exp"], NOW + 5 * 60);
    assert_eq!(claims["auth_time"], NOW);
}

#[test]
fn every_token_has_its_own_id() {
    let issuer = issuer();

    let first = claims_of(&issuer.mint(Audience::Master, "handle", MASTER, NOW));
    let second = claims_of(&issuer.mint(Audience::Master, "handle", MASTER, NOW));

    assert_ne!(first["jti"], second["jti"]);
}

#[test]
fn the_discovery_document_lists_the_five_endpoints_stalwart_requires() {
    let document = issuer().discovery();

    assert_eq!(document["issuer"], URL);
    for field in [
        "jwks_uri",
        "userinfo_endpoint",
        "token_endpoint",
        "authorization_endpoint",
    ] {
        assert!(
            document[field].as_str().unwrap().starts_with(URL),
            "{field}"
        );
    }
}

#[test]
fn a_token_it_minted_verifies_for_its_audience() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Daily, "handle", "mailuser", NOW);

    let claims = issuer.verify(&token, Audience::Daily, NOW + 60).unwrap();

    assert_eq!(claims.preferred_username, "mailuser");
}

#[test]
fn a_master_token_is_refused_as_a_daily_one() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Master, "handle", MASTER, NOW);

    assert_eq!(
        issuer.verify(&token, Audience::Daily, NOW).err(),
        Some(Invalid("wrong issuer or audience"))
    );
}

#[test]
fn an_expired_token_is_refused() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Master, "handle", MASTER, NOW);

    assert_eq!(
        issuer.verify(&token, Audience::Master, NOW + 5 * 60).err(),
        Some(Invalid("expired"))
    );
}

#[test]
fn a_token_signed_by_another_key_is_refused() {
    let token = issuer().mint(Audience::Daily, "handle", "mailuser", NOW);

    assert_eq!(
        issuer().verify(&token, Audience::Daily, NOW).err(),
        Some(Invalid("not an access token of this issuer"))
    );
}

#[test]
fn a_token_with_altered_claims_is_refused() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Daily, "handle", "mailuser", NOW);
    let mut parts: Vec<_> = token.split('.').map(str::to_owned).collect();
    parts[1] = URL_SAFE_NO_PAD.encode(
        json!({"iss": URL, "sub": "handle", "aud": "corvid", "preferred_username": "someone",
               "scope": "openid profile", "iat": NOW, "exp": NOW + 900, "jti": "x"})
        .to_string(),
    );

    assert_eq!(
        issuer.verify(&parts.join("."), Audience::Daily, NOW).err(),
        Some(Invalid("bad signature"))
    );
}

#[test]
fn a_token_with_an_hmac_header_is_refused() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Daily, "handle", "mailuser", NOW);
    let (_, rest) = token.split_once('.').unwrap();
    let forged = format!(
        "{}.{rest}",
        URL_SAFE_NO_PAD.encode(r#"{"alg":"HS256","typ":"at+jwt","kid":"x"}"#)
    );

    assert_eq!(
        issuer.verify(&forged, Audience::Daily, NOW).err(),
        Some(Invalid("not an access token of this issuer"))
    );
}

#[test]
fn a_master_token_verifies_once_and_is_then_spent() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Master, "handle", MASTER, NOW);

    assert!(issuer.verify_master(&token, MASTER, NOW).is_ok());

    assert_eq!(
        issuer.verify_master(&token, MASTER, NOW).err(),
        Some(MasterRefusal::Invalid(Invalid("already used")))
    );
}

#[test]
fn a_spent_id_is_forgotten_once_its_token_has_expired() {
    let issuer = issuer();
    let spent = issuer.mint(Audience::Master, "handle", MASTER, NOW);
    issuer.verify_master(&spent, MASTER, NOW).unwrap();
    let later = NOW + 10 * 60;

    let fresh = issuer.mint(Audience::Master, "handle", MASTER, later);

    assert!(issuer.verify_master(&fresh, MASTER, later).is_ok());
    assert_eq!(
        issuer.verify_master(&spent, MASTER, later).err(),
        Some(MasterRefusal::Invalid(Invalid("expired")))
    );
}

#[test]
fn a_master_token_for_another_user_is_genuine_but_not_the_masters() {
    let issuer = issuer();
    let token = issuer.mint(Audience::Master, "handle", "someone-else", NOW);

    assert_eq!(
        issuer.verify_master(&token, MASTER, NOW).err(),
        Some(MasterRefusal::NotMaster)
    );
}
