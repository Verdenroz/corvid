use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use corvid_labeler::{Candidate, Exit, Suggest};
use hmac::{Hmac, KeyInit, Mac};
use serde_json::{Value, json};
use sha2::Sha256;

const SECRET: &str = "scribe secret";

struct Received {
    path: String,
    signature: String,
    body: Vec<u8>,
}

/// Answers one request with `status` and returns what it carried.
fn stub(status: u16) -> (String, thread::JoinHandle<Received>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut raw = Vec::new();
        let mut chunk = [0; 1024];
        let (head, length) = loop {
            let read = stream.read(&mut chunk).unwrap();
            raw.extend_from_slice(&chunk[..read]);
            let Some(end) = raw.windows(4).position(|window| window == b"\r\n\r\n") else {
                continue;
            };
            let head = String::from_utf8(raw[..end].to_vec()).unwrap();
            let length = header(&head, "content-length").parse::<usize>().unwrap();
            if raw.len() >= end + 4 + length {
                break (head, length);
            }
        };
        let start = raw.len() - length;
        stream
            .write_all(format!("HTTP/1.1 {status} X\r\ncontent-length: 0\r\n\r\n").as_bytes())
            .unwrap();
        Received {
            path: head.split(' ').nth(1).unwrap().to_owned(),
            signature: header(&head, "x-signature"),
            body: raw[start..].to_vec(),
        }
    });
    (url, handle)
}

fn header(head: &str, name: &str) -> String {
    head.lines()
        .filter_map(|line| line.split_once(':'))
        .find(|(key, _)| key.eq_ignore_ascii_case(name))
        .map(|(_, value)| value.trim().to_owned())
        .unwrap()
}

fn candidates() -> Vec<Candidate> {
    vec![
        Candidate {
            id: "a".to_owned(),
            key: "list:news.example".to_owned(),
            outcome: Exit::Folder("mb7".to_owned()),
            settled: 6,
            hits: 5,
        },
        Candidate {
            id: "b".to_owned(),
            key: "from:bob@x.test".to_owned(),
            outcome: Exit::Archive,
            settled: 4,
            hits: 4,
        },
    ]
}

#[test]
fn candidates_are_posted_signed_over_the_exact_body() {
    let (url, received) = stub(204);

    Suggest::connect(&url, SECRET)
        .unwrap()
        .post(1_000, &candidates())
        .unwrap();

    let received = received.join().unwrap();
    let mut mac = <Hmac<Sha256>>::new_from_slice(SECRET.as_bytes()).unwrap();
    mac.update(&received.body);
    assert_eq!(received.path, "/internal/scribe/suggestions");
    assert_eq!(
        received.signature,
        STANDARD.encode(mac.finalize().into_bytes())
    );
    let body: Value = serde_json::from_slice(&received.body).unwrap();
    assert_eq!(
        body,
        json!({
            "sent_at": 1_000,
            "candidates": [
                {"id": "a", "key": "list:news.example", "outcome": "folder:mb7", "settled": 6, "hits": 5},
                {"id": "b", "key": "from:bob@x.test", "outcome": "archive", "settled": 4, "hits": 4},
            ],
        })
    );
}

#[test]
fn a_refusal_from_the_sentinel_is_an_error() {
    let (url, received) = stub(401);

    let result = Suggest::connect(&url, SECRET)
        .unwrap()
        .post(1_000, &candidates());

    received.join().unwrap();
    assert!(result.is_err());
}

#[test]
fn an_unreachable_sentinel_is_an_error() {
    let url = {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        format!("http://{}", listener.local_addr().unwrap())
    };

    let result = Suggest::connect(&url, SECRET)
        .unwrap()
        .post(1_000, &candidates());

    assert!(result.is_err());
}
