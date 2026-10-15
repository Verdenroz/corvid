//! Posts the mined rule candidates to the sentinel, which stores and serves them to the app.

use std::io;

use corvid_sign::SignedPost;
use serde::Serialize;

use crate::Candidate;

pub struct Suggest {
    post: SignedPost,
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
        Ok(Self {
            post: SignedPost::connect(sentinel_url, secret)?,
        })
    }

    /// Replaces the sentinel's set with `candidates`.
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
        self.post
            .post("/internal/scribe/suggestions", &post)
            .map(drop)
    }
}
