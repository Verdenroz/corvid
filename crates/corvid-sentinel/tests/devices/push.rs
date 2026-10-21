use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes128Gcm, Nonce};
use axum::Router;
use axum::body::Bytes;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode, Uri};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use corvid_sentinel::devices::{self, Devices};
use hkdf::Hkdf;
use p256::ecdh::diffie_hellman;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::elliptic_curve::sec1::ToSec1Point;
use p256::{PublicKey, SecretKey};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tokio::net::TcpListener;

use super::stalwart::{ARCHIVE, INBOX};
use super::{CONTACT, Rig, SECRET, rig_full};

pub(super) const BASE: &str = "https://fcm.googleapis.com/fcm/send";

/// What the fake FCM received.
pub(super) struct Posted {
    pub(super) target: String,
    pub(super) headers: HeaderMap,
    pub(super) body: Vec<u8>,
}

#[derive(Clone)]
pub(super) struct Fcm {
    pub(super) posted: Arc<Mutex<Vec<Posted>>>,
    status: Arc<AtomicU16>,
}

impl Fcm {
    pub(super) async fn start() -> (Self, String) {
        let fcm = Self {
            posted: Arc::default(),
            status: Arc::new(AtomicU16::new(200)),
        };
        let app = Router::new().fallback(receive).with_state(fcm.clone());
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        (fcm, url)
    }

    pub(super) fn count(&self) -> usize {
        self.posted.lock().unwrap().len()
    }

