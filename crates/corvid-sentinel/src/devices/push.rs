//! New-mail previews pushed to approved devices. Stalwart's `message-ingest.ham` WebHook only
//! wakes the watcher; it carries no subject, so the watcher reads what changed in the mail account
//! as the admin API key and pushes each new Inbox message, encrypted to each device's own keys,
//! to the FCM endpoint the device registered or onto its own stream.

use std::collections::BTreeSet;
use std::io;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use axum::Json;
use axum::Router;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use futures_util::future::join_all;
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::Notify;

use super::keys::{self, Keys, checked};
use super::state::{Device, Push, PushKind, Target};
use super::stream;
use super::{Shared, commit, valid_name};
use crate::http::{Off, off_json};
use crate::vapid::Vapid;
use crate::webhook::Kind;
use crate::webpush::{self, Receiver, random_bytes};

pub const FCM_ORIGIN: &str = "https://fcm.googleapis.com";
const FCM_HOST: &str = "fcm.googleapis.com";
const FCM_PATHS: [&str; 2] = ["/fcm/send/", "/wp/"];
const MAX_ENDPOINT: usize = 512;
const PUSH_BODY_LIMIT: usize = 2048;
const WEBHOOK_BODY_LIMIT: usize = 1 << 20;
const PREVIEW_CHARS: usize = 200;
const MAX_CHANGES: u64 = 256;
const MAX_PER_WAKE: usize = 20;
const BASELINE_RETRY: Duration = Duration::from_secs(5);
const SEND_TIMEOUT: Duration = Duration::from_secs(10);
const SEEN: &str = "$seen";
const FRESH_SECONDS: i64 = 15 * 60;
const HAM: &str = "message-ingest.ham";

pub struct Config {
    vapid: Vapid,
    contact: String,
    fcm_origin: String,
}

impl Config {
    /// `contact` is the operator's address, sent to the push service in each VAPID claim. The
    /// signing key lives at `state` with its name replaced by `vapid.key`.
    pub fn open(state: &std::path::Path, contact: &str, fcm_origin: &str) -> io::Result<Self> {
        Ok(Self {
            vapid: Vapid::open(&state.with_file_name("vapid.key"))?,
            contact: contact.to_owned(),
            fcm_origin: fcm_origin.to_owned(),
        })
    }
}

/// The device state with what only a host that pushes needs. Its routes exist only then.
pub(super) struct PushState {
    device: Shared,
    config: Config,
    http: reqwest::Client,
    arrived: Notify,
}

pub(super) type Pushing = Arc<PushState>;

pub(super) fn routes(device: Shared, config: Config) -> (Router, Pushing) {
    let state = Arc::new(PushState {
        device: device.clone(),
        config,
        http: reqwest::Client::builder()
            .timeout(SEND_TIMEOUT)
            .build()
            .expect("a client with a timeout builds"),
        arrived: Notify::new(),
    });
    tokio::spawn(watch(state.clone()));
    let registration = Router::new()
        .route(
            "/corvid/devices/{name}/push",
            put(register).delete(unregister),
        )
        .route("/corvid/devices/{name}/push/kinds", put(set_kinds))
        .layer(DefaultBodyLimit::max(PUSH_BODY_LIMIT))
        .with_state(state.clone());
    let webhook = Router::new()
        .route("/webhook/mail", post(arrived))
        .layer(DefaultBodyLimit::max(WEBHOOK_BODY_LIMIT))
        .with_state(state.clone());
    let key = Router::new()
        .route("/corvid/push/vapid", get(vapid_key))
        .with_state(state.clone());
    let stream = Router::new()
        .route("/corvid/push/stream", get(stream::serve))
        .with_state(device);
    let router = registration.merge(webhook).merge(key).merge(stream);
    (router, state)
}

/// The push routes on a host whose config has devices but no `sentinel.push`.
pub(super) fn off() -> Router {
    async fn off() -> (StatusCode, Json<Off>) {
        off_json("push_off")
    }
    Router::new()
        .route("/corvid/devices/{name}/push", put(off).delete(off))
        .route("/corvid/devices/{name}/push/kinds", put(off))
        .route("/corvid/push/vapid", get(off))
        .route("/corvid/push/stream", get(off))
        .route("/webhook/mail", post(|| async { StatusCode::NO_CONTENT }))
}

