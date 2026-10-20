use std::path::Path;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use corvid_scribe::{Sentinel, Sink, Upsert};
use corvid_sentinel::{Config, Seen, build, scribe};
use serde_json::{Value, json};

const SECRET: &str = "scribe secret";

/// The real sentinel on a thread of its own, since the scribe posts with a blocking client.
fn sentinel(dir: &Path) -> String {
    let (sender, receiver) = mpsc::channel();
    let dir = dir.to_owned();
    thread::spawn(move || {
        tokio::runtime::Runtime::new()
            .unwrap()
            .block_on(async move {
                let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
                sender.send(listener.local_addr().unwrap()).unwrap();
                let (app, watcher) = build(Config {
                    secret: b"webhook".to_vec(),
                    state_path: dir.join("sentinel.json"),
                    seen: Seen::default(),
                    update_dir: dir.join("no-update"),
                    update_poll: Duration::from_secs(60),
                    devices: None,
                    auth: None,
                    scribe: Some(scribe::Config {
                        secret: SECRET.as_bytes().to_vec(),
                        state_path: dir.join("scribe.json"),
                        scribe: scribe::Scribe::default(),
                        digest: None,
                    }),
                    aliases: None,
                });
                tokio::spawn(watcher);
                axum::serve(listener, app).await.unwrap();
            });
    });
    format!("http://{}", receiver.recv().unwrap())
}

fn upsert(thread_id: &str, hash: &str) -> Upsert {
    Upsert {
        thread_id: thread_id.to_owned(),
        input_hash: hash.to_owned(),
        text: "They agreed.".to_owned(),
        model: "m".to_owned(),
        generated_at: 1700,
    }
}

#[test]
fn a_posted_summary_shows_in_the_manifest_and_is_served_to_the_app() {
    let dir = tempfile::tempdir().unwrap();
    let url = sentinel(dir.path());
    let client = Sentinel::connect(&url, SECRET).unwrap();
    let now = corvid_sentinel::now_secs();

    client
        .post(now, &[upsert("T1", "h1"), upsert("T2", "h2")], &[])
        .unwrap();
    client.post(now, &[], &["T2".to_owned()]).unwrap();

    let manifest = client.manifest(now).unwrap();
    assert_eq!(manifest.len(), 1);
    assert_eq!(manifest["T1"], "h1");
    let served: Value = reqwest::blocking::get(format!("{url}/corvid/summaries/T1"))
        .unwrap()
        .json()
        .unwrap();
    assert_eq!(
        served,
        json!({
            "threadId": "T1",
            "text": "They agreed.",
            "model": "m",
            "generatedAt": "1970-01-01T00:28:20Z",
        })
    );
}

#[test]
fn a_wrong_secret_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let url = sentinel(dir.path());

    let client = Sentinel::connect(&url, "another secret").unwrap();

    assert!(client.manifest(corvid_sentinel::now_secs()).is_err());
}

#[test]
fn an_unreachable_sentinel_is_an_error() {
    let client = Sentinel::connect("http://127.0.0.1:1", SECRET).unwrap();

    assert!(client.manifest(0).is_err());
}
