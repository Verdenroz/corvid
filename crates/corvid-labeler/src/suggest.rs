//! Posts the mined rule candidates to the sentinel, which stores and serves them to the app.

use std::io;
use std::time::Duration;

use reqwest::blocking::Client as Http;
use serde::Serialize;

use crate::Candidate;

const TIMEOUT: Duration = Duration::from_secs(10);

/// Short, because the poll loop shares this thread.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

pub struct Suggest {
    http: Http,
    url: String,
    secret: Vec<u8>,
}

#[derive(Serialize)]
struct Post {
    sent_at: i64,
    candidates: Vec<Wire>,
}

#[derive(Serialize)]
struct Wire {
    id: String,
    key: String,
    outcome: String,
    settled: u32,
    hits: u32,
}

impl Suggest {
    pub fn connect(sentinel_url: &str, secret: &str) -> io::Result<Self> {
        let http = Http::builder()
            .timeout(TIMEOUT)
            .connect_timeout(CONNECT_TIMEOUT)
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
        let post = Post {
            sent_at: now,
            candidates: candidates
                .iter()
                .map(|candidate| Wire {
                    id: candidate.id.clone(),
                    key: candidate.key.clone(),
                    outcome: candidate.outcome.to_string(),
                    settled: candidate.settled,
                    hits: candidate.hits,
                })
                .collect(),
        };
        let body = serde_json::to_vec(&post).map_err(io::Error::other)?;
        self.http
            .post(&self.url)
            .header("X-Signature", corvid_sign::sign_body(&self.secret, &body))
            .body(body)
            .send()
            .and_then(|response| response.error_for_status())
            .map(drop)
            .map_err(io::Error::other)
    }
}
