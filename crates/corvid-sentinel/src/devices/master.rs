use std::collections::HashMap;
use std::sync::{PoisonError, RwLock};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ring::signature::{self, RsaPublicKeyComponents};
use serde::Deserialize;
use serde_json::Value;

const CLIENT_ID: &str = "corvid-admin";
const MAX_AGE_SECONDS: i64 = 300;
const CLOCK_SKEW_SECONDS: i64 = 60;
const KEY_SET_SECONDS: i64 = 3600;

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
    keys: RwLock<KeySet>,
}

type RsaParts = (Vec<u8>, Vec<u8>);

#[derive(Default)]
struct KeySet {
    fetched_at: i64,
    keys: HashMap<Option<String>, RsaParts>,
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
            keys: RwLock::default(),
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
        let (n, e) = self.key(header.kid.as_deref(), now).await?;
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

    /// The signing key for `kid`. The key set is fetched again when it is an hour old or lacks
    /// `kid`, so a rotated-out key stops verifying and a new one is picked up.
    async fn key(&self, kid: Option<&str>, now: i64) -> Result<RsaParts, Refusal> {
        let kid = kid.map(str::to_owned);
        if let Some(key) = self.cached(&kid, now) {
            return Ok(key);
        }
        let fetched = self
            .get(&format!("{}/.well-known/jwks.json", self.idp_url), None)
            .await?;
        let mut set = self.keys.write().unwrap_or_else(PoisonError::into_inner);
        *set = KeySet {
            fetched_at: now,
            keys: rsa_keys(&fetched),
        };
        set.keys
            .get(&kid)
            .cloned()
            .ok_or(Refusal::Invalid("unknown key"))
    }

    fn cached(&self, kid: &Option<String>, now: i64) -> Option<RsaParts> {
        let set = self.keys.read().unwrap_or_else(PoisonError::into_inner);
        if now - set.fetched_at >= KEY_SET_SECONDS {
            return None;
        }
        set.keys.get(kid).cloned()
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

fn rsa_keys(keys: &Value) -> HashMap<Option<String>, RsaParts> {
    let list = keys.get("keys").and_then(Value::as_array);
    list.into_iter()
        .flatten()
        .filter(|key| key.get("kty").and_then(Value::as_str) == Some("RSA"))
        .filter_map(|key| {
            let part = |name| URL_SAFE_NO_PAD.decode(key.get(name)?.as_str()?).ok();
            let kid = key.get("kid").and_then(Value::as_str).map(str::to_owned);
            Some((kid, (part("n")?, part("e")?)))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use axum::routing::get;
    use axum::{Json, Router};
    use serde_json::json;
    use tokio::net::TcpListener;

    use super::*;

    async fn serving(published: Arc<Mutex<Value>>) -> Master {
        let app = Router::new().route(
            "/.well-known/jwks.json",
            get(move || async move { Json(published.lock().unwrap().clone()) }),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Master::new(String::new(), url, String::new())
    }

    fn signed_in_at(iat: i64) -> Claims {
        serde_json::from_value(json!({
            "iss": "issuer", "sub": "admin", "aud": [CLIENT_ID], "exp": 1_000_000, "iat": iat
        }))
        .unwrap()
    }

    #[test]
    fn a_token_whose_sign_in_is_over_five_minutes_old_is_refused_however_long_it_is_valid() {
        let master = Master::new("issuer".to_owned(), String::new(), String::new());
        let now = 10_000;

        assert_eq!(
            master.check_claims(&signed_in_at(now - MAX_AGE_SECONDS), now),
            Ok(())
        );
        assert_eq!(
            master.check_claims(&signed_in_at(now - MAX_AGE_SECONDS - 1), now),
            Err(Refusal::Invalid("signed in too long ago"))
        );
    }

    fn set(kid: &str) -> Value {
        json!({"keys": [{"kty": "RSA", "kid": kid, "n": "AQAB", "e": "AQAB"}]})
    }

    #[tokio::test]
    async fn a_key_rotated_out_stops_verifying_once_the_cached_set_is_an_hour_old() {
        let published = Arc::new(Mutex::new(set("k1")));
        let master = serving(published.clone()).await;
        assert!(master.key(Some("k1"), 0).await.is_ok());

        *published.lock().unwrap() = set("k2");
        assert!(master.key(Some("k1"), KEY_SET_SECONDS - 1).await.is_ok());
        assert_eq!(
            master.key(Some("k1"), KEY_SET_SECONDS).await,
            Err(Refusal::Invalid("unknown key"))
        );
        assert!(master.key(Some("k2"), KEY_SET_SECONDS).await.is_ok());
    }
}
