use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ring::signature::{self, RsaPublicKeyComponents};
use serde::Deserialize;
use serde_json::Value;

const CLIENT_ID: &str = "corvid-admin";
const MAX_AGE_SECONDS: i64 = 300;
const CLOCK_SKEW_SECONDS: i64 = 60;

/// Why a master token was not accepted. `Invalid` is for a token that proves nothing, `NotMaster`
/// for a genuine token of someone who is not the admin, `Unreachable` for Pocket-ID not answering.
#[derive(Debug, PartialEq, Eq)]
pub enum Refusal {
    Invalid(&'static str),
    NotMaster,
    Unreachable,
}

pub struct Master {
    http: reqwest::Client,
    issuer: String,
    idp_url: String,
    admin_user: String,
}

#[derive(Deserialize)]
struct Header {
    alg: String,
    typ: Option<String>,
    kid: Option<String>,
}

#[derive(Deserialize)]
struct Claims {
    iss: String,
    sub: String,
    aud: Audience,
    exp: i64,
    iat: Option<i64>,
    auth_time: Option<i64>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum Audience {
    One(String),
    Many(Vec<String>),
}

impl Audience {
    fn contains(&self, client: &str) -> bool {
        match self {
            Self::One(one) => one == client,
            Self::Many(many) => many.iter().any(|aud| aud == client),
        }
    }
}

impl Master {
    pub fn new(issuer: String, idp_url: String, admin_user: String) -> Self {
        Self {
            http: reqwest::Client::new(),
            issuer,
            idp_url,
            admin_user,
        }
    }

    /// Accepts a Pocket-ID access token issued to `corvid-admin` in the last five minutes for the
    /// admin user. The signature is checked against the issuer's own key set, and the identity
    /// against its userinfo, so a token for any other user or client of the same issuer is refused.
    pub async fn verify(&self, token: &str, now: i64) -> Result<(), Refusal> {
        let (signed, signature) = token
            .rsplit_once('.')
            .ok_or(Refusal::Invalid("not a JWT"))?;
        let (header, payload) = signed
            .split_once('.')
            .ok_or(Refusal::Invalid("not a JWT"))?;
        let header: Header = decode(header)?;
        if header.alg != "RS256" || header.typ.as_deref() != Some("at+jwt") {
            return Err(Refusal::Invalid("not an RS256 access token"));
        }
        let keys = self
            .get(&format!("{}/.well-known/jwks.json", self.idp_url), None)
            .await?;
        let (n, e) =
            rsa_key(&keys, header.kid.as_deref()).ok_or(Refusal::Invalid("unknown key"))?;
        let signature = URL_SAFE_NO_PAD
            .decode(signature)
            .map_err(|_| Refusal::Invalid("bad signature encoding"))?;
        RsaPublicKeyComponents { n, e }
            .verify(
                &signature::RSA_PKCS1_2048_8192_SHA256,
                signed.as_bytes(),
                &signature,
            )
            .map_err(|_| Refusal::Invalid("bad signature"))?;

        let claims: Claims = decode(payload)?;
        self.check_claims(&claims, now)?;

        let userinfo = self
            .get(&format!("{}/api/oidc/userinfo", self.idp_url), Some(token))
            .await?;
        let name = userinfo.get("preferred_username").and_then(Value::as_str);
        let sub = userinfo.get("sub").and_then(Value::as_str);
        if name == Some(self.admin_user.as_str()) && sub == Some(claims.sub.as_str()) {
            Ok(())
        } else {
            Err(Refusal::NotMaster)
        }
    }

    fn check_claims(&self, claims: &Claims, now: i64) -> Result<(), Refusal> {
        if claims.iss != self.issuer {
            return Err(Refusal::Invalid("wrong issuer"));
        }
        if !claims.aud.contains(CLIENT_ID) {
            return Err(Refusal::Invalid("not issued to corvid-admin"));
        }
        if claims.exp <= now {
            return Err(Refusal::Invalid("expired"));
        }
        let authenticated = claims
            .auth_time
            .or(claims.iat)
            .ok_or(Refusal::Invalid("no time of sign-in"))?;
        if now - authenticated > MAX_AGE_SECONDS || authenticated - now > CLOCK_SKEW_SECONDS {
            return Err(Refusal::Invalid("signed in too long ago"));
        }
        Ok(())
    }

    async fn get(&self, url: &str, bearer: Option<&str>) -> Result<Value, Refusal> {
        let mut request = self.http.get(url);
        if let Some(bearer) = bearer {
            request = request.bearer_auth(bearer);
        }
        let response = request.send().await.map_err(|error| {
            tracing::warn!(%error, "Pocket-ID did not answer");
            Refusal::Unreachable
        })?;
        match response.status().as_u16() {
            200 => response.json().await.map_err(|_| Refusal::Unreachable),
            401 | 403 => Err(Refusal::Invalid("Pocket-ID refused the token")),
            _ => Err(Refusal::Unreachable),
        }
    }
}

fn decode<T: for<'de> Deserialize<'de>>(part: &str) -> Result<T, Refusal> {
    let bytes = URL_SAFE_NO_PAD
        .decode(part)
        .map_err(|_| Refusal::Invalid("bad encoding"))?;
    serde_json::from_slice(&bytes).map_err(|_| Refusal::Invalid("bad JSON"))
}

fn rsa_key(keys: &Value, kid: Option<&str>) -> Option<(Vec<u8>, Vec<u8>)> {
    let key = keys.get("keys")?.as_array()?.iter().find(|key| {
        key.get("kty").and_then(Value::as_str) == Some("RSA")
            && key.get("kid").and_then(Value::as_str) == kid
    })?;
    let part = |name| URL_SAFE_NO_PAD.decode(key.get(name)?.as_str()?).ok();
    Some((part("n")?, part("e")?))
}
