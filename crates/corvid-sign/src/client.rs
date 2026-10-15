//! A blocking client for the sentinel's signed internal routes.

use std::io;
use std::time::Duration;

use reqwest::blocking::{Client, Response};
use serde::Serialize;

use crate::sign_body;

const TIMEOUT: Duration = Duration::from_secs(10);

/// Short, because the caller's loop shares this thread.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

pub struct SignedPost {
    http: Client,
    base: String,
    secret: Vec<u8>,
}

impl SignedPost {
    pub fn connect(base_url: &str, secret: &str) -> io::Result<Self> {
        let http = Client::builder()
            .timeout(TIMEOUT)
            .connect_timeout(CONNECT_TIMEOUT)
            .build()
            .map_err(io::Error::other)?;
        Ok(Self {
            http,
            base: base_url.trim_end_matches('/').to_owned(),
            secret: secret.as_bytes().to_vec(),
        })
    }

    /// Posts `body` as JSON to `path`, signed over the exact bytes sent. A refusal is an error.
    pub fn post(&self, path: &str, body: &impl Serialize) -> io::Result<Response> {
        let body = serde_json::to_vec(body).map_err(io::Error::other)?;
        self.http
            .post(format!("{}{path}", self.base))
            .header("X-Signature", sign_body(&self.secret, &body))
            .body(body)
            .send()
            .and_then(|response| response.error_for_status())
            .map_err(io::Error::other)
    }
}
