use std::process::{Command, Output};

const LABELER: &str = "labeler:\n  jmap_url: http://127.0.0.1:9\n  jmap_user: u\n  state: /nonexistent/labeler.json\n";

fn labeler(config: &str, password: Option<&str>) -> Output {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.yaml");
    std::fs::write(&path, config).unwrap();
    let mut command = Command::new(env!("CARGO_BIN_EXE_corvid-labeler"));
    command.env_clear().env("CORVID_CONFIG", &path);
    if let Some(password) = password {
        command.env("CORVID_JMAP_PASSWORD", password);
    }
    command.output().unwrap()
}

fn refused(out: &Output, naming: &str) {
    assert_eq!(out.status.code(), Some(78));
    assert!(
        String::from_utf8_lossy(&out.stderr).contains(naming),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
}

#[test]
fn a_malformed_setting_exits_78_naming_it() {
    let config = format!("{LABELER}  interval_secs: 2O\n");
    refused(&labeler(&config, Some("p")), "interval_secs");
}

#[test]
fn a_confidence_above_a_hundred_percent_exits_78() {
    let config = format!(
        "{LABELER}  model:\n    url: http://127.0.0.1:9\n    name: m\n    confidence: 150\n"
    );
    refused(&labeler(&config, Some("p")), "percentage");
}

#[test]
fn a_config_without_a_labeler_section_exits_78_saying_so() {
    refused(
        &labeler("milter:\n  proxy_base: http://p\n", Some("p")),
        "`labeler`",
    );
}

#[test]
fn a_missing_password_exits_78_naming_it() {
    refused(&labeler(LABELER, None), "CORVID_JMAP_PASSWORD");
}

#[test]
fn a_missing_config_file_exits_78_naming_the_path() {
    let out = Command::new(env!("CARGO_BIN_EXE_corvid-labeler"))
        .env_clear()
        .env("CORVID_CONFIG", "/nonexistent/config.yaml")
        .output()
        .unwrap();
    refused(&out, "/nonexistent/config.yaml");
}