    pub(super) async fn wait_for(&self, count: usize) {
        for _ in 0..100 {
            if self.count() >= count {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        panic!("the fake FCM received {} pushes, not {count}", self.count());
    }
}

async fn receive(State(fcm): State<Fcm>, uri: Uri, headers: HeaderMap, body: Bytes) -> StatusCode {
    fcm.posted.lock().unwrap().push(Posted {
        target: uri.path_and_query().unwrap().to_string(),
        headers,
        body: body.to_vec(),
    });
    StatusCode::from_u16(fcm.status.load(Ordering::SeqCst)).unwrap()
}

/// A device's push keys, as the UnifiedPush connector makes them.
pub(super) struct Phone {
    secret: SecretKey,
    auth: [u8; 16],
}

impl Phone {
    pub(super) fn new() -> Self {
        Self {
            secret: SecretKey::from_slice(&[7; 32]).unwrap(),
            auth: [9; 16],
        }
    }

    pub(super) fn other() -> Self {
        Self {
            secret: SecretKey::from_slice(&[8; 32]).unwrap(),
            auth: [10; 16],
        }
    }

    pub(super) fn registration(&self, endpoint: &str) -> Value {
        json!({
            "endpoint": endpoint,
            "p256dh": URL_SAFE_NO_PAD.encode(self.secret.public_key().to_sec1_point(false).as_bytes()),
            "auth": URL_SAFE_NO_PAD.encode(self.auth),
        })
    }

    /// RFC 8291 from the receiving end: the sender's key and the salt come from the header.
    pub(super) fn decrypt(&self, body: &[u8]) -> Value {
        let salt = &body[..16];
        let key_length = body[20] as usize;
        let sender = PublicKey::from_sec1_bytes(&body[21..21 + key_length]).unwrap();
        let sealed = &body[21 + key_length..];
        let shared = diffie_hellman(self.secret.to_nonzero_scalar(), sender.as_affine());
        let mut key_info = b"WebPush: info\0".to_vec();
        key_info.extend_from_slice(self.secret.public_key().to_sec1_point(false).as_bytes());
        key_info.extend_from_slice(sender.to_sec1_point(false).as_bytes());
        let mut ikm = [0; 32];
        Hkdf::<Sha256>::new(Some(&self.auth), shared.raw_secret_bytes())
            .expand(&key_info, &mut ikm)
            .unwrap();
        let content = Hkdf::<Sha256>::new(Some(salt), &ikm);
        let mut key = [0; 16];
        let mut nonce = [0; 12];
        content
            .expand(b"Content-Encoding: aes128gcm\0", &mut key)
            .unwrap();
        content
            .expand(b"Content-Encoding: nonce\0", &mut nonce)
            .unwrap();
        let mut record = Aes128Gcm::new(&key.into())
            .decrypt(&Nonce::from(nonce), sealed)
            .unwrap();
        assert_eq!(
            record.pop(),
            Some(2),
            "a single record ends with the last-record delimiter"
        );
        serde_json::from_slice(&record).unwrap()
    }
}

struct PushRig {
    rig: Rig,
    fcm: Fcm,
}

async fn push_rig() -> PushRig {
    let (fcm, url) = Fcm::start().await;
    let rig = rig_full(Devices::default(), Some(&url)).await;
    // The watcher takes its baseline once the sentinel starts; mail before it is not pushed.
    tokio::time::sleep(Duration::from_millis(200)).await;
    PushRig { rig, fcm }
}

impl PushRig {
    async fn register(&self, name: &str, body: &Value) -> reqwest::Response {
        reqwest::Client::new()
            .put(format!("{}/corvid/devices/{name}/push", self.rig.url))
            .json(body)
            .send()
            .await
            .unwrap()
    }

    async fn unregister(&self, name: &str) -> reqwest::Response {
        reqwest::Client::new()
            .delete(format!("{}/corvid/devices/{name}/push", self.rig.url))
            .send()
            .await
            .unwrap()
    }

    async fn arrived(&self) -> reqwest::Response {
        let body = json!({"events": [{"id": "1", "createdAt": "2026-10-11T15:19:07Z",
            "type": "message-ingest.ham", "data": {"accountId": 2, "documentId": 1, "mailboxId": [0]}}]})
        .to_string();
        reqwest::Client::new()
            .post(format!("{}/webhook/mail", self.rig.url))
            .header(
                "X-Signature",
                super::common::signature(SECRET, body.as_bytes()),
            )
            .body(body)
            .send()
            .await
            .unwrap()
    }

    fn pushes(&self) -> Option<devices::Push> {
        devices::open(&self.rig.state)
            .unwrap()
            .approved
            .get("phone")
            .and_then(|device| device.push.clone())
    }
}

/// An open SSE stream, read one event at a time.
struct Stream {
    response: reqwest::Response,
    pending: String,
}

impl Stream {
    async fn open(rig: &PushRig, token: &str, last: Option<&str>) -> Self {
        let mut request = reqwest::Client::new()
            .get(format!("{}/corvid/push/stream", rig.rig.url))
            .bearer_auth(token);
        if let Some(last) = last {
            request = request.header("Last-Event-ID", last);
        }
        let response = request.send().await.unwrap();
        assert_eq!(response.status(), 200);
        assert_eq!(response.headers()["content-type"], "text/event-stream");
        Self {
            response,
            pending: String::new(),
        }
    }

    /// The next `push` event as its id and decoded body, or `None` once the server ends the stream.
    async fn next(&mut self) -> Option<(String, Vec<u8>)> {
        loop {
            if let Some((block, rest)) = self.pending.split_once("\n\n") {
                let block = block.to_owned();
                self.pending = rest.to_owned();
                let field = |name: &str| {
                    block
                        .lines()
                        .find_map(|line| line.strip_prefix(&format!("{name}: ")))
                        .map(str::to_owned)
                };
                if let (Some(id), Some(data)) = (field("id"), field("data")) {
                    assert_eq!(field("event").as_deref(), Some("push"));
                    return Some((id, URL_SAFE_NO_PAD.decode(data).unwrap()));
                }
                continue;
            }
            let chunk = tokio::time::timeout(Duration::from_secs(5), self.response.chunk())
                .await
                .expect("the stream went quiet")
                .unwrap()?;
            self.pending.push_str(core::str::from_utf8(&chunk).unwrap());
        }
    }
}

impl PushRig {
    async fn register_stream(&self, name: &str, phone: &Phone) -> String {
        let response = self
            .register(name, &phone.registration("corvid-stream"))
            .await;
        assert_eq!(response.status(), 200);
        response.json::<Value>().await.unwrap()["token"]
            .as_str()
            .unwrap()
            .to_owned()
    }
}

fn received(secs_ago: i64) -> String {
    corvid_sentinel::rfc3339(corvid_sentinel::now_secs() - secs_ago)
}

fn email(id: &str, mailbox: &str, keywords: Value) -> Value {
    email_received(id, mailbox, keywords, 0)
}

fn email_received(id: &str, mailbox: &str, keywords: Value, secs_ago: i64) -> Value {
    json!({"id": id, "from": [{"name": "Ada Lovelace", "email": "ada@outside.test"}],
           "subject": "Notes on the engine", "preview": "Note G is the long one. ".repeat(20),
           "receivedAt": received(secs_ago), "mailboxIds": {mailbox: true}, "keywords": keywords})
}

#[tokio::test]
async fn new_inbox_mail_reaches_the_device_encrypted_with_its_sender_subject_and_preview() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();
    let endpoint = format!("{BASE}/upAbC123?up=1");
    assert_eq!(
        rig.register("phone", &phone.registration(&endpoint))
            .await
            .status(),
        204
    );

    rig.rig.stalwart.deliver(email("m1", INBOX, json!({})));
    assert_eq!(rig.arrived().await.status(), 204);
    rig.fcm.wait_for(1).await;

    let posted = rig.fcm.posted.lock().unwrap().remove(0);
    assert_eq!(posted.target, "/fcm/send/upAbC123?up=1");
    assert_eq!(posted.headers["content-encoding"], "aes128gcm");
    assert_eq!(posted.headers["ttl"], "86400");
    assert_eq!(posted.headers["urgency"], "high");
    assert!(!String::from_utf8_lossy(&posted.body).contains("Ada"));
    let shown = phone.decrypt(&posted.body);
    assert_eq!(shown["v"], 1);
    assert_eq!(shown["id"], "m1");
    assert_eq!(
        shown["from"],
        json!({"name": "Ada Lovelace", "email": "ada@outside.test"})
    );
    assert_eq!(shown["subject"], "Notes on the engine");
    assert_eq!(shown["preview"].as_str().unwrap().chars().count(), 200);
}

#[tokio::test]
async fn mail_that_arrived_on_an_alias_is_pushed_like_any_other() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();
    rig.register("phone", &phone.registration(&format!("{BASE}/upAbC123")))
        .await;

