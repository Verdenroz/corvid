use std::io::{Read, Write};
use std::net::TcpListener;
use std::process::Command;
use std::thread;

fn exit_code(config: &str, password: Option<&str>) -> Option<i32> {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.yaml");
    std::fs::write(
        &path,
        config.replace("STATE", &dir.path().join("scribe.json").to_string_lossy()),
    )
    .unwrap();
    let mut command = Command::new(env!("CARGO_BIN_EXE_corvid-scribe"));
    command.env_clear().env("CORVID_CONFIG", &path);
    if let Some(password) = password {
        command
            .env("CORVID_JMAP_PASSWORD", password)
            .env("CORVID_SCRIBE_SECRET", "s");
    }
    command.output().unwrap().status.code()
}

fn config(jmap_url: &str) -> String {
    format!(
        "scribe:\n  jmap_url: {jmap_url}\n  jmap_user: u\n  state: STATE\n  sentinel_url: http://127.0.0.1:1\n  model:\n    url: http://127.0.0.1:1\n    name: m\n"
    )
}

fn closed_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

fn refusing_every_login() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    thread::spawn(move || {
        for mut stream in listener.incoming().flatten() {
            let _ = stream.read(&mut [0; 4096]);
            let _ = stream.write_all(
                b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            );
        }
    });
    port
}

#[test]
fn a_host_without_the_section_exits_78() {
    assert_eq!(exit_code("update:\n  auto: true\n", Some("p")), Some(78));
}

#[test]
fn a_missing_secret_exits_78() {
    let url = format!("http://127.0.0.1:{}", closed_port());
    assert_eq!(exit_code(&config(&url), None), Some(78));
}

#[test]
fn a_mail_server_not_listening_yet_exits_75_so_systemd_retries() {
    let url = format!("http://127.0.0.1:{}", closed_port());
    assert_eq!(exit_code(&config(&url), Some("p")), Some(75));
}

#[test]
fn a_refused_password_exits_1_so_it_is_not_retried_into_a_ban() {
    let url = format!("http://127.0.0.1:{}", refusing_every_login());
    assert_eq!(exit_code(&config(&url), Some("p")), Some(1));
}
