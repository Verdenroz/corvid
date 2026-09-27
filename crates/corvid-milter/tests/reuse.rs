use std::future;
use std::sync::Arc;

use corvid_milter::daemon::callbacks;
use corvid_rewrite::Rewriter;
use corvid_sign::Signer;
use indymilter::Config;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

const MTA_ACTIONS: u32 = 0xff;
const MTA_OPTS: u32 = 0x42;

struct Client {
    stream: TcpStream,
}

impl Client {
    async fn send(&mut self, kind: u8, payload: &[u8]) {
        let len = (payload.len() + 1) as u32;
        self.stream.write_all(&len.to_be_bytes()).await.unwrap();
        self.stream.write_all(&[kind]).await.unwrap();
        self.stream.write_all(payload).await.unwrap();
    }

    async fn read(&mut self) -> (u8, Vec<u8>) {
        let mut header = [0u8; 4];
        self.stream.read_exact(&mut header).await.unwrap();
        let len = u32::from_be_bytes(header) as usize;
        let mut body = vec![0u8; len];
        self.stream.read_exact(&mut body).await.unwrap();
        (body[0], body[1..].to_vec())
    }

    async fn command(&mut self, kind: u8, payload: &[u8]) {
        self.send(kind, payload).await;
        self.read().await;
    }

    /// Sends one message and returns the replacement body and the added header values.
    async fn message(&mut self, headers: &[(&str, &str)], body: &str) -> (Vec<u8>, Vec<String>) {
        self.message_to(&["<tester@corvid.test>"], headers, body)
            .await
    }

    async fn message_to(
        &mut self,
        recipients: &[&str],
        headers: &[(&str, &str)],
        body: &str,
    ) -> (Vec<u8>, Vec<String>) {
        self.command(b'M', b"<sender@t.example>\0").await;
        for recipient in recipients {
            let mut payload = recipient.as_bytes().to_vec();
            payload.push(0);
            self.command(b'R', &payload).await;
        }
        self.command(b'T', b"").await;
        for (name, value) in headers {
            let mut payload = Vec::new();
            payload.extend_from_slice(name.as_bytes());
            payload.push(0);
            payload.extend_from_slice(value.as_bytes());
            payload.push(0);
            self.command(b'L', &payload).await;
        }

        // Stalwart hands the whole message over in the body chunk, headers included.
        let mut chunk = String::new();
        for (name, value) in headers {
            chunk.push_str(&format!("{name}: {value}\r\n"));
        }
        chunk.push_str("\r\n");
        chunk.push_str(body);
        self.command(b'B', chunk.as_bytes()).await;

        self.send(b'E', b"").await;
        let mut replacement = Vec::new();
        let mut added = Vec::new();
        loop {
            let (kind, payload) = self.read().await;
            match kind {
                b'b' => replacement.extend_from_slice(&payload),
                b'h' => {
                    let text = String::from_utf8_lossy(&payload);
                    added.push(text.split('\0').nth(1).unwrap_or_default().to_owned());
                }
                _ => break,
            }
        }
        (replacement, added)
    }
}

async fn connect() -> Client {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let rewriter = Arc::new(Rewriter::new(
        Signer::new(b"reuse".to_vec()),
        "https://img.test",
    ));
    tokio::spawn(async move {
        indymilter::run(
            listener,
            callbacks(rewriter),
            Config::default(),
            future::pending::<()>(),
        )
        .await
    });

    let mut client = Client {
        stream: TcpStream::connect(addr).await.unwrap(),
    };
    let mut optneg = vec![b'O'];
    optneg.extend_from_slice(&6u32.to_be_bytes());
    optneg.extend_from_slice(&MTA_ACTIONS.to_be_bytes());
    optneg.extend_from_slice(&MTA_OPTS.to_be_bytes());
    client
        .stream
        .write_all(&(optneg.len() as u32).to_be_bytes())
        .await
        .unwrap();
    client.stream.write_all(&optneg).await.unwrap();
    client.read().await;

    let mut conn = Vec::from(&b"localhost\0"[..]);
    conn.push(b'4');
    conn.extend_from_slice(&25u16.to_be_bytes());
    conn.extend_from_slice(b"127.0.0.1\0");
    client.command(b'C', &conn).await;
    client
}

fn html(host: &str) -> String {
    format!(
        r#"<html><body><img src="https://px.{host}/t.gif" width="1" height="1"><img src="https://cdn.{host}/hero.png"></body></html>"#
    )
}

// Stalwart opens a milter connection per message, so only this test reaches the per-message
// reset that other MTAs, which reuse connections, depend on.
#[tokio::test]
async fn a_second_message_on_one_connection_does_not_inherit_the_first() {
    let mut client = connect().await;
    let headers = [("From", "sender@t.example"), ("Content-Type", "text/html")];

    let (first, _) = client.message(&headers, &html("one.example")).await;
    let (second, added) = client.message(&headers, &html("two.example")).await;

    let first = String::from_utf8(first).unwrap();
    let second = String::from_utf8(second).unwrap();

    assert!(first.contains("img.test/i/"), "first message was rewritten");
    assert!(
        !second.is_empty(),
        "second message produced a replacement body"
    );
    assert!(
        !second.contains("one.example"),
        "second message inherited the first's body"
    );
    assert!(
        !second.contains("From: sender"),
        "header block leaked into the body"
    );
    assert_eq!(
        added.first().map(String::as_str),
        Some("stripped=1 proxied=1 hosts=2 trackers=1 tracker-hosts=px.two.example"),
        "accounting must not carry over between messages"
    );
}

#[tokio::test]
async fn a_third_message_still_reports_only_its_own_hosts() {
    let mut client = connect().await;
    let headers = [("From", "sender@t.example"), ("Content-Type", "text/html")];

    client.message(&headers, &html("one.example")).await;
    client.message(&headers, &html("two.example")).await;
    let (body, added) = client.message(&headers, &html("three.example")).await;

    let body = String::from_utf8(body).unwrap();
    assert!(!body.contains("one.example") && !body.contains("two.example"));
    assert_eq!(
        added.first().map(String::as_str),
        Some("stripped=1 proxied=1 hosts=2 trackers=1 tracker-hosts=px.three.example")
    );
}

#[tokio::test]
async fn a_message_to_several_recipients_is_rewritten_once() {
    let mut client = connect().await;
    let headers = [("From", "sender@t.example"), ("Content-Type", "text/html")];

    let (body, added) = client
        .message_to(
            &["<one@corvid.test>", "<two@corvid.test>"],
            &headers,
            &html("multi.example"),
        )
        .await;

    let body = String::from_utf8(body).unwrap();
    assert!(body.contains("img.test/i/"));
    assert!(
        !body.contains("px.multi.example"),
        "the pixel is stripped once, not per recipient"
    );
    assert_eq!(
        added,
        ["stripped=1 proxied=1 hosts=2 trackers=1 tracker-hosts=px.multi.example"],
        "one header, one accounting"
    );
}