    let mut shop = email("shop", INBOX, json!({}));
    shop["to"] = json!([{"name": null, "email": "shop.k2mq7x4p@rig.test"}]);
    shop["header:Delivered-To:asText:all"] = json!(["shop.k2mq7x4p@rig.test"]);
    rig.rig.stalwart.deliver(shop);
    rig.arrived().await;
    rig.fcm.wait_for(1).await;

    let posted = rig.fcm.posted.lock().unwrap().remove(0);
    assert_eq!(phone.decrypt(&posted.body)["id"], "shop");
}

#[tokio::test]
async fn mail_already_read_or_filed_outside_the_inbox_is_not_pushed() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();
    rig.register("phone", &phone.registration(&format!("{BASE}/upAbC123")))
        .await;

    rig.rig
        .stalwart
        .deliver(email("read", INBOX, json!({"$seen": true})));
    rig.rig.stalwart.deliver(email("filed", ARCHIVE, json!({})));
    rig.rig.stalwart.deliver(email("new", INBOX, json!({})));
    rig.arrived().await;
    rig.fcm.wait_for(1).await;
    tokio::time::sleep(Duration::from_millis(200)).await;

    let posted = rig.fcm.posted.lock().unwrap();
    let ids: Vec<Value> = posted
        .iter()
        .map(|posted| phone.decrypt(&posted.body)["id"].clone())
        .collect();
    assert_eq!(ids, [json!("new")]);
}

