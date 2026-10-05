mod common;

use std::path::Path;

async fn serve(update_dir: &Path) -> String {
    let config = common::config(&update_dir.join("sentinel-state.json"), update_dir);
    format!("{}/corvid/update", common::serve(config).await)
}

async fn post(url: &str, body: &str) -> reqwest::Response {
    reqwest::Client::new()
        .post(url)
        .body(body.to_owned())
        .send()
        .await
        .unwrap()
}

#[tokio::test]
async fn the_status_file_is_served_verbatim_as_json() {
    let dir = tempfile::tempdir().unwrap();
    let status = r#"{"running":"v1.0.0","latest":null,"result":"current"}"#;
    std::fs::write(dir.path().join("status.json"), status).unwrap();
    let url = serve(dir.path()).await;

    let response = reqwest::get(&url).await.unwrap();

    assert_eq!(response.status(), 200);
    assert_eq!(response.headers()["content-type"], "application/json");
    assert_eq!(response.text().await.unwrap(), status);
}

#[tokio::test]
async fn a_directory_that_is_not_mounted_answers_404_to_both_methods() {
    let dir = tempfile::tempdir().unwrap();
    let url = serve(&dir.path().join("absent")).await;

    assert_eq!(reqwest::get(&url).await.unwrap().status(), 404);
    assert_eq!(post(&url, r#"{"action":"check"}"#).await.status(), 404);
}

#[tokio::test]
async fn a_mounted_directory_without_a_status_file_answers_503_with_json() {
    let dir = tempfile::tempdir().unwrap();
    let url = serve(dir.path()).await;

    let response = reqwest::get(&url).await.unwrap();

    assert_eq!(response.status(), 503);
    let body: serde_json::Value = response.json().await.unwrap();
    assert!(body["error"].is_string());
}

#[tokio::test]
async fn a_check_or_apply_is_written_as_the_request_file() {
    for action in ["check", "apply"] {
        let dir = tempfile::tempdir().unwrap();
        let url = serve(dir.path()).await;

        let response = post(&url, &format!(r#"{{"action":"{action}"}}"#)).await;

        assert_eq!(response.status(), 202);
        assert_eq!(
            std::fs::read_to_string(dir.path().join("request")).unwrap(),
            action
        );
        let leftovers = std::fs::read_dir(dir.path()).unwrap().count();
        assert_eq!(leftovers, 1, "the temp file is renamed away");
    }
}

#[tokio::test]
async fn an_apply_replaces_a_pending_check() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("request"), "check").unwrap();
    let url = serve(dir.path()).await;

    let response = post(&url, r#"{"action":"apply"}"#).await;

    assert_eq!(response.status(), 202);
    assert_eq!(
        std::fs::read_to_string(dir.path().join("request")).unwrap(),
        "apply"
    );
}

#[tokio::test]
async fn a_pending_apply_survives_a_check_and_still_answers_202() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("request"), "apply").unwrap();
    let url = serve(dir.path()).await;

    let response = post(&url, r#"{"action":"check"}"#).await;

    assert_eq!(response.status(), 202);
    assert_eq!(
        std::fs::read_to_string(dir.path().join("request")).unwrap(),
        "apply"
    );
}

#[tokio::test]
async fn anything_but_a_known_action_is_400_and_writes_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let url = serve(dir.path()).await;

    for body in [
        "",
        "check",
        "{}",
        r#"{"action":"rm -rf /"}"#,
        r#"{"action":"Check"}"#,
        r#"{"action":"check","extra":1}"#,
    ] {
        assert_eq!(post(&url, body).await.status(), 400, "{body:?}");
    }
    assert!(!dir.path().join("request").exists());
}

#[tokio::test]
async fn an_oversized_body_is_refused_and_writes_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let url = serve(dir.path()).await;
    let body = format!(r#"{{"action":"check","pad":"{}"}}"#, "x".repeat(4096));

    assert_eq!(post(&url, &body).await.status(), 413);
    assert!(!dir.path().join("request").exists());
}
