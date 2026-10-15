mod common;

use common::ScribeRig as Rig;
use corvid_sentinel::now_secs;
use serde_json::{Value, json};

const SECRET: &[u8] = common::SCRIBE_SECRET;

fn upsert(thread_id: &str, input_hash: &str, text: &str, generated_at: i64) -> Value {
    json!({
        "thread_id": thread_id,
        "input_hash": input_hash,
        "text": text,
        "model": "m",
        "generated_at": generated_at,
    })
}

async fn signed(
    rig: &Rig,
    path: &str,
    secret: &[u8],
    mut body: Value,
    sent_at: i64,
) -> reqwest::Response {
    body["sent_at"] = json!(sent_at);
    let body = serde_json::to_vec(&body).unwrap();
    reqwest::Client::new()
        .post(format!("{}/internal/scribe/{path}", rig.url))
        .header("X-Signature", common::signature(secret, &body))
        .body(body)
        .send()
        .await
        .unwrap()
}

async fn post(rig: &Rig, upserts: Vec<Value>, deletes: Vec<&str>) -> reqwest::Response {
    signed(
        rig,
        "summaries",
        SECRET,
        json!({"upserts": upserts, "deletes": deletes}),
        now_secs(),
    )
    .await
}

async fn manifest(rig: &Rig) -> Value {
    signed(rig, "manifest", SECRET, json!({}), now_secs())
        .await
        .json()
        .await
        .unwrap()
}

async fn get(rig: &Rig, thread_id: &str) -> reqwest::Response {
    reqwest::get(format!("{}/corvid/summaries/{thread_id}", rig.url))
        .await
        .unwrap()
}

#[tokio::test]
async fn a_posted_summary_is_served_in_the_app_shape() {
    let rig = Rig::new().await;

    let response = post(&rig, vec![upsert("T1", "h1", "They agreed.", 1700)], vec![]).await;

    assert_eq!(response.status(), 204);
    let served: Value = get(&rig, "T1").await.json().await.unwrap();
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

#[tokio::test]
async fn a_thread_without_a_summary_is_not_found() {
    let rig = Rig::new().await;

    assert_eq!(get(&rig, "nope").await.status(), 404);
}

#[tokio::test]
async fn the_manifest_maps_each_thread_to_its_input_hash() {
    let rig = Rig::new().await;
    post(
        &rig,
        vec![upsert("T1", "h1", "a", 1), upsert("T2", "h2", "b", 2)],
        vec![],
    )
    .await;

    assert_eq!(
        manifest(&rig).await,
        json!({"threads": {"T1": "h1", "T2": "h2"}})
    );
}

#[tokio::test]
async fn a_second_summary_replaces_the_first_and_a_delete_removes_it() {
    let rig = Rig::new().await;
    post(
        &rig,
        vec![upsert("T1", "h1", "old", 1), upsert("T2", "h2", "keep", 2)],
        vec![],
    )
    .await;

    post(&rig, vec![upsert("T1", "h3", "new", 3)], vec!["T2"]).await;

    let served: Value = get(&rig, "T1").await.json().await.unwrap();
    assert_eq!(served["text"], "new");
    assert_eq!(get(&rig, "T2").await.status(), 404);
    assert_eq!(manifest(&rig).await, json!({"threads": {"T1": "h3"}}));
}

#[tokio::test]
async fn text_over_two_thousand_chars_refuses_the_whole_post() {
    let rig = Rig::new().await;

    let response = post(
        &rig,
        vec![
            upsert("T1", "h1", "fine", 1),
            upsert("T2", "h2", &"é".repeat(2001), 2),
        ],
        vec![],
    )
    .await;

    assert_eq!(response.status(), 400);
    assert_eq!(get(&rig, "T1").await.status(), 404);
    let at_limit = post(&rig, vec![upsert("T3", "h3", &"é".repeat(2000), 3)], vec![]).await;
    assert_eq!(at_limit.status(), 204);
}

#[tokio::test]
async fn past_the_cap_the_oldest_generated_summary_goes_first() {
    let rig = Rig::new().await;
    let upserts: Vec<Value> = (0..5001)
        .map(|n| upsert(&format!("T{n}"), "h", "s", 1000 + (n + 1) % 5001))
        .collect();

    post(&rig, upserts, vec![]).await;

    assert_eq!(get(&rig, "T4999").await.status(), 200);
    assert_eq!(get(&rig, "T5000").await.status(), 404);
    assert_eq!(
        manifest(&rig).await["threads"].as_object().unwrap().len(),
        5000
    );
}

#[tokio::test]
async fn a_bad_signature_or_stale_clock_is_refused_on_both_posts() {
    let rig = Rig::new().await;
    let body = json!({"upserts": [], "deletes": []});

    let forged = signed(&rig, "summaries", b"another", body.clone(), now_secs()).await;
    let stale = signed(&rig, "summaries", SECRET, body, now_secs() - 301).await;
    let forged_manifest = signed(&rig, "manifest", b"another", json!({}), now_secs()).await;
    let stale_manifest = signed(&rig, "manifest", SECRET, json!({}), now_secs() + 301).await;

    assert_eq!(forged.status(), 401);
    assert_eq!(stale.status(), 400);
    assert_eq!(forged_manifest.status(), 401);
    assert_eq!(stale_manifest.status(), 400);
}

#[tokio::test]
async fn summaries_survive_a_restart() {
    let first = Rig::new().await;
    post(&first, vec![upsert("T1", "h1", "kept", 5)], vec![]).await;

    let second = first.reopen().await;

    let served: Value = get(&second, "T1").await.json().await.unwrap();
    assert_eq!(served["text"], "kept");
}

#[tokio::test]
async fn off_answers_503_on_every_summary_route() {
    let dir = tempfile::tempdir().unwrap();
    let url = common::serve(common::config(
        &dir.path().join("sentinel.json"),
        &dir.path().join("no-update"),
    ))
    .await;
    let client = reqwest::Client::new();

    let read = client
        .get(format!("{url}/corvid/summaries/T1"))
        .send()
        .await
        .unwrap();
    let ingest = client
        .post(format!("{url}/internal/scribe/summaries"))
        .send()
        .await
        .unwrap();
    let manifest = client
        .post(format!("{url}/internal/scribe/manifest"))
        .send()
        .await
        .unwrap();

    for response in [read, ingest, manifest] {
        assert_eq!(response.status(), 503);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body, json!({"error": "summaries_off"}));
    }
}

#[test]
fn a_state_file_written_before_summaries_still_opens() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("scribe.json");
    corvid_store::save(&path, 1, &json!({"suggestions": [], "decided": []})).unwrap();

    let scribe = corvid_sentinel::scribe::open(&path).unwrap();

    assert!(scribe.summaries.is_empty());
}
