use std::path::{Path, PathBuf};

use corvid_ingest::{load, open};

fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

// Written under a bucketing that no longer exists, so it is kept rather than rebuilt from
// today's format.
#[test]
fn a_real_stale_state_file_is_refused() {
    let error = open(&fixture("stale-schema.state"))
        .err()
        .expect("a stale schema must refuse");

    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert!(error.to_string().contains("different schema"));
}

#[test]
fn a_real_state_file_from_the_current_schema_loads() {
    let ingest = load(&fixture("replayed-cursor.state"))
        .unwrap()
        .expect("state is present");

    assert_eq!(
        ingest.cursor(),
        Some("s3ya"),
        "the halt declined to advance past the bad cursor"
    );
    assert!(ingest.has_counted("nqaaaadm"));
    assert_eq!(ingest.census().unseen(), 2);
}
