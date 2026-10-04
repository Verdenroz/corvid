use std::io::{Read, Write};
use std::net::TcpListener;
use std::process::Command;
use std::thread;

fn exit_code(jmap_url: &str) -> Option<i32> {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.yaml");
    let config = format!(
        "labeler:\n  jmap_url: {jmap_url}\n  jmap_user: u\n  state: {}\n",
        dir.path().join("labeler.json").display()
    );
    std::fs::write(&path, config).unwrap();
    Command::new(env!("CARGO_BIN_EXE_corvid-labeler"))
        .env_clear()
        .env("CORVID_CONFIG", &path)
        .env("CORVID_JMAP_PASSWORD", "p")
        .output()
        .unwrap()
        .status
        .code()
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
fn a_mail_server_not_listening_yet_exits_75_so_systemd_retries() {
    let url = format!("http://127.0.0.1:{}", closed_port());
    assert_eq!(exit_code(&url), Some(75));
}

#[test]
fn a_refused_password_exits_1_so_it_is_not_retried_into_a_ban() {
    let url = format!("http://127.0.0.1:{}", refusing_every_login());
    assert_eq!(exit_code(&url), Some(1));
}
