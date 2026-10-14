//! Posts the mined rule candidates to the sentinel, which stores and serves them to the app.

use std::io;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, KeyInit, Mac};
use reqwest::blocking::Client as Http;
use serde_json::json;
use sha2::Sha256;

use crate::Candidate;

const TIMEOUT: Duration = Duration::from_secs(10);

pub struct Suggest {
    http: Http,
    url: String,
    secret: Vec<u8>,
}

impl Suggest {
    pub fn connect(sentinel_url: &str, secret: &str) -> io::Result<Self> {
        let http = Http::builder()
            .timeout(TIMEOUT)
            .build()
            .map_err(io::Error::other)?;
        Ok(Self {
            http,
            url: format!(
                "{}/internal/scribe/suggestions",
                sentinel_url.trim_end_matches('/')
            ),
            secret: secret.as_bytes().to_vec(),
        })
    }

    /// Replaces the sentinel's set with `candidates`, signed over the exact bytes sent.
    pub fn post(&self, now: i64, candidates: &[Candidate]) -> io::Result<()> {
        let body = json!({
            "sent_at": now,
            "candidates": candidates.iter().map(|candidate| json!({
                "id": candidate.id,
                "key": candidate.key,
                "outcome": candidate.outcome.to_string(),
                "settled": candidate.settled,
                "hits": candidate.hits,
            })).collect::<Vec<_>>(),
        })
        .to_string();
        let mut mac =
            <Hmac<Sha256>>::new_from_slice(&self.secret).expect("hmac accepts any key size");
        mac.update(body.as_bytes());
        self.http
            .post(&self.url)
            .header("X-Signature", STANDARD.encode(mac.finalize().into_bytes()))
            .body(body)
            .send()
            .and_then(|response| response.error_for_status())
            .map(drop)
            .map_err(io::Error::other)
    }
}
