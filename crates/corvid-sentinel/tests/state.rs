use corvid_sentinel::{ApiKeyIdentity, CredentialKind, Identity, NoticeKind, Seen, open, save};

fn scratch() -> (tempfile::TempDir, std::path::PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("sentinel.json");
    (dir, path)
}

#[test]
fn a_missing_state_file_is_a_first_run() {
    let (_dir, path) = scratch();
    assert_eq!(open(&path).unwrap().identities.len(), 0);
}

#[test]
fn a_saved_identity_round_trips() {
    let (_dir, path) = scratch();
    let mut seen = Seen::default();
    seen.identities.insert(Identity {
        account: "alice@corvid.test".to_owned(),
        kind: CredentialKind::AppPassword,
        listener: "imaps".to_owned(),
        remote_ip: "203.0.113.9".to_owned(),
    });
    seen.too_many_attempts_notified
        .insert("203.0.113.9".to_owned(), 100);
    save(&path, &seen).unwrap();

    let reopened = open(&path).unwrap();
    assert_eq!(reopened.identities, seen.identities);
    assert_eq!(
        reopened.too_many_attempts_notified,
        seen.too_many_attempts_notified
    );
}

#[test]
fn a_truncated_state_file_refuses_to_start_fresh() {
    let (_dir, path) = scratch();
    save(&path, &Seen::default()).unwrap();
    let whole = std::fs::read_to_string(&path).unwrap();
    std::fs::write(&path, &whole[..whole.len() - 5]).unwrap();

    assert!(open(&path).is_err());
}

#[test]
fn a_saved_feed_round_trips_with_its_counter() {
    let (_dir, path) = scratch();
    let mut seen = Seen::default();
    seen.push_notice(
        NoticeKind::Ban,
        "2026-09-27T02:08:58Z".to_owned(),
        "Address banned".to_owned(),
        "203.0.113.9 was banned".to_owned(),
    );
    seen.update_noticed = Some("2026-09-27T03:00:00Z".to_owned());
    save(&path, &seen).unwrap();

    let reopened = open(&path).unwrap();
    assert_eq!(reopened.notices, seen.notices);
    assert_eq!(reopened.last_notice_id, 1);
    assert_eq!(reopened.update_noticed, seen.update_noticed);
}

#[test]
fn the_feed_keeps_only_the_newest_two_hundred() {
    let mut seen = Seen::default();
    for n in 1..=250 {
        seen.push_notice(
            NoticeKind::SignIn,
            String::new(),
            n.to_string(),
            String::new(),
        );
    }

    assert_eq!(seen.notices.len(), 200);
    assert_eq!(seen.notices.first().unwrap().id, 51);
    assert_eq!(seen.notices.last().unwrap().id, 250);
}

#[test]
fn a_file_from_the_previous_schema_keeps_its_history_and_starts_an_empty_feed() {
    let (_dir, path) = scratch();
    let previous = serde_json::json!({
        "identities": [{
            "account": "alice@corvid.test",
            "kind": "AppPassword",
            "listener": "imaps",
            "remote_ip": "203.0.113.9",
        }],
        "too_many_attempts_notified": {"203.0.113.9": 100},
    });
    corvid_store::save(&path, 1, &previous).unwrap();

    let migrated = open(&path).unwrap();

    assert_eq!(migrated.identities.len(), 1);
    assert_eq!(migrated.too_many_attempts_notified["203.0.113.9"], 100);
    assert!(migrated.notices.is_empty());
    assert_eq!(migrated.last_notice_id, 0);
}

#[test]
fn a_file_holding_an_api_key_identity_reads_under_the_previous_binary_shape() {
    #[derive(serde::Deserialize)]
    #[allow(dead_code)]
    struct SeenBefore {
        identities: std::collections::BTreeSet<Identity>,
        too_many_attempts_notified: std::collections::BTreeMap<String, i64>,
        notices: Vec<corvid_sentinel::Notice>,
        last_notice_id: u64,
        update_noticed: Option<String>,
    }
    let (_dir, path) = scratch();
    let mut seen = Seen::default();
    seen.api_keys.insert(ApiKeyIdentity {
        account: "admin".to_owned(),
        listener: "http".to_owned(),
        remote_ip: "10.88.0.1".to_owned(),
    });
    save(&path, &seen).unwrap();

    let old: SeenBefore = corvid_store::load(&path, 2).unwrap().unwrap();

    assert!(old.identities.is_empty());
}

#[test]
fn a_file_without_api_key_tracking_opens_with_none() {
    let (_dir, path) = scratch();
    let before = serde_json::json!({
        "identities": [],
        "too_many_attempts_notified": {},
        "notices": [],
        "last_notice_id": 0,
        "update_noticed": null,
    });
    corvid_store::save(&path, 2, &before).unwrap();

    assert!(open(&path).unwrap().api_keys.is_empty());
}
