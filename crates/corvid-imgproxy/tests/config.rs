use std::process::{Command, Output};

fn imgproxy(config: &str) -> Output {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.yaml");
    std::fs::write(&path, config).unwrap();
    Command::new(env!("CARGO_BIN_EXE_corvid-imgproxy"))
        .env_clear()
        .env("CORVID_CONFIG", &path)
        .env("CORVID_IMGPROXY_SECRET", "secret")
        .output()
        .unwrap()
}

#[test]
fn a_malformed_setting_exits_78_naming_it() {
    for (config, key) in [
        ("imgproxy:\n  max_bytes: 5M\n", "max_bytes"),
        ("imgproxy:\n  cache_entries: 0\n", "cache_entries"),
    ] {
        let out = imgproxy(config);
        assert_eq!(out.status.code(), Some(78), "{config}");
        assert!(
            String::from_utf8_lossy(&out.stderr).contains(key),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
    }
}
