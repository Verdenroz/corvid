use std::io;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use corvid_sentinel::{Config, Notifier, Seen, open, router};
use tokio::net::TcpListener;

#[derive(Default)]
struct Mailbox {
    sent: Mutex<Vec<String>>,
    refuse: AtomicBool,
}

struct Shared(Arc<Mailbox>);

impl Notifier for Shared {
    fn account_email(&self) -> &str {
        "alice@corvid.test"
    }

    fn send(&self, message: &[u8]) -> io::Result<()> {
        if self.0.refuse.load(Ordering::SeqCst) {
            return Err(io::Error::other("the mail server refused"));
        }
        self.0
            .sent
            .lock()
            .unwrap()
            .push(String::from_utf8(message.to_vec()).unwrap());
        Ok(())
    }
}

async fn serve(update_dir: &Path) -> String {
    serve_with(update_dir, Arc::default()).await
}

async fn serve_with(update_dir: &Path, mailbox: Arc<Mailbox>) -> String {
    let unused = std::env::temp_dir().join("sentinel-update-test-unused.json");
    serve_state(update_dir, mailbox, Seen::default(), &unused).await
}

async fn serve_state(
    update_dir: &Path,
    mailbox: Arc<Mailbox>,
    seen: Seen,
    state_path: &Path,
) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = router(Config {
        secret: b"secret".to_vec(),
        state_path: state_path.to_owned(),
        seen,
        notifier: Shared(mailbox),
        update_dir: update_dir.to_owned(),
        update_poll: Duration::from_millis(20),
    });
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}/corvid/update")
}

fn write_status(dir: &Path, result: &str, checked_at: &str) {
    let status = format!(
        r#"{{"running":"v1.0.0","latest":"v1.1.0","checkedAt":"{checked_at}","result":"{result}","detail":"v1.1.0 failed to deploy; v1.0.0 is running again","auto":true,"busy":false}}"#
    );
    std::fs::write(dir.join("status.json"), status).unwrap();
}

async fn sent_count(mailbox: &Mailbox, at_least: usize) -> usize {
    for _ in 0..100 {
        let count = mailbox.sent.lock().unwrap().len();
        if count >= at_least {
            return count;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    mailbox.sent.lock().unwrap().len()
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

#[tokio::test]
async fn a_rolled_back_update_reaches_the_inbox_once_and_is_remembered() {
    let dir = tempfile::tempdir().unwrap();
    let state = tempfile::tempdir().unwrap();
    let state_path = state.path().join("sentinel.json");
    write_status(dir.path(), "rolledBack", "2026-10-04T04:12:00Z");
    let mailbox = Arc::new(Mailbox::default());
    serve_state(dir.path(), mailbox.clone(), Seen::default(), &state_path).await;

    assert_eq!(sent_count(&mailbox, 1).await, 1);
    tokio::time::sleep(Duration::from_millis(200)).await;

    let sent = mailbox.sent.lock().unwrap();
    assert_eq!(sent.len(), 1, "later polls of the same run send nothing");
    assert!(sent[0].contains("Subject: Corvid update to v1.1.0 was rolled back on corvid.test"));
    assert!(sent[0].contains("v1.0.0 is running again"));
    assert!(sent[0].contains("journalctl -u corvid-update.service"));
    assert_eq!(
        open(&state_path).unwrap().update_noticed.as_deref(),
        Some("2026-10-04T04:12:00Z")
    );
}

#[tokio::test]
async fn a_later_failed_run_is_noticed_again() {
    let dir = tempfile::tempdir().unwrap();
    write_status(dir.path(), "rolledBack", "2026-10-04T04:12:00Z");
    let mailbox = Arc::new(Mailbox::default());
    serve_with(dir.path(), mailbox.clone()).await;
    sent_count(&mailbox, 1).await;

    write_status(dir.path(), "failed", "2026-10-05T04:03:00Z");

    assert_eq!(sent_count(&mailbox, 2).await, 2);
    assert!(mailbox.sent.lock().unwrap()[1].contains("failed and the rollback failed too"));
}

#[tokio::test]
async fn a_run_that_did_not_fail_sends_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let mailbox = Arc::new(Mailbox::default());
    serve_with(dir.path(), mailbox.clone()).await;

    for result in ["current", "updated", "skipped"] {
        write_status(dir.path(), result, "2026-10-04T04:12:00Z");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }

    assert!(mailbox.sent.lock().unwrap().is_empty());
}

#[tokio::test]
async fn a_failure_already_noticed_before_a_restart_is_not_sent_again() {
    let dir = tempfile::tempdir().unwrap();
    write_status(dir.path(), "rolledBack", "2026-10-04T04:12:00Z");
    let seen = Seen {
        update_noticed: Some("2026-10-04T04:12:00Z".to_owned()),
        ..Seen::default()
    };
    let mailbox = Arc::new(Mailbox::default());
    let unused = std::env::temp_dir().join("sentinel-update-test-unused.json");
    serve_state(dir.path(), mailbox.clone(), seen, &unused).await;

    tokio::time::sleep(Duration::from_millis(200)).await;

    assert!(mailbox.sent.lock().unwrap().is_empty());
}

#[tokio::test]
async fn a_notice_the_mail_server_refused_is_retried_on_a_later_poll() {
    let dir = tempfile::tempdir().unwrap();
    let state = tempfile::tempdir().unwrap();
    write_status(dir.path(), "rolledBack", "2026-10-04T04:12:00Z");
    let mailbox = Arc::new(Mailbox::default());
    mailbox.refuse.store(true, Ordering::SeqCst);
    let state_path = state.path().join("sentinel.json");
    serve_state(dir.path(), mailbox.clone(), Seen::default(), &state_path).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert!(!state_path.exists());

    mailbox.refuse.store(false, Ordering::SeqCst);

    assert_eq!(sent_count(&mailbox, 1).await, 1);
}
