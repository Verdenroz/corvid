mod common;

use std::path::Path;
use std::time::Duration;

use corvid_sentinel::{Config, NoticeKind, open};

async fn start(dir: &Path) {
    let config = Config {
        update_poll: Duration::from_millis(20),
        ..common::config(&dir.join("sentinel.json"), dir)
    };
    common::serve(config).await;
}

fn status(result: &str, checked_at: &str, detail: &str) -> String {
    report(result, checked_at, detail, false)
}

fn report(result: &str, checked_at: &str, detail: &str, busy: bool) -> String {
    format!(
        r#"{{"running":"v1","latest":"v2","checkedAt":"{checked_at}","result":"{result}","detail":"{detail}","auto":true,"busy":{busy}}}"#
    )
}

async fn settle() {
    tokio::time::sleep(Duration::from_millis(300)).await;
}

fn notices(dir: &Path) -> Vec<corvid_sentinel::Notice> {
    open(&dir.join("sentinel.json")).unwrap().notices
}

#[tokio::test]
async fn a_rolled_back_update_becomes_one_notice_however_often_it_is_polled() {
    let dir = tempfile::tempdir().unwrap();
    let body = status(
        "rolledBack",
        "2026-10-04T01:00:00Z",
        "v2 failed its health check",
    );
    std::fs::write(dir.path().join("status.json"), body).unwrap();
    start(dir.path()).await;

    settle().await;

    let found = notices(dir.path());
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].kind, NoticeKind::Update);
    assert_eq!(found[0].title, "Update rolled back");
    assert_eq!(found[0].detail, "v2 failed its health check");
    assert_eq!(found[0].at, "2026-10-04T01:00:00Z");
}

#[tokio::test]
async fn a_later_failure_with_a_new_check_time_is_a_second_notice() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("status.json");
    std::fs::write(
        &path,
        status("failed", "2026-10-04T01:00:00Z", "pull failed"),
    )
    .unwrap();
    start(dir.path()).await;
    settle().await;

    std::fs::write(
        &path,
        status("failed", "2026-10-04T02:00:00Z", "pull failed"),
    )
    .unwrap();
    settle().await;

    assert_eq!(notices(dir.path()).len(), 2);
}

#[tokio::test]
async fn updated_current_and_skipped_results_add_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("status.json");
    start(dir.path()).await;

    for (n, result) in ["updated", "current", "skipped"].into_iter().enumerate() {
        std::fs::write(
            &path,
            status(result, &format!("2026-10-04T0{n}:00:00Z"), ""),
        )
        .unwrap();
        settle().await;
    }

    assert!(!dir.path().join("sentinel.json").exists());
}

#[tokio::test]
async fn an_update_that_passed_with_something_stale_off_the_host_becomes_a_notice() {
    let dir = tempfile::tempdir().unwrap();
    let detail = "https://example.com/.well-known/assetlinks.json serves a different document";
    std::fs::write(
        dir.path().join("status.json"),
        status("updated", "2026-10-04T01:00:00Z", detail),
    )
    .unwrap();
    start(dir.path()).await;
    settle().await;

    let found = notices(dir.path());
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].title, "Out of date off this host");
    assert_eq!(found[0].detail, detail);
}

#[tokio::test]
async fn a_skipped_check_with_a_detail_adds_nothing() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(
        dir.path().join("status.json"),
        status("skipped", "2026-10-04T01:00:00Z", "v2 is available"),
    )
    .unwrap();
    start(dir.path()).await;
    settle().await;

    assert!(!dir.path().join("sentinel.json").exists());
}

#[tokio::test]
async fn a_busy_report_is_never_noticed_even_when_it_repeats_a_failure() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("status.json");
    std::fs::write(
        &path,
        report("failed", "2026-10-04T01:00:00Z", "stopped", true),
    )
    .unwrap();
    start(dir.path()).await;
    settle().await;

    assert!(!dir.path().join("sentinel.json").exists());
}

#[tokio::test]
async fn an_unreadable_status_does_not_stop_the_watcher() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("status.json");
    std::fs::write(&path, "not json").unwrap();
    start(dir.path()).await;
    settle().await;

    std::fs::write(
        &path,
        status("failed", "2026-10-04T01:00:00Z", "pull failed"),
    )
    .unwrap();
    settle().await;

    assert_eq!(notices(dir.path()).len(), 1);
}
