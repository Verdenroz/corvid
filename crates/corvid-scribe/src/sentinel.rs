//! The sentinel's side of the summaries: what it holds, and new ones to keep.

use std::collections::BTreeMap;
use std::io;

use corvid_sign::SignedPost;
use serde::{Deserialize, Serialize};
use serde_json::json;

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
    post: SignedPost,
}

#[derive(Deserialize)]
struct Manifest {
    threads: BTreeMap<String, String>,
}

impl Sentinel {
    pub fn connect(sentinel_url: &str, secret: &str) -> io::Result<Self> {
        Ok(Self {
            post: SignedPost::connect(sentinel_url, secret)?,
        })
    }
}

impl Sink for Sentinel {
    fn manifest(&self, now: i64) -> io::Result<BTreeMap<String, String>> {
        let manifest: Manifest = self
            .post
            .post("/internal/scribe/manifest", &json!({"sent_at": now}))?
            .json()
            .map_err(io::Error::other)?;
        Ok(manifest.threads)
    }

    fn post(&self, now: i64, upserts: &[Upsert], deletes: &[String]) -> io::Result<()> {
        let body = json!({"sent_at": now, "upserts": upserts, "deletes": deletes});
        self.post
            .post("/internal/scribe/summaries", &body)
            .map(drop)
    }
}
