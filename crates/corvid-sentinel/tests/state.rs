use corvid_sentinel::{CredentialKind, Identity, Seen, open, save};

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
