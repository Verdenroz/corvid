use std::process::{Command, Output};

fn run(config: &str, args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_corvid-config"))
        .env_clear()
        .env("CORVID_CONFIG", config)
        .args(args)
        .output()
        .unwrap()
}

fn template() -> String {
    format!("{}/../../deploy/config.yaml", env!("CARGO_MANIFEST_DIR"))
}

#[test]
fn shell_prints_the_server_section_of_the_template() {
    let out = run(&template(), &["shell"]);

    assert_eq!(out.status.code(), Some(0));
    let keys: Vec<String> = String::from_utf8(out.stdout)
        .unwrap()
        .lines()
        .map(|l| l.split('=').next().unwrap().to_owned())
        .collect();
    assert_eq!(
        keys,
        [
            "MAIL_DOMAIN",
            "MAIL_HOSTNAME",
            "MAIL_USER",
            "ACME_CONTACT",
            "WEB_PORT",
            "HOST_ZONE",
            "PUBLISH_MTA_STS",
            "PUBLISH_TLSA",
            "CLIENT_SOURCES"
        ]
    );
}

#[test]
fn a_config_that_does_not_parse_exits_78() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.yaml");
    std::fs::write(&path, "server:\n  web_port: 8443\n").unwrap();

    for command in ["check", "shell"] {
        assert_eq!(
            run(path.to_str().unwrap(), &[command]).status.code(),
            Some(78)
        );
    }
}

#[test]
fn an_unknown_command_exits_64() {
    assert_eq!(run(&template(), &["render"]).status.code(), Some(64));
}
