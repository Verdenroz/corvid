use std::path::Path;
use std::time::Duration;

use corvid_sentinel::{Config, Seen, build};
use tokio::net::TcpListener;

pub fn config(state_path: &Path, update_dir: &Path) -> Config {
    Config {
        secret: b"secret".to_vec(),
        state_path: state_path.to_owned(),
        seen: Seen::default(),
        update_dir: update_dir.to_owned(),
        update_poll: Duration::from_secs(60),
        devices: None,
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