#[tokio::test]
async fn mail_received_long_ago_is_not_pushed_next_to_fresh_mail() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();
    rig.register("phone", &phone.registration(&format!("{BASE}/upAbC123")))
        .await;

    rig.rig
        .stalwart
        .deliver(email_received("restored", INBOX, json!({}), 3 * 3600));
    rig.rig.stalwart.deliver(email("fresh", INBOX, json!({})));
    rig.arrived().await;
    rig.fcm.wait_for(1).await;
    tokio::time::sleep(Duration::from_millis(200)).await;

    let posted = rig.fcm.posted.lock().unwrap();
    let ids: Vec<Value> = posted
        .iter()
        .map(|posted| phone.decrypt(&posted.body)["id"].clone())
        .collect();
    assert_eq!(ids, [json!("fresh")]);
}

#[tokio::test]
async fn an_endpoint_outside_fcm_is_refused() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();

    for endpoint in [
        "https://ntfy.sh/upAbC123".to_owned(),
        "https://fcm.googleapis.com.evil.test/fcm/send/abc".to_owned(),
        "https://fcm.googleapis.com@evil.test/fcm/send/abc".to_owned(),
        "https://evil.test/fcm/send/abc".to_owned(),
        "https://user@fcm.googleapis.com/fcm/send/abc".to_owned(),
        "https://fcm.googleapis.com:8443/fcm/send/abc".to_owned(),
        "http://fcm.googleapis.com/fcm/send/abc".to_owned(),
        "https://fcm.googleapis.com/jmap".to_owned(),
        "https://fcm.googleapis.com/fcm/send/ab c".to_owned(),
        format!("{BASE}/{}", "a".repeat(600)),
    ] {
        let response = rig.register("phone", &phone.registration(&endpoint)).await;
        assert_eq!(response.status(), 422, "{endpoint}");
    }
    assert!(rig.pushes().is_none());
}

#[tokio::test]
async fn the_wp_path_is_an_fcm_endpoint_too() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;

    let response = rig
        .register(
            "phone",
            &Phone::new().registration("https://fcm.googleapis.com/wp/abc"),
        )
        .await;

    assert_eq!(response.status(), 204);
}

#[tokio::test]
async fn each_push_carries_a_vapid_signature_that_verifies_under_the_published_key() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    rig.register(
        "phone",
        &Phone::new().registration(&format!("{BASE}/upAbC123")),
    )
    .await;
    let published: Value = reqwest::get(format!("{}/corvid/push/vapid", rig.rig.url))
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let public = published["publicKey"].as_str().unwrap();

    rig.rig.stalwart.deliver(email("m1", INBOX, json!({})));
    rig.arrived().await;
    rig.fcm.wait_for(1).await;

    let posted = rig.fcm.posted.lock().unwrap().remove(0);
    let header = posted.headers["authorization"].to_str().unwrap();
    let (token, key) = header
        .strip_prefix("vapid t=")
        .unwrap()
        .split_once(", k=")
        .unwrap();
    assert_eq!(key, public);
    let (signed, signature) = token.rsplit_once('.').unwrap();
    VerifyingKey::from_sec1_bytes(&URL_SAFE_NO_PAD.decode(public).unwrap())
        .unwrap()
        .verify(
            signed.as_bytes(),
            &Signature::from_slice(&URL_SAFE_NO_PAD.decode(signature).unwrap()).unwrap(),
        )
        .unwrap();
    let claims: Value = serde_json::from_slice(
        &URL_SAFE_NO_PAD
            .decode(signed.split_once('.').unwrap().1)
            .unwrap(),
    )
    .unwrap();
    assert_eq!(claims["aud"], "https://fcm.googleapis.com");
    assert_eq!(claims["sub"], format!("mailto:{CONTACT}"));
}