/// The path and query of an endpoint FCM issued, or `None` for anything else, so the sentinel
/// never posts to a host or a path it was not given by Google.
fn fcm_target(endpoint: &str) -> Option<String> {
    if endpoint.len() > MAX_ENDPOINT || endpoint.contains(char::is_whitespace) {
        return None;
    }
    let url = reqwest::Url::parse(endpoint).ok()?;
    let issued = url.scheme() == "https"
        && url.host_str() == Some(FCM_HOST)
        && url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
        && FCM_PATHS
            .iter()
            .any(|prefix| url.path().starts_with(prefix));
    issued.then(|| endpoint[url.origin().ascii_serialization().len()..].to_owned())
}

fn refuse(status: StatusCode) -> (StatusCode, Json<Off>) {
    (status, Json(Off { error: "refused" }))
}

async fn vapid_key(State(state): State<Pushing>) -> Json<Value> {
    Json(json!({"publicKey": state.config.vapid.public_key()}))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Registration {
    endpoint: String,
    p256dh: String,
    auth: String,
}

fn receiver(p256dh: &str, auth: &str) -> Option<Receiver> {
    let decode = |text: &str| URL_SAFE_NO_PAD.decode(text.trim_end_matches('=')).ok();
    Receiver::new(&decode(p256dh)?, &decode(auth)?)
}

enum Registered {
    NotApproved,
    Unchanged,
    Changed,
}

/// Stores where a device takes its pushes. Only a device already approved may have one, and
/// only an endpoint FCM issued or the sentinel's own stream. A stream registration answers with
/// the token its connection reads with; the sentinel keeps only the token's hash. Registering
/// what the device already holds changes nothing, so an open stream stays open.
async fn register(
    State(state): State<Pushing>,
    Path(name): Path<String>,
    Json(registration): Json<Registration>,
) -> Result<Response, (StatusCode, Json<Off>)> {
    if !valid_name(&name) || receiver(&registration.p256dh, &registration.auth).is_none() {
        return Err(refuse(StatusCode::BAD_REQUEST));
    }
    let token = URL_SAFE_NO_PAD.encode(random_bytes::<32>());
    let own_stream = registration.endpoint == stream::ENDPOINT;
    let target = if own_stream {
        Target::Stream {
            token_hash: stream::hash(&token),
        }
    } else {
        let endpoint = fcm_target(&registration.endpoint)
            .ok_or_else(|| refuse(StatusCode::UNPROCESSABLE_ENTITY))?;
        Target::Fcm { endpoint }
    };
    let push = Push {
        p256dh: registration.p256dh,
        auth: registration.auth,
        target,
    };
    let ended = name.clone();
    let outcome = commit(state.device.clone(), move |devices| {
        let Some(device) = devices.approved.get(&name) else {
            return (None, Registered::NotApproved);
        };
        if device.push.as_ref().is_some_and(|held| held.same_as(&push)) {
            return (None, Registered::Unchanged);
        }
        let mut next = devices.clone();
        next.approved.get_mut(&name).expect("read above").push = Some(push);
        (Some(next), Registered::Changed)
    })
    .await
    .map_err(refuse)?;
    match outcome {
        Registered::NotApproved => Err(refuse(StatusCode::NOT_FOUND)),
        Registered::Unchanged => Ok(StatusCode::NO_CONTENT.into_response()),
        Registered::Changed => {
            state.device.streams.close(&ended);
            Ok(if own_stream {
                Json(json!({"token": token})).into_response()
            } else {
                StatusCode::NO_CONTENT.into_response()
            })
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Kinds {
    kinds: BTreeSet<PushKind>,
}

/// Replaces the kinds of push a device takes beyond new-mail previews. It never touches the
/// device's push registration.
pub(super) async fn set_kinds(
    State(state): State<Pushing>,
    Path(name): Path<String>,
    body: Bytes,
) -> Result<StatusCode, (StatusCode, Json<Off>)> {
    let Kinds { kinds } = serde_json::from_slice(&body)
        .ok()
        .filter(|_| valid_name(&name))
        .ok_or_else(|| refuse(StatusCode::BAD_REQUEST))?;
    let known = commit(state.device.clone(), move |devices| {
        let Some(device) = devices.approved.get(&name) else {
            return (None, false);
        };
        if device.kinds == kinds {
            return (None, true);
        }
        let mut next = devices.clone();
        next.approved.get_mut(&name).expect("read above").kinds = kinds;
        (Some(next), true)
    })
    .await
    .map_err(refuse)?;
    known
        .then_some(StatusCode::NO_CONTENT)
        .ok_or_else(|| refuse(StatusCode::NOT_FOUND))
}

async fn unregister(
    State(state): State<Pushing>,
    Path(name): Path<String>,
) -> Result<StatusCode, (StatusCode, Json<Off>)> {
    let ended = name.clone();
    let known = commit(state.device.clone(), move |devices| {
        let Some(device) = devices.approved.get(&name) else {
            return (None, false);
        };
        if device.push.is_none() {
            return (None, true);
        }
        let mut next = devices.clone();
        next.approved.get_mut(&name).expect("read above").push = None;
        (Some(next), true)
    })
    .await
    .map_err(refuse)?;
    if !known {
        return Err(refuse(StatusCode::NOT_FOUND));
    }
    state.device.streams.close(&ended);
    Ok(StatusCode::NO_CONTENT)
}

/// Stalwart's signed `message-ingest.ham` batch. It only wakes the watcher, so a batch arriving
/// while one is being read coalesces into one more read.
async fn arrived(State(state): State<Pushing>, headers: HeaderMap, body: Bytes) -> StatusCode {
    let signature = headers
        .get("X-Signature")
        .and_then(|value| value.to_str().ok());
    if !crate::verify(&state.device.secret, &body, signature) {
        return StatusCode::UNAUTHORIZED;
    }
    let delivered = crate::parse(&body).is_ok_and(|batch| {
        batch
            .events
            .iter()
            .any(|event| matches!(&event.kind, Kind::Other(kind) if kind == HAM))
    });
    if delivered {
        state.arrived.notify_one();
    }
    StatusCode::NO_CONTENT
}

/// Where the watcher has read up to in the mail account.
struct Mailbox {
    account: String,
    inbox: String,
    state: String,
}

/// Runs for the life of the process. Mail delivered before the first baseline is not pushed.
async fn watch(state: Pushing) {
    let keys = &state.device.keys;
    let mut mailbox = loop {
        match baseline(keys).await {
            Ok(mailbox) => break mailbox,
            Err(error) => {
                tracing::info!(%error, "push: the mail account is not readable yet");
                tokio::time::sleep(BASELINE_RETRY).await;
            }
        }
    };
    loop {
        state.arrived.notified().await;
        match arrivals(keys, &mailbox).await {
            Ok(Changes::Since(next, messages)) => {
                mailbox.state = next;
                deliver(&state, &messages).await;
            }
            Ok(Changes::Lost) => match baseline(keys).await {
                Ok(fresh) => mailbox = fresh,
                Err(error) => tracing::warn!(%error, "push: cannot take a new baseline"),
            },
            Err(error) => tracing::warn!(%error, "push: cannot read new mail"),
        }
    }
}

async fn baseline(keys: &Keys) -> Result<Mailbox, keys::Error> {
    let (account, inbox) = keys.inbox_id().await?;
    let answers = keys
        .batch(&[("Email/get", json!({"accountId": account, "ids": []}))])
        .await?;
    let state = checked("Email/get", &answers[0])?["state"]
        .as_str()
        .ok_or_else(|| keys::Error::new("Email/get returned no state"))?
        .to_owned();
    Ok(Mailbox {
        account,
        inbox,
        state,
    })
}

enum Changes {
    Since(String, Vec<Value>),
    /// The server can no longer say what changed since the state held.
    Lost,
}

/// The messages created since the held state that sit in the Inbox unread and arrived recently, so
/// an import or restore of old mail, or a catch-up after downtime, is not announced as new.
async fn arrivals(keys: &Keys, mailbox: &Mailbox) -> Result<Changes, keys::Error> {
    let mut since = mailbox.state.clone();
    let mut created: Vec<Value> = Vec::new();
    loop {
        let answers = keys
            .batch(&[(
                "Email/changes",
                json!({"accountId": mailbox.account, "sinceState": since, "maxChanges": MAX_CHANGES}),
            )])
            .await?;
        if answers[0][0] == "error" && answers[0][1]["type"] == "cannotCalculateChanges" {
            return Ok(Changes::Lost);
        }
        let changes = checked("Email/changes", &answers[0])?;
        created.extend(changes["created"].as_array().into_iter().flatten().cloned());
        since = changes["newState"]
            .as_str()
            .ok_or_else(|| keys::Error::new("Email/changes returned no newState"))?
            .to_owned();
        if changes["hasMoreChanges"] != true {
            break;
        }
    }
    if created.is_empty() {
        return Ok(Changes::Since(since, Vec::new()));
    }
    let answers = keys
        .batch(&[(
            "Email/get",
            json!({"accountId": mailbox.account, "ids": created,
                   "properties": ["id", "from", "subject", "preview", "receivedAt", "mailboxIds", "keywords"]}),
        )])
        .await?;
    let now = crate::dates::now_secs();
    let messages = checked("Email/get", &answers[0])?["list"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|email| {
            email["mailboxIds"][&mailbox.inbox] == true
                && email["keywords"][SEEN] != true
                && email["receivedAt"]
                    .as_str()
                    .and_then(crate::dates::seconds)
                    .is_some_and(|received| now - received <= FRESH_SECONDS)
        })
        .cloned()
        .collect();
    Ok(Changes::Since(since, messages))
}

/// What a device decrypts and shows; the device ignores fields it does not know.
fn preview(email: &Value) -> Vec<u8> {
    let from = &email["from"][0];
    let preview: String = email["preview"]
        .as_str()
        .unwrap_or_default()
        .chars()
        .take(PREVIEW_CHARS)
        .collect();
    json!({
        "v": 1,
        "id": email["id"],
        "from": if from.is_object() { json!({"name": from["name"], "email": from["email"]}) } else { Value::Null },
        "subject": email["subject"].as_str().unwrap_or_default(),
        "preview": preview,
    })
    .to_string()
    .into_bytes()
}

async fn deliver(state: &Pushing, messages: &[Value]) {
    if messages.len() > MAX_PER_WAKE {
        tracing::info!(
            skipped = messages.len() - MAX_PER_WAKE,
            "push: more new mail than one wake pushes"
        );
    }
    let plaintexts: Vec<Vec<u8>> = messages.iter().take(MAX_PER_WAKE).map(preview).collect();
    fan_out(state, |_| true, &plaintexts).await;
}

/// Sends `plaintexts` to each device with a push record that `wants` them.
pub(super) async fn fan_out(
    state: &Pushing,
    wants: impl Fn(&Device) -> bool,
    plaintexts: &[Vec<u8>],
) {
    let devices: Vec<(String, Push)> = state.device.devices.read(|devices| {
        devices
            .approved
            .iter()
            .filter(|(_, device)| wants(device))
            .filter_map(|(name, device)| Some((name.clone(), device.push.clone()?)))
            .collect()
    });
    join_all(
        devices
            .into_iter()
            .map(|(name, push)| push_to(state, name, push, plaintexts)),
    )
    .await;
}

/// One device's share of a wake, in order. FCM that stops answering ends the device's share, so a
/// hung endpoint costs one timeout, not one per message.
async fn push_to(state: &Pushing, name: String, push: Push, plaintexts: &[Vec<u8>]) {
    let Some(receiver) = receiver(&push.p256dh, &push.auth) else {
        return;
    };
    let Target::Fcm { endpoint } = &push.target else {
        for plaintext in plaintexts {
            let body = webpush::encrypt(&receiver, plaintext);
            state.device.streams.publish(&name, &body);
        }
        return;
    };
    let url = format!("{}{endpoint}", state.config.fcm_origin);
    for plaintext in plaintexts {
        match send(state, &url, &receiver, plaintext).await {
            Ok(status) if status == StatusCode::NOT_FOUND || status == StatusCode::GONE => {
                return forget(state, name, push).await;
            }
            Ok(status) if !status.is_success() => {
                tracing::warn!(device = %name, %status, "push: FCM refused a push");
            }
            Ok(_) => {}
            Err(error) => {
                tracing::warn!(device = %name, %error, "push: FCM is unreachable");
                return;
            }
        }
    }
}

async fn send(
    state: &Pushing,
    url: &str,
    receiver: &Receiver,
    plaintext: &[u8],
) -> Result<StatusCode, reqwest::Error> {
    let config = &state.config;
    let response = state
        .http
        .post(url)
        .header(
            "Authorization",
            config
                .vapid
                .authorization(FCM_ORIGIN, &config.contact, SystemTime::now()),
        )
        .header("Content-Encoding", "aes128gcm")
        .header("Content-Type", "application/octet-stream")
        .header("TTL", "86400")
        .header("Urgency", "high")
        .body(webpush::encrypt(receiver, plaintext))
        .send()
        .await?;
    Ok(StatusCode::from_u16(response.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY))
}

/// A registration FCM no longer knows is dropped, unless the device has registered another
/// since it was read.
async fn forget(state: &Pushing, name: String, held: Push) {
    let outcome = commit(state.device.clone(), move |devices| {
        match devices.approved.get(&name) {
            Some(device) if device.push.as_ref() == Some(&held) => {}
            _ => return (None, ()),
        }
        let mut next = devices.clone();
        next.approved.get_mut(&name).expect("read above").push = None;
        (Some(next), ())
    })
    .await;
    if outcome.is_err() {
        tracing::warn!("push: a gone endpoint could not be dropped");
    }
}
