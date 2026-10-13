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
            "ADMIN_USER",
            "SENTINEL_URL",
            "ISSUER_URL",
            "PUBLISH_MTA_STS",
            "PUBLISH_TLSA",
            "PUBLISH_ASSETLINKS",
            "ASSETLINKS",
            "MTA_STS_MODE",
            "MTA_STS_ID",
            "CLIENT_SOURCES",
            "UPDATE_AUTO"
        ]
    );
}

#[test]
fn mta_sts_policy_prints_the_templates_rendered_policy() {
    let out = run(&template(), &["mta-sts-policy"]);

    assert_eq!(out.status.code(), Some(0));
    let text = String::from_utf8(out.stdout).unwrap();
    assert!(
        text.starts_with("version: STSv1\nmode: testing\nmx: "),
        "{text}"
    );
}

#[test]
fn a_config_that_does_not_parse_exits_78() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.yaml");
    std::fs::write(&path, "server:\n  web_port: 8443\n").unwrap();

    for command in ["check", "shell", "mta-sts-policy"] {
        assert_eq!(
            run(path.to_str().unwrap(), &[command]).status.code(),
            Some(78)
        );
    }
}

#[test]
fn assetlinks_prints_the_official_app_for_the_template() {
    let out = run(&template(), &["assetlinks"]);

    assert_eq!(out.status.code(), Some(0));
    let text = String::from_utf8(out.stdout).unwrap();
    assert!(
        text.contains(r#""package_name":"com.verdenroz.corvid""#),
        "{text}"
    );
    assert!(
        text.contains("delegate_permission/common.get_login_creds"),
        "{text}"
    );
}

#[test]
fn shell_carries_the_assetlinks_document_on_one_line() {
    let shell = String::from_utf8(run(&template(), &["shell"]).stdout).unwrap();
    let document = String::from_utf8(run(&template(), &["assetlinks"]).stdout).unwrap();

    let line = shell
        .lines()
        .find_map(|l| l.strip_prefix("ASSETLINKS="))
        .unwrap();
    assert_eq!(line, document.trim_end());
    assert!(line.contains("com.verdenroz.corvid"), "{line}");
}

#[test]
fn an_unknown_command_exits_64() {
    assert_eq!(run(&template(), &["render"]).status.code(), Some(64));
}