#[tokio::test]
async fn keys_that_are_not_a_p256_point_and_a_16_byte_secret_are_refused() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let mut body = Phone::new().registration(&format!("{BASE}/upAbC123"));
    body["auth"] = json!(URL_SAFE_NO_PAD.encode([1; 8]));

    assert_eq!(rig.register("phone", &body).await.status(), 400);
}

#[tokio::test]
async fn only_an_approved_device_can_take_pushes() {
    let rig = push_rig().await;

    let response = rig
        .register(
            "phone",
            &Phone::new().registration(&format!("{BASE}/upAbC123")),
        )
        .await;

    assert_eq!(response.status(), 404);
}

#[tokio::test]
async fn revoking_a_device_ends_its_pushes() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    rig.register(
        "phone",
        &Phone::new().registration(&format!("{BASE}/upAbC123")),
    )
    .await;
    assert!(rig.pushes().is_some());

    assert_eq!(rig.rig.revoke("phone").await.status(), 204);
    rig.rig.stalwart.deliver(email("m1", INBOX, json!({})));
    rig.arrived().await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    assert!(rig.pushes().is_none());
    assert_eq!(rig.fcm.count(), 0);
}

#[tokio::test]
async fn approving_a_name_again_drops_the_old_devices_endpoint() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    rig.register(
        "phone",
        &Phone::new().registration(&format!("{BASE}/upAbC123")),
    )
    .await;

    rig.rig.approved("phone").await;

    assert!(rig.pushes().is_none());
}

#[tokio::test]
async fn an_endpoint_fcm_no_longer_knows_is_dropped() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    rig.register(
        "phone",
        &Phone::new().registration(&format!("{BASE}/upAbC123")),
    )
    .await;
    rig.fcm.status.store(410, Ordering::SeqCst);

    rig.rig.stalwart.deliver(email("m1", INBOX, json!({})));
    rig.arrived().await;
    rig.fcm.wait_for(1).await;
    tokio::time::sleep(Duration::from_millis(200)).await;

    assert!(rig.pushes().is_none());
}

#[tokio::test]
async fn unregistering_removes_the_endpoint_and_a_second_time_still_succeeds() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    rig.register(
        "phone",
        &Phone::new().registration(&format!("{BASE}/upAbC123")),
    )
    .await;

    assert_eq!(rig.unregister("phone").await.status(), 204);
    assert_eq!(rig.unregister("phone").await.status(), 204);
    assert!(rig.pushes().is_none());
}

#[tokio::test]
async fn an_unsigned_mail_webhook_is_refused() {
    let rig = push_rig().await;

    let response = reqwest::Client::new()
        .post(format!("{}/webhook/mail", rig.rig.url))
        .body("{\"events\": []}")
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn a_host_without_push_config_answers_push_off() {
    let rig = super::rig().await;
    let response = reqwest::Client::new()
        .put(format!("{}/corvid/devices/phone/push", rig.url))
        .json(&Phone::new().registration(&format!("{BASE}/upAbC123")))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 503);
    assert_eq!(
        response.json::<Value>().await.unwrap(),
        json!({"error": "push_off"})
    );
}

#[tokio::test]
async fn a_stream_registration_answers_with_a_token_and_the_sentinel_keeps_only_its_hash() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;

    let token = rig.register_stream("phone", &Phone::new()).await;

    assert_eq!(URL_SAFE_NO_PAD.decode(&token).unwrap().len(), 32);
    let stored = rig.pushes().unwrap();
    assert_eq!(
        stored.target,
        devices::Target::Stream {
            token_hash: hex::encode(Sha256::digest(token.as_bytes()))
        }
    );
}

