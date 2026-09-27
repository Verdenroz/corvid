use std::fs;

use corvid_store::{open, save};

#[test]
fn a_missing_file_is_a_first_run() {
    let dir = tempfile::tempdir().unwrap();
    let state: Vec<u32> = open(&dir.path().join("state.json"), 1).unwrap();
    assert!(state.is_empty());
}

#[test]
fn saved_state_reads_back() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("state.json");
    save(&path, 1, &vec![7u32]).unwrap();
    assert_eq!(open::<Vec<u32>>(&path, 1).unwrap(), vec![7]);
}

#[test]
fn a_path_that_cannot_be_read_is_not_a_first_run() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("state.json");
    fs::create_dir(&path).unwrap();
    assert!(open::<Vec<u32>>(&path, 1).is_err());
}

#[test]
fn a_file_that_is_not_utf8_is_not_a_first_run() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("state.json");
    fs::write(&path, [0xff, 0xfe, 0x00]).unwrap();
    assert!(open::<Vec<u32>>(&path, 1).is_err());
}
