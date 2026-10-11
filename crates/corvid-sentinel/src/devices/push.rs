//! New-mail previews pushed to approved devices. Stalwart's `message-ingest.ham` WebHook only
//! wakes the watcher; it carries no subject, so the watcher reads what changed in the mail account
//! as the admin API key and pushes each new Inbox message, encrypted to each device's own keys,
//! to the FCM endpoint the device registered.

use std::io;
use std::time::{Duration, SystemTime};

use axum::Json;
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::Notify;

use super::keys::{self, Keys, checked};
use super::state::{Devices, Push};
use super::stream::{self, Streams};
use super::{Off, Shared, commit, valid_name};
use crate::vapid::Vapid;
use crate::webpush::{self, Receiver, random_bytes};

const MAX_ENDPOINT: usize = 512;
const FCM_AUDIENCE: &str = "https://fcm.googleapis.com";
const FCM_HOST: &str = "fcm.googleapis.com";
const FCM_PATHS: [&str; 2] = ["/fcm/send/", "/wp/"];
const PREVIEW_CHARS: usize = 200;
const MAX_CHANGES: u64 = 256;
const MAX_PER_WAKE: usize = 20;
const BASELINE_RETRY: Duration = Duration::from_secs(5);
const SEND_TIMEOUT: Duration = Duration::from_secs(10);
const SEEN: &str = "$seen";
const FRESH_SECONDS: i64 = 15 * 60;

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

pub(super) struct Pusher {
    vapid: Vapid,
    contact: String,
    fcm_origin: String,
    http: reqwest::Client,
    arrived: Notify,
    pub(super) streams: Streams,
}

impl Pusher {
    pub(super) fn new(config: Config) -> Self {
        Self {
            vapid: config.vapid,
            contact: config.contact,
            fcm_origin: config.fcm_origin,
            http: reqwest::Client::builder()
                .timeout(SEND_TIMEOUT)
                .build()
                .expect("a client with a timeout builds"),
            arrived: Notify::new(),
            streams: Streams::default(),
        }
    }
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

fn push_off() -> (StatusCode, Json<Off>) {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        Json(Off { error: "push_off" }),
    )
}

