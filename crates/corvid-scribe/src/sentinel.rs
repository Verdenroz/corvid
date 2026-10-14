//! The sentinel's side of the summaries: what it holds, and new ones to keep.

use std::collections::BTreeMap;
use std::io;
use std::time::Duration;

use reqwest::blocking::Client as Http;
use serde::{Deserialize, Serialize};
use serde_json::json;

const TIMEOUT: Duration = Duration::from_secs(10);

/// Short, because the pass shares this thread.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Upsert {
    pub thread_id: String,
    pub input_hash: String,
    pub text: String,
    pub model: String,
    pub generated_at: i64,
}

/// What a pass needs from the sentinel, so a pass can be exercised without one.
pub trait Sink {
    /// Thread id to the input hash its stored summary was written from.
    fn manifest(&self, now: i64) -> io::Result<BTreeMap<String, String>>;

    fn post(&self, now: i64, upserts: &[Upsert], deletes: &[String]) -> io::Result<()>;
}

pub struct Sentinel {
    http: Http,
    base: String,
    secret: Vec<u8>,
}

#[derive(Deserialize)]
struct Manifest {
    threads: BTreeMap<String, String>,
}

impl Sentinel {
    pub fn connect(sentinel_url: &str, secret: &str) -> io::Result<Self> {
        let http = Http::builder()
            .timeout(TIMEOUT)
            .connect_timeout(CONNECT_TIMEOUT)
            .build()
            .map_err(io::Error::other)?;
        Ok(Self {
            http,
            base: sentinel_url.trim_end_matches('/').to_owned(),
            secret: secret.as_bytes().to_vec(),
        })
    }

    /// Signed over the exact bytes sent.
    fn send(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> io::Result<reqwest::blocking::Response> {
        let body = serde_json::to_vec(body).map_err(io::Error::other)?;
        self.http
            .post(format!("{}/internal/scribe/{path}", self.base))
            .header("X-Signature", corvid_sign::sign_body(&self.secret, &body))
            .body(body)
            .send()
            .and_then(|response| response.error_for_status())
            .map_err(io::Error::other)
    }
}

impl Sink for Sentinel {
    fn manifest(&self, now: i64) -> io::Result<BTreeMap<String, String>> {
        let manifest: Manifest = self
            .send("manifest", &json!({"sent_at": now}))?
            .json()
            .map_err(io::Error::other)?;
        Ok(manifest.threads)
    }

    fn post(&self, now: i64, upserts: &[Upsert], deletes: &[String]) -> io::Result<()> {
        let body = json!({"sent_at": now, "upserts": upserts, "deletes": deletes});
        self.send("summaries", &body).map(drop)
    }
}