#[tokio::test]
async fn the_stream_refuses_a_missing_wrong_or_fcm_registered_devices_token() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    rig.rig.approved("tablet").await;
    rig.register_stream("phone", &Phone::new()).await;
    rig.register(
        "tablet",
        &Phone::new().registration(&format!("{BASE}/upAbC123")),
    )
    .await;
    let url = format!("{}/corvid/push/stream", rig.rig.url);

    let none = reqwest::get(&url).await.unwrap();
    let wrong = reqwest::Client::new()
        .get(&url)
        .bearer_auth("not-a-token")
        .send()
        .await
        .unwrap();
    let fcm_device = reqwest::Client::new()
        .get(&url)
        .bearer_auth(hex::encode(Sha256::digest(b"")))
        .send()
        .await
        .unwrap();

    assert_eq!(none.status(), 401);
    assert_eq!(wrong.status(), 401);
    assert_eq!(fcm_device.status(), 401);
}

#[tokio::test]
async fn new_mail_reaches_an_open_stream_as_the_same_encrypted_preview() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();
    let token = rig.register_stream("phone", &phone).await;
    let mut stream = Stream::open(&rig, &token, None).await;

    rig.rig.stalwart.deliver(email("m1", INBOX, json!({})));
    rig.arrived().await;

    let (_, body) = stream.next().await.unwrap();
    let shown = phone.decrypt(&body);
    assert_eq!(shown["id"], "m1");
    assert_eq!(shown["subject"], "Notes on the engine");
    assert_eq!(rig.fcm.count(), 0);
}

#[tokio::test]
async fn a_reconnect_replays_what_it_missed_after_the_last_event_id() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();
    let token = rig.register_stream("phone", &phone).await;
    let mut first = Stream::open(&rig, &token, None).await;
    rig.rig.stalwart.deliver(email("m1", INBOX, json!({})));
    rig.arrived().await;
    let (seen, _) = first.next().await.unwrap();
    drop(first);

    rig.rig.stalwart.deliver(email("m2", INBOX, json!({})));
    rig.arrived().await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    let mut second = Stream::open(&rig, &token, Some(&seen)).await;

    let (_, body) = second.next().await.unwrap();
    assert_eq!(phone.decrypt(&body)["id"], "m2");
}

#[tokio::test]
async fn revoking_a_device_ends_its_open_stream_and_its_token_is_refused() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let token = rig.register_stream("phone", &Phone::new()).await;
    let mut stream = Stream::open(&rig, &token, None).await;

    assert_eq!(rig.rig.revoke("phone").await.status(), 204);

    assert!(stream.next().await.is_none());
    let again = reqwest::Client::new()
        .get(format!("{}/corvid/push/stream", rig.rig.url))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(again.status(), 401);
}

#[tokio::test]
async fn approving_a_name_again_ends_the_old_devices_stream() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let token = rig.register_stream("phone", &Phone::new()).await;
    let mut stream = Stream::open(&rig, &token, None).await;

    rig.rig.approved("phone").await;

    assert!(stream.next().await.is_none());
}

#[tokio::test]
async fn registering_the_same_stream_again_changes_nothing_and_keeps_the_stream_open() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let phone = Phone::new();
    let token = rig.register_stream("phone", &phone).await;
    let mut stream = Stream::open(&rig, &token, None).await;

    let again = rig
        .register("phone", &phone.registration("corvid-stream"))
        .await;
    rig.rig.stalwart.deliver(email("m1", INBOX, json!({})));
    rig.arrived().await;

    assert_eq!(again.status(), 204);
    let (_, body) = stream.next().await.unwrap();
    assert_eq!(phone.decrypt(&body)["id"], "m1");
}

#[tokio::test]
async fn registering_other_keys_replaces_the_token_and_ends_the_old_stream() {
    let rig = push_rig().await;
    rig.rig.approved("phone").await;
    let old = rig.register_stream("phone", &Phone::new()).await;
    let mut stream = Stream::open(&rig, &old, None).await;

    let new = rig.register_stream("phone", &Phone::other()).await;

    assert!(stream.next().await.is_none());
    assert_ne!(old, new);
}
