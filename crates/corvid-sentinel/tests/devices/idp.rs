use std::sync::{Arc, Mutex};

use axum::Json;
use axum::Router;
use axum::extract::State;
use axum::routing::get;
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use ring::rand::SystemRandom;
use ring::rsa::PublicKeyComponents;
use ring::signature::{RSA_PKCS1_SHA256, RsaKeyPair};
use serde_json::{Value, json};
use tokio::net::TcpListener;

const KEY: &[u8] = include_bytes!("../fixtures/rsa-test.der");

pub const ISSUER: &str = "https://idp.rig.test:4443";
pub const SUBJECT: &str = "subject-1";

/// A stand-in for Pocket-ID: it serves the key set for a fixed test key and userinfo for
/// whichever username a test sets.
pub struct Idp {
    pub url: String,
    username: Arc<Mutex<String>>,
}

struct Served {
    jwks: Value,
    username: Arc<Mutex<String>>,
}

impl Idp {
    pub async fn start(username: &str) -> Self {
        let public: PublicKeyComponents<Vec<u8>> = key().public().into();
        let jwks = json!({"keys": [{
            "kty": "RSA", "alg": "RS256", "use": "sig", "kid": "k1",
            "n": B64.encode(&public.n),
            "e": B64.encode(&public.e),
        }]});
        let username = Arc::new(Mutex::new(username.to_owned()));
        let served = Arc::new(Served {
            jwks,
            username: username.clone(),
        });
        let app = Router::new()
            .route(
                "/.well-known/jwks.json",
                get(|State(served): State<Arc<Served>>| async move { Json(served.jwks.clone()) }),
            )
            .route("/api/oidc/userinfo", get(userinfo))
            .with_state(served);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Self { url, username }
    }

    pub fn sign_in_as(&self, username: &str) {
        *self.username.lock().unwrap() = username.to_owned();
    }
}

async fn userinfo(State(served): State<Arc<Served>>) -> Json<Value> {
    let name = served.username.lock().unwrap().clone();
    Json(json!({"sub": SUBJECT, "preferred_username": name}))
}

fn key() -> RsaKeyPair {
    RsaKeyPair::from_der(KEY).unwrap()
}

pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
}

/// The claims of a token a fresh `corvid-admin` authorization would yield.
pub fn claims() -> Value {
    json!({"iss": ISSUER, "sub": SUBJECT, "aud": ["corvid-admin", ISSUER], "exp": now() + 3600, "iat": now()})
}

/// A token over `claims`, signed with the fixture key under header `header`.
pub fn sign(header: &Value, claims: &Value) -> String {
    let signed = format!(
        "{}.{}",
        B64.encode(header.to_string()),
        B64.encode(claims.to_string())
    );
    let mut signature = vec![0; key().public().modulus_len()];
    key()
        .sign(
            &RSA_PKCS1_SHA256,
            &SystemRandom::new(),
            signed.as_bytes(),
            &mut signature,
        )
        .unwrap();
    format!("{signed}.{}", B64.encode(signature))
}

pub fn header() -> Value {
    json!({"alg": "RS256", "typ": "at+jwt", "kid": "k1"})
}

pub fn token() -> String {
    sign(&header(), &claims())
}
