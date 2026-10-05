use std::time::Duration;

use corvid_sentinel::{Config, NoticeKind, Seen, router};
use tokio::net::TcpListener;

async fn serve(seen: Seen) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let dir = std::env::temp_dir();
    let app = router(Config {
        secret: b"secret".to_vec(),
        state_path: dir.join("sentinel-notices-test.json"),
        seen,
        update_dir: dir.join("sentinel-test-no-update-dir"),
        update_poll: Duration::from_secs(60),
    });
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}/corvid/notices")
}

fn feed(count: u64) -> Seen {
    let mut seen = Seen::default();
    for n in 1..=count {
        seen.push_notice(
            NoticeKind::SignIn,
            format!("2026-10-04T00:00:0{n}Z"),
            format!("notice {n}"),
            String::new(),
        );
    }
    seen
}

async fn ids(url: &str) -> Vec<u64> {
    let body: serde_json::Value = reqwest::get(url).await.unwrap().json().await.unwrap();
    body["notices"]
        .as_array()
        .unwrap()
        .iter()
        .map(|notice| notice["id"].as_u64().unwrap())
        .collect()
}

#[tokio::test]
async fn every_notice_is_served_oldest_first_when_after_is_absent() {
    let url = serve(feed(3)).await;

    assert_eq!(ids(&url).await, [1, 2, 3]);
}

#[tokio::test]
async fn after_returns_only_newer_notices() {
    let url = serve(feed(3)).await;

    assert_eq!(ids(&format!("{url}?after=2")).await, [3]);
    assert_eq!(ids(&format!("{url}?after=3")).await, Vec::<u64>::new());
}

#[tokio::test]
async fn a_notice_is_served_with_the_documented_fields() {
    let url = serve(feed(1)).await;

    let body: serde_json::Value = reqwest::get(&url).await.unwrap().json().await.unwrap();

    assert_eq!(
        body["notices"][0],
        serde_json::json!({
            "id": 1,
            "kind": "signIn",
            "at": "2026-10-04T00:00:01Z",
            "title": "notice 1",
            "detail": "",
        })
    );
}

#[tokio::test]
async fn an_after_that_is_not_a_number_is_a_bad_request() {
    let url = serve(feed(1)).await;

    let response = reqwest::get(format!("{url}?after=newest")).await.unwrap();

    assert_eq!(response.status(), 400);
}

#[tokio::test]
async fn a_feed_with_nothing_in_it_serves_an_empty_list() {
    let url = serve(Seen::default()).await;

    assert_eq!(ids(&url).await, Vec::<u64>::new());
}