pub(super) async fn vapid_key(
    State(state): State<Shared>,
) -> Result<Json<Value>, (StatusCode, Json<Off>)> {
    let pusher = state.push.as_ref().ok_or_else(push_off)?;
    Ok(Json(json!({"publicKey": pusher.vapid.public_key()})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Registration {
    endpoint: String,
    p256dh: String,
    auth: String,
}

fn receiver(p256dh: &str, auth: &str) -> Option<Receiver> {
    let decode = |text: &str| URL_SAFE_NO_PAD.decode(text.trim_end_matches('=')).ok();
    Receiver::new(&decode(p256dh)?, &decode(auth)?)
}

/// Stores where a device takes its pushes. Only a device already approved may have one, and
/// only an endpoint FCM issued or the sentinel's own stream. A stream registration answers with
/// the token its connection reads with; the sentinel keeps only the token's hash.
pub(super) async fn register(
    State(state): State<Shared>,
    Path(name): Path<String>,
    Json(registration): Json<Registration>,
) -> Result<Response, (StatusCode, Json<Off>)> {
    state.push.as_ref().ok_or_else(push_off)?;
    let refuse = |status: StatusCode| (status, Json(Off { error: "refused" }));
    if !valid_name(&name) || receiver(&registration.p256dh, &registration.auth).is_none() {
        return Err(refuse(StatusCode::BAD_REQUEST));
    }
    let own_stream = registration.endpoint == stream::ENDPOINT;
    if !own_stream && fcm_target(&registration.endpoint).is_none() {
        return Err(refuse(StatusCode::UNPROCESSABLE_ENTITY));
    }
    let token = URL_SAFE_NO_PAD.encode(random_bytes::<32>());
    let push = Push {
        endpoint: registration.endpoint,
        p256dh: registration.p256dh,
        auth: registration.auth,
        token_hash: own_stream.then(|| stream::hash(&token)),
    };
    let ended = name.clone();
    let stored = commit(state.clone(), move |devices| {
        if !devices.approved.contains_key(&name) {
            return (None, false);
        }
        let mut next = devices.clone();
        next.pushes.insert(name, push);
        (Some(next), true)
    })
    .await
    .map_err(refuse)?;
    if !stored {
        return Err(refuse(StatusCode::NOT_FOUND));
    }
    state.end_stream(&ended);
    Ok(if own_stream {
        Json(json!({"token": token})).into_response()
    } else {
        StatusCode::NO_CONTENT.into_response()
    })
}

pub(super) async fn unregister(
    State(state): State<Shared>,
    Path(name): Path<String>,
) -> Result<StatusCode, (StatusCode, Json<Off>)> {
    if state.push.is_none() {
        return Err(push_off());
    }
    let ended = name.clone();
    let known = commit(state.clone(), move |devices| {
        if !devices.approved.contains_key(&name) {
            return (None, false);
        }
        let mut next = devices.clone();
        let removed = next.pushes.remove(&name).is_some();
        (removed.then_some(next), true)
    })
    .await
    .map_err(|status| (status, Json(Off { error: "refused" })))?;
    if !known {
        return Err((StatusCode::NOT_FOUND, Json(Off { error: "refused" })));
    }
    state.end_stream(&ended);
    Ok(StatusCode::NO_CONTENT)
}

/// Stalwart's signed `message-ingest.ham` batch. It only wakes the watcher, so a batch arriving
/// while one is being read coalesces into one more read.
pub(super) async fn arrived(
    State(state): State<Shared>,
    headers: HeaderMap,
    body: Bytes,
) -> StatusCode {
    let signature = headers
        .get("X-Signature")
        .and_then(|value| value.to_str().ok());
    if !crate::verify(&state.secret, &body, signature) {
        return StatusCode::UNAUTHORIZED;
    }
    let Some(pusher) = state.push.as_ref() else {
        return StatusCode::NO_CONTENT;
    };
    let delivered = serde_json::from_slice::<Value>(&body).is_ok_and(|batch| {
        batch["events"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|event| event["type"] == "message-ingest.ham")
    });
    if delivered {
        pusher.arrived.notify_one();
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
pub(super) async fn watch(state: Shared) {
    let Some(pusher) = state.push.as_ref() else {
        return;
    };
    let mut mailbox = loop {
        match baseline(&state.keys).await {
            Ok(mailbox) => break mailbox,
            Err(error) => {
                tracing::info!(%error, "push: the mail account is not readable yet");
                tokio::time::sleep(BASELINE_RETRY).await;
            }
        }
    };
    loop {
        pusher.arrived.notified().await;
        match arrivals(&state.keys, &mailbox).await {
            Ok(Changes::Since(next, messages)) => {
                mailbox.state = next;
                deliver(&state, pusher, &messages).await;
            }
            Ok(Changes::Lost) => match baseline(&state.keys).await {
                Ok(fresh) => mailbox = fresh,
                Err(error) => tracing::warn!(%error, "push: cannot take a new baseline"),
            },
            Err(error) => tracing::warn!(%error, "push: cannot read new mail"),
        }
    }
}

async fn baseline(keys: &Keys) -> Result<Mailbox, keys::Error> {
    let account = keys.account(keys.mail_address()).await?;
    let answers = keys
        .batch(&[
            (
                "Mailbox/query",
                json!({"accountId": account, "filter": {"role": "inbox"}}),
            ),
            ("Email/get", json!({"accountId": account, "ids": []})),
        ])
        .await?;
    let inbox = checked("Mailbox/query", &answers[0])?["ids"][0]
        .as_str()
        .ok_or_else(|| keys::Error::new("the mail account has no Inbox"))?
        .to_owned();
    let state = checked("Email/get", &answers[1])?["state"]
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

async fn deliver(state: &Shared, pusher: &Pusher, messages: &[Value]) {
    if messages.len() > MAX_PER_WAKE {
        tracing::info!(
            skipped = messages.len() - MAX_PER_WAKE,
            "push: more new mail than one wake pushes"
        );
    }
    let devices: Vec<(String, Push)> = state.devices.read(|devices: &Devices| {
        devices
            .pushes
            .iter()
            .map(|(name, push)| (name.clone(), push.clone()))
            .collect()
    });
    for (name, push) in devices {
        let Some(receiver) = receiver(&push.p256dh, &push.auth) else {
            continue;
        };
        if push.endpoint == stream::ENDPOINT {
            for email in messages.iter().take(MAX_PER_WAKE) {
                pusher
                    .streams
                    .publish(&name, &webpush::encrypt(&receiver, &preview(email)));
            }
            continue;
        }
        let Some(target) = fcm_target(&push.endpoint) else {
            continue;
        };
        let url = format!("{}{target}", pusher.fcm_origin);
        for email in messages.iter().take(MAX_PER_WAKE) {
            match send(pusher, &url, &receiver, &preview(email)).await {
                Ok(status) if status == StatusCode::NOT_FOUND || status == StatusCode::GONE => {
                    forget(state.clone(), name.clone(), push.endpoint.clone()).await;
                    break;
                }
                Ok(status) if !status.is_success() => {
                    tracing::warn!(device = %name, %status, "push: FCM refused a push");
                }
                Ok(_) => {}
                Err(error) => {
                    tracing::warn!(device = %name, %error, "push: FCM is unreachable")
                }
            }
        }
    }
}

async fn send(
    pusher: &Pusher,
    url: &str,
    receiver: &Receiver,
    plaintext: &[u8],
) -> Result<StatusCode, reqwest::Error> {
    let response = pusher
        .http
        .post(url)
        .header(
            "Authorization",
            pusher
                .vapid
                .authorization(FCM_AUDIENCE, &pusher.contact, SystemTime::now()),
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

/// An endpoint FCM no longer knows is dropped, unless the device has registered another
/// since it was read.
async fn forget(state: Shared, name: String, endpoint: String) {
    let outcome = commit(state, move |devices| {
        if devices
            .pushes
            .get(&name)
            .is_none_or(|push| push.endpoint != endpoint)
        {
            return (None, ());
        }
        let mut next = devices.clone();
        next.pushes.remove(&name);
        (Some(next), ())
    })
    .await;
    if outcome.is_err() {
        tracing::warn!("push: a gone endpoint could not be dropped");
    }
}
