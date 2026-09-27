use std::net::TcpListener;
use std::process::{Command, Stdio};
use std::thread::sleep;
use std::time::{Duration, Instant};

#[test]
fn sigterm_stops_it_promptly() {
    // Accepts and never answers, so the labeler stays blocked fetching the session.
    let silent = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = silent.local_addr().unwrap().port();
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config.yaml");
    let state = dir.path().join("labeler.json");
    let config = format!(
        "labeler:\n  jmap_url: http://127.0.0.1:{port}\n  jmap_user: u\n  state: {}\n",
        state.display()
    );
    std::fs::write(&path, config).unwrap();
    let mut child = Command::new(env!("CARGO_BIN_EXE_corvid-labeler"))
        .env_clear()
        .env("CORVID_CONFIG", &path)
        .env("CORVID_JMAP_PASSWORD", "p")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    sleep(Duration::from_millis(500));
    assert!(
        child.try_wait().unwrap().is_none(),
        "exited before the signal"
    );
    // As PID 1 in a container, a signal with no handler is ignored rather than fatal.
    let status = std::fs::read_to_string(format!("/proc/{}/status", child.id())).unwrap();
    let caught = status
        .lines()
        .find_map(|l| l.strip_prefix("SigCgt:"))
        .unwrap()
        .trim();
    let mask = u64::from_str_radix(caught, 16).unwrap();
    assert!(
        mask & (1 << (15 - 1)) != 0,
        "no SIGTERM handler, so PID 1 would ignore it"
    );

    Command::new("kill")
        .args(["-TERM", &child.id().to_string()])
        .status()
        .unwrap();
    let sent = Instant::now();
    while child.try_wait().unwrap().is_none() {
        if sent.elapsed() > Duration::from_secs(3) {
            child.kill().unwrap();
            panic!("still running 3 s after SIGTERM");
        }
        sleep(Duration::from_millis(50));
    }
}
