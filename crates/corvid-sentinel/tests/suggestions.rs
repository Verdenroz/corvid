mod common;

use std::path::Path;
use std::sync::Arc;

use corvid_sentinel::now_secs;
use corvid_sentinel::scribe::{self, Scribe};
use serde_json::{Value, json};

const SECRET: &[u8] = b"scribe secret";

struct Rig {
    url: String,
    dir: Arc<tempfile::TempDir>,
}

impl Rig {
    /// A new sentinel on the same state directory, reading what the first one saved.
    async fn restart(&self) -> Rig {
        let scribe = scribe::open(&self.dir.path().join("scribe.json")).unwrap();
        Rig {
            url: serve(self.dir.path(), scribe).await,
            dir: self.dir.clone(),
        }
    }
}

async fn serve(dir: &Path, scribe: Scribe) -> String {
    let mut config = common::config(&dir.join("sentinel.json"), &dir.join("no-update"));
    config.scribe = Some(scribe::Config {
        secret: SECRET.to_vec(),
        state_path: dir.join("scribe.json"),
        scribe,
    });
    common::serve(config).await
}

async fn rig() -> Rig {
    let dir = tempfile::tempdir().unwrap();
    Rig {
        url: serve(dir.path(), Scribe::default()).await,
        dir: Arc::new(dir),
    }
}

fn candidate(id: &str, key: &str, outcome: &str) -> Value {
    json!({"id": id, "key": key, "outcome": outcome, "settled": 6, "hits": 6})
}

async fn post_as(
    rig: &Rig,
    secret: &[u8],
    sent_at: i64,
    candidates: Vec<Value>,
) -> reqwest::Response {
    let body = serde_json::to_vec(&json!({"sent_at": sent_at, "candidates": candidates})).unwrap();
    reqwest::Client::new()
        .post(format!("{}/internal/scribe/suggestions", rig.url))
        .header("X-Signature", common::signature(secret, &body))
        .body(body)
        .send()
        .await
        .unwrap()
}

async fn post_at(rig: &Rig, sent_at: i64, candidates: Vec<Value>) -> reqwest::Response {
    post_as(rig, SECRET, sent_at, candidates).await
}

async fn post(rig: &Rig, candidates: Vec<Value>) -> reqwest::Response {
    post_at(rig, now_secs(), candidates).await
}

async fn suggestions(rig: &Rig) -> Vec<Value> {
    let body: Value = reqwest::get(format!("{}/corvid/suggestions", rig.url))
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    body["suggestions"].as_array().unwrap().clone()
}

async fn decide(rig: &Rig, id: &str, verdict: &str) -> reqwest::Response {
    reqwest::Client::new()
        .post(format!("{}/corvid/suggestions/{id}/{verdict}", rig.url))
        .send()
        .await
        .unwrap()
}

fn ids(suggestions: &[Value]) -> Vec<&str> {
    suggestions
        .iter()
        .map(|suggestion| suggestion["id"].as_str().unwrap())
        .collect()
}

#[tokio::test]
async fn a_posted_candidate_is_served_in_the_app_shape() {
    let rig = rig().await;

    let response = post(
        &rig,
        vec![candidate("a", "list:news.example", "folder:mb7")],
    )
    .await;

    assert_eq!(response.status(), 204);
    assert_eq!(
        suggestions(&rig).await,
        [json!({
            "id": "a",
            "sender": {"kind": "list", "value": "news.example"},
            "action": {"kind": "folder", "mailboxId": "mb7"},
            "settled": 6,
            "hits": 6,
        })]
    );
}

#[tokio::test]
async fn a_bad_signature_is_refused() {
    let rig = rig().await;

    let response = post_as(&rig, b"another secret", now_secs(), vec![]).await;

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn a_stale_sent_at_is_refused() {
    let rig = rig().await;

    let old = post_at(
        &rig,
        now_secs() - 301,
        vec![candidate("a", "from:a@x.test", "trash")],
    )
    .await;
    let early = post_at(
        &rig,
        now_secs() + 301,
        vec![candidate("a", "from:a@x.test", "trash")],
    )
    .await;

    assert_eq!(old.status(), 400);
    assert_eq!(early.status(), 400);
    assert!(suggestions(&rig).await.is_empty());
}

#[tokio::test]
async fn a_dismissed_id_is_never_served_again() {
    let rig = rig().await;
    post(&rig, vec![candidate("a", "from:a@x.test", "archive")]).await;

    assert_eq!(decide(&rig, "a", "dismiss").await.status(), 204);
    post(&rig, vec![candidate("a", "from:a@x.test", "archive")]).await;

    assert!(suggestions(&rig).await.is_empty());
}

#[tokio::test]
async fn an_accepted_id_is_hidden_and_deciding_it_again_is_idempotent() {
    let rig = rig().await;
    post(&rig, vec![candidate("a", "from:a@x.test", "archive")]).await;

    assert_eq!(decide(&rig, "a", "accept").await.status(), 204);
    assert_eq!(decide(&rig, "a", "dismiss").await.status(), 204);

    assert!(suggestions(&rig).await.is_empty());
}

#[tokio::test]
async fn an_unknown_id_is_not_found() {
    let rig = rig().await;

    assert_eq!(decide(&rig, "nope", "accept").await.status(), 404);
}

#[tokio::test]
async fn replace_all_drops_a_vanished_candidate() {
    let rig = rig().await;
    post(
        &rig,
        vec![
            candidate("a", "from:a@x.test", "archive"),
            candidate("b", "list:l.test", "trash"),
        ],
    )
    .await;

    post(&rig, vec![candidate("b", "list:l.test", "trash")]).await;

    assert_eq!(ids(&suggestions(&rig).await), ["b"]);
    assert_eq!(decide(&rig, "a", "accept").await.status(), 404);
}

#[tokio::test]
async fn an_unparseable_candidate_is_dropped() {
    let rig = rig().await;

    let response = post(
        &rig,
        vec![
            candidate("a", "domain:x.test", "archive"),
            candidate("b", "from:b@x.test", "snooze"),
            candidate("c", "from:", "archive"),
            candidate("d", "from:d@x.test", "folder:"),
            candidate("e", "from:e@x.test", "trash"),
        ],
    )
    .await;

    assert_eq!(response.status(), 204);
    assert_eq!(ids(&suggestions(&rig).await), ["e"]);
}

#[tokio::test]
async fn decisions_and_suggestions_survive_a_restart() {
    let first = rig().await;
    post(
        &first,
        vec![
            candidate("a", "from:a@x.test", "archive"),
            candidate("b", "from:b@x.test", "trash"),
        ],
    )
    .await;
    decide(&first, "a", "dismiss").await;

    let second = first.restart().await;

    assert_eq!(ids(&suggestions(&second).await), ["b"]);
}

#[tokio::test]
async fn off_answers_503_on_every_suggestion_route() {
    let dir = tempfile::tempdir().unwrap();
    let url = common::serve(common::config(
        &dir.path().join("sentinel.json"),
        &dir.path().join("no-update"),
    ))
    .await;
    let client = reqwest::Client::new();

    let list = client
        .get(format!("{url}/corvid/suggestions"))
        .send()
        .await
        .unwrap();
    let accept = client
        .post(format!("{url}/corvid/suggestions/a/accept"))
        .send()
        .await
        .unwrap();
    let ingest = client
        .post(format!("{url}/internal/scribe/suggestions"))
        .send()
        .await
        .unwrap();

    for response in [list, accept, ingest] {
        assert_eq!(response.status(), 503);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body, json!({"error": "suggestions_off"}));
    }
}
