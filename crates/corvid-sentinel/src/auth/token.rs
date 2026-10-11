//! The access tokens the sentinel signs as the OIDC issuer Stalwart's directory trusts.

use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use p256::ecdsa::signature::{Signer, Verifier};
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::webpush::random_bytes;

const SCOPE: &str = "openid profile";

/// Whom a token is for. Stalwart requires `corvid`, so a master token never opens mail.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Audience {
    Daily,
    Master,
}

impl Audience {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Daily => "corvid",
            Self::Master => "corvid-admin",
        }
    }

    pub fn lifetime_seconds(self) -> i64 {
        match self {
            Self::Daily => 15 * 60,
            Self::Master => 5 * 60,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Claims {
    pub iss: String,
    pub sub: String,
    pub aud: String,
    pub preferred_username: String,
    pub scope: String,
    pub iat: i64,
    pub exp: i64,
    pub jti: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub auth_time: Option<i64>,
}

/// Why a token was not accepted.
#[derive(Debug, PartialEq, Eq)]
pub struct Invalid(pub &'static str);

/// Why a master token was refused: it proves nothing, or it is genuine but not the master's.
#[derive(Debug, PartialEq, Eq)]
pub enum MasterRefusal {
    Invalid(Invalid),
    NotMaster,
}

#[derive(Deserialize)]
struct Header {
    alg: String,
    typ: String,
    kid: String,
}

pub struct Issuer {
    key: SigningKey,
    kid: String,
    url: String,
    /// Master token ids already used, with their expiry. A restart forgets them, which is safe:
    /// a token lives five minutes and a restart takes longer to come back.
    spent: Mutex<HashMap<String, i64>>,
}

impl Issuer {
    /// `url` is the issuer identifier Stalwart is configured with, with no trailing slash.
    pub fn new(key: SigningKey, url: String) -> Self {
        let (x, y) = coordinates(key.verifying_key());
        let kid = URL_SAFE_NO_PAD.encode(Sha256::digest(format!(
            r#"{{"crv":"P-256","kty":"EC","x":"{x}","y":"{y}"}}"#
        )));
        Self {
            key,
            kid,
            url,
            spent: Mutex::default(),
        }
    }

    pub fn kid(&self) -> &str {
        &self.kid
    }

    pub fn discovery(&self) -> Value {
        let url = &self.url;
        json!({
            "issuer": url,
            "jwks_uri": format!("{url}/jwks"),
            "userinfo_endpoint": format!("{url}/userinfo"),
            "token_endpoint": format!("{url}/token"),
            "authorization_endpoint": format!("{url}/authorize"),
        })
    }

    pub fn jwks(&self) -> Value {
        let (x, y) = coordinates(self.key.verifying_key());
        json!({"keys": [{
            "kty": "EC", "crv": "P-256", "alg": "ES256", "use": "sig",
            "kid": self.kid, "x": x, "y": y,
        }]})
    }

    /// An `at+jwt` for `username`, valid for the audience's lifetime from `now`. A master token
    /// follows a fresh assertion, so its `auth_time` is `now`.
    pub fn mint(&self, audience: Audience, subject: &str, username: &str, now: i64) -> String {
        let claims = Claims {
            iss: self.url.clone(),
            sub: subject.to_owned(),
            aud: audience.as_str().to_owned(),
            preferred_username: username.to_owned(),
            scope: SCOPE.to_owned(),
            iat: now,
            exp: now + audience.lifetime_seconds(),
            jti: URL_SAFE_NO_PAD.encode(random_bytes::<16>()),
            auth_time: (audience == Audience::Master).then_some(now),
        };
        let header = json!({"alg": "ES256", "typ": "at+jwt", "kid": self.kid});
        let signed = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(header.to_string()),
            URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).expect("claims serialize"))
        );
        let signature: Signature = self.key.sign(signed.as_bytes());
        format!("{signed}.{}", URL_SAFE_NO_PAD.encode(signature.to_bytes()))
    }

    /// The claims of a token this issuer signed for `audience` that has not expired at `now`.
    pub fn verify(&self, token: &str, audience: Audience, now: i64) -> Result<Claims, Invalid> {
        let (signed, signature) = token.rsplit_once('.').ok_or(Invalid("not a JWT"))?;
        let (header, payload) = signed.split_once('.').ok_or(Invalid("not a JWT"))?;
        let header: Header = decode(header)?;
        if header.alg != "ES256" || header.typ != "at+jwt" || header.kid != self.kid {
            return Err(Invalid("not an access token of this issuer"));
        }
        let signature = URL_SAFE_NO_PAD
            .decode(signature)
            .ok()
            .and_then(|bytes| Signature::from_slice(&bytes).ok())
            .ok_or(Invalid("bad signature encoding"))?;
        VerifyingKey::from(&self.key)
            .verify(signed.as_bytes(), &signature)
            .map_err(|_| Invalid("bad signature"))?;

        let claims: Claims = decode(payload)?;
        if claims.iss != self.url || claims.aud != audience.as_str() {
            return Err(Invalid("wrong issuer or audience"));
        }
        if claims.exp <= now {
            return Err(Invalid("expired"));
        }
        Ok(claims)
    }

    /// Accepts a master token for `username`, which is good for one request: its id is spent here.
    pub fn verify_master(
        &self,
        token: &str,
        username: &str,
        now: i64,
    ) -> Result<(), MasterRefusal> {
        let claims = self
            .verify(token, Audience::Master, now)
            .map_err(MasterRefusal::Invalid)?;
        let mut spent = self.spent.lock().unwrap_or_else(PoisonError::into_inner);
        spent.retain(|_, expires| *expires > now);
        if spent.insert(claims.jti, claims.exp).is_some() {
            return Err(MasterRefusal::Invalid(Invalid("already used")));
        }
        if claims.preferred_username == username {
            Ok(())
        } else {
            Err(MasterRefusal::NotMaster)
        }
    }
}

fn coordinates(key: &VerifyingKey) -> (String, String) {
    let point = key.to_sec1_point(false);
    let bytes = point.as_bytes();
    (
        URL_SAFE_NO_PAD.encode(&bytes[1..33]),
        URL_SAFE_NO_PAD.encode(&bytes[33..65]),
    )
}

fn decode<T: for<'de> Deserialize<'de>>(part: &str) -> Result<T, Invalid> {
    URL_SAFE_NO_PAD
        .decode(part)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .ok_or(Invalid("malformed"))
}
