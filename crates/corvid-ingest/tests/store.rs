use std::collections::BTreeSet;

use corvid_ingest::{Ingest, load, save};
use corvid_label::{Abstain, SenderKey};
use sha2::Digest;

fn acme() -> SenderKey {
    SenderKey::Domain("acme.test".into())
}

fn owned(list: &[&str]) -> Vec<String> {
    list.iter().map(|l| (*l).to_owned()).collect()
}

fn populated() -> Ingest {
    let mut ingest = Ingest::default();
    for day in 0..8 {
        ingest.learn(
            &format!("seed{day}"),
            &acme(),
            &owned(&["receipts"]),
            day as f64,
        );
    }
    ingest.label("m1", &acme(), 8.0);
    ingest.set_cursor("sxmaq");
    ingest
}

#[test]
fn state_survives_a_round_trip() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    let before = populated();
    save(&path, &before).unwrap();

    let after = load(&path).unwrap().expect("state was written");

    assert_eq!(after.cursor(), Some("sxmaq"));
    assert_eq!(after.census().messages(), 1);
    assert_eq!(after.census().distinct_senders(), 1);
}

// The process that dies mid-write is the one that would have held the dedup set in memory.
#[test]
fn the_replay_detector_survives_a_restart() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    save(&path, &populated()).unwrap();

    let mut restarted = load(&path).unwrap().unwrap();
    restarted.label("m1", &acme(), 8.0);

    assert_eq!(
        restarted.repeats(),
        1,
        "a replayed message is still recognised after a restart"
    );
    assert_eq!(restarted.census().messages(), 1, "and still counts once");
}

#[test]
fn a_learned_correction_survives_a_restart() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    let mut before = populated();
    before.reconcile("seed0", &acme(), &BTreeSet::from(["work".to_owned()]), 8.0);
    save(&path, &before).unwrap();

    let mut after = load(&path).unwrap().unwrap();
    after.reconcile("seed0", &acme(), &BTreeSet::from(["work".to_owned()]), 8.0);

    assert_eq!(
        after.corrections(),
        1,
        "the same correction must not be learned twice"
    );
}

// The server accepts a truncated cursor and replays, so a half-written file must not read back.
#[test]
fn a_truncated_state_file_is_refused() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    save(&path, &populated()).unwrap();

    let whole = std::fs::read_to_string(&path).unwrap();
    std::fs::write(&path, &whole[..whole.len() - 20]).unwrap();

    assert!(
        load(&path).is_err(),
        "a partial write must not read back as state"
    );
}

#[test]
fn a_missing_state_file_is_a_fresh_start() {
    let directory = tempfile::tempdir().unwrap();

    assert!(
        load(&directory.path().join("absent.json"))
            .unwrap()
            .is_none()
    );
}

#[test]
fn the_prior_still_decides_the_same_way_after_a_restart() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    let mut before = populated();
    let expected = before.label("m2", &acme(), 8.0);
    save(&path, &before).unwrap();

    let mut after = load(&path).unwrap().unwrap();

    assert_eq!(after.label("m3", &acme(), 8.0), expected);
    assert_eq!(after.census().abstentions(Abstain::UnseenSender), 0);
}

#[test]
fn a_file_from_another_schema_is_refused() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    save(&path, &populated()).unwrap();

    let contents = std::fs::read_to_string(&path).unwrap();
    let (_, body) = contents.split_once('\n').unwrap();
    let (_, payload) = body.split_once('\n').unwrap();
    let forward = format!("99\n{payload}");
    let checksum = hex::encode(sha2::Sha256::digest(forward.as_bytes()));
    std::fs::write(&path, format!("{checksum}\n{forward}")).unwrap();

    assert!(
        load(&path).is_err(),
        "a valid checksum over the wrong schema is still wrong"
    );
}

// Treating a corrupt file as fresh would discard the prior and replay the mailbox into an
// empty census, which looks like a clean start.
#[test]
fn a_corrupt_file_stops_the_run_rather_than_starting_over() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("ingest.json");
    save(&path, &populated()).unwrap();
    let whole = std::fs::read_to_string(&path).unwrap();
    std::fs::write(&path, &whole[..whole.len() - 20]).unwrap();

    assert!(corvid_ingest::open(&path).is_err());
}

#[test]
fn a_first_run_starts_from_nothing() {
    let directory = tempfile::tempdir().unwrap();
    let ingest = corvid_ingest::open(&directory.path().join("absent.json")).unwrap();

    assert_eq!(ingest.cursor(), None);
    assert_eq!(ingest.census().messages(), 0);
}
