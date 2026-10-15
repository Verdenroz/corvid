use std::net::TcpListener;
use std::path::Path;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use corvid_labeler::{Candidate, Exit, Suggest};
use corvid_sentinel::{Config, Seen, build, scribe};
use serde_json::{Value, json};

const SECRET: &str = "scribe secret";

/// The real sentinel on a thread of its own, since the labeler posts with a blocking client.
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
                });
                tokio::spawn(watcher);
                axum::serve(listener, app).await.unwrap();
            });
    });
    format!("http://{}", receiver.recv().unwrap())
}

fn suggestions(url: &str) -> Vec<Value> {
    let body: Value = reqwest::blocking::get(format!("{url}/corvid/suggestions"))
        .unwrap()
        .json()
        .unwrap();
    body["suggestions"].as_array().unwrap().clone()
}

fn candidates() -> Vec<Candidate> {
    vec![
        Candidate {
            id: "a".to_owned(),
            key: "list:news.example".to_owned(),
            outcome: Exit::Folder("mb7".to_owned()),
            settled: 6,
            hits: 5,
        },
        Candidate {
            id: "b".to_owned(),
            key: "from:bob@x.test".to_owned(),
            outcome: Exit::Archive,
            settled: 4,
            hits: 4,
        },
    ]
}

#[test]
fn mined_candidates_come_back_from_the_sentinel_in_the_app_shape() {
    let dir = tempfile::tempdir().unwrap();
    let url = sentinel(dir.path());

    Suggest::connect(&url, SECRET)
        .unwrap()
        .post(corvid_sentinel::now_secs(), &candidates())
        .unwrap();

    assert_eq!(
        suggestions(&url),
        [
            json!({
                "id": "a",
                "sender": {"kind": "list", "value": "news.example"},
                "action": {"kind": "folder", "mailboxId": "mb7"},
                "settled": 6,
                "hits": 5,
            }),
            json!({
                "id": "b",
                "sender": {"kind": "from", "value": "bob@x.test"},
                "action": {"kind": "archive"},
                "settled": 4,
                "hits": 4,
            }),
        ]
    );
}

#[test]
fn a_wrong_secret_is_refused_and_stores_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let url = sentinel(dir.path());

    let result = Suggest::connect(&url, "another secret")
        .unwrap()
        .post(corvid_sentinel::now_secs(), &candidates());

    assert!(result.is_err());
    assert!(suggestions(&url).is_empty());
}

#[test]
fn an_unreachable_sentinel_is_an_error() {
    let url = {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        format!("http://{}", listener.local_addr().unwrap())
    };

    let result = Suggest::connect(&url, SECRET)
        .unwrap()
        .post(1_000, &candidates());

    assert!(result.is_err());
}
