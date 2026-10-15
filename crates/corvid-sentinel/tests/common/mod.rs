use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use corvid_sentinel::scribe::{self, Scribe};
use corvid_sentinel::{Config, Seen, build};
use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use tokio::net::TcpListener;

pub fn config(state_path: &Path, update_dir: &Path) -> Config {
    Config {
        secret: b"secret".to_vec(),
        state_path: state_path.to_owned(),
        seen: Seen::default(),
        update_dir: update_dir.to_owned(),
        update_poll: Duration::from_secs(60),
        devices: None,
        auth: None,
        scribe: None,
    }
}

/// Serves the sentinel on a free port and returns its base URL.
pub async fn serve(config: Config) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let (app, watcher) = build(config);
    tokio::spawn(watcher);
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}")
}

/// The `X-Signature` Stalwart's WebHooks, and the host's signed calls, carry for `body`.
#[allow(dead_code)]
pub fn signature(secret: &[u8], body: &[u8]) -> String {
    let mut mac = <Hmac<Sha256>>::new_from_slice(secret).unwrap();
    mac.update(body);
    STANDARD.encode(mac.finalize().into_bytes())
}

#[allow(dead_code)]
pub const SCRIBE_SECRET: &[u8] = b"scribe secret";

/// A sentinel with the scribe routes on, over a state directory that outlives a restart.
#[allow(dead_code)]
pub struct ScribeRig {
    pub url: String,
    pub dir: Arc<tempfile::TempDir>,
}

#[allow(dead_code)]
impl ScribeRig {
    pub async fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        Self {
            url: serve_scribe(dir.path(), Scribe::default()).await,
            dir: Arc::new(dir),
        }
    }

    /// A new sentinel on the same state directory, reading what the first one saved.
    pub async fn reopen(&self) -> Self {
        let scribe = scribe::open(&self.dir.path().join("scribe.json")).unwrap();
        Self {
            url: serve_scribe(self.dir.path(), scribe).await,
            dir: self.dir.clone(),
        }
    }
}

async fn serve_scribe(dir: &Path, scribe: Scribe) -> String {
    let mut config = config(&dir.join("sentinel.json"), &dir.join("no-update"));
    config.scribe = Some(scribe::Config {
        secret: SCRIBE_SECRET.to_vec(),
        state_path: dir.join("scribe.json"),
        scribe,
    });
    serve(config).await
}
