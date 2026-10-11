//! Master-approved device enrollment: a device's storage key is created only by a request that
//! the admin user approves with a fresh Pocket-ID token, and is read back by the node id the
//! approval recorded.

mod keys;
mod master;
mod push;
mod state;

use std::io;
use std::path::PathBuf;
use std::sync::Arc;

use axum::Json;
use axum::Router;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{delete, get, post, put};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;

use crate::dates;
use crate::persisted::Persisted;
use keys::Keys;
use master::{Master, Refusal};
pub use push::Config as PushConfig;
pub use state::{Approved, Devices, Push, Request, Stage, open};
use state::{LIFETIME_SECONDS, MAX_OPEN, save};

const REQUEST_BODY_LIMIT: usize = 256;
const PUSH_BODY_LIMIT: usize = 2048;
const WEBHOOK_BODY_LIMIT: usize = 1 << 20;
const NAME_LENGTH: std::ops::RangeInclusive<usize> = 1..=32;

pub struct Config {
    pub secret: Vec<u8>,
    pub state_path: PathBuf,
    pub devices: Devices,
    pub issuer: String,
    pub idp_url: String,
    pub admin_user: String,
    pub keys_address: String,
    pub mail_address: String,
    pub stalwart_url: String,
    pub stalwart_token: String,
    /// Absent, devices take no pushes and the mail WebHook is acknowledged and ignored.
    pub push: Option<PushConfig>,
}

struct DeviceState {
    secret: Vec<u8>,
    devices: Persisted<Devices>,
    master: Master,
    keys: Keys,
    push: Option<push::Pusher>,
}

type Shared = Arc<DeviceState>;

/// Runs a state change off the async threads, since it writes the state file.
async fn commit<T: Send + 'static>(
    state: Shared,
    change: impl FnOnce(&Devices) -> (Option<Devices>, T) + Send + 'static,
) -> Result<T, StatusCode> {
    tokio::task::spawn_blocking(move || state.devices.commit(change))
        .await
        .expect("the blocking task panicked")
        .map_err(|error| unrecorded(&error))
}

pub fn routes(config: Config) -> Router {
    let state = Arc::new(DeviceState {
        secret: config.secret,
        devices: Persisted::new(config.state_path, config.devices, save),
        master: Master::new(config.issuer, config.idp_url, config.admin_user),
        keys: Keys::new(
            config.stalwart_url,
            config.stalwart_token,
            config.keys_address,
            config.mail_address,
        ),
        push: config.push.map(push::Pusher::new),
    });
    tokio::spawn(push::watch(state.clone()));
    let pushes = Router::new()
        .route(
            "/corvid/devices/{name}/push",
            put(push::register).delete(push::unregister),
        )
        .layer(DefaultBodyLimit::max(PUSH_BODY_LIMIT))
        .with_state(state.clone());
    let vapid = Router::new()
        .route("/corvid/push/vapid", get(push::vapid_key))
        .with_state(state.clone());
    let webhook = Router::new()
        .route("/webhook/mail", post(push::arrived))
        .layer(DefaultBodyLimit::max(WEBHOOK_BODY_LIMIT))
        .with_state(state.clone());
    Router::new()
        .route("/corvid/devices/requests", post(create).get(list))
        .route("/corvid/devices/requests/{id}", get(status))
        .route("/corvid/devices/requests/{id}/approve", post(approve))
        .route("/corvid/devices/{name}", get(read))
        .route("/internal/devices/{name}", delete(revoke))
        .layer(DefaultBodyLimit::max(REQUEST_BODY_LIMIT))
        .with_state(state)
        .merge(pushes)
        .merge(vapid)
        .merge(webhook)
}

#[derive(Serialize)]
struct Off {
    error: &'static str,
}

/// The device routes on a host whose config has no `sentinel.devices`. A stable 503 lets a client
/// tell "switched off" from a request that never reached the sentinel, which Stalwart answers 404.
pub fn off() -> Router {
    async fn off() -> (StatusCode, Json<Off>) {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(Off {
                error: "devices_off",
            }),
        )
    }
    Router::new()
        .route("/corvid/devices/requests", post(off).get(off))
        .route("/corvid/devices/requests/{id}", get(off))
        .route("/corvid/devices/requests/{id}/approve", post(off))
        .route("/corvid/devices/{name}", get(off))
        .route("/corvid/devices/{name}/push", put(off).delete(off))
        .route("/internal/devices/{name}", delete(off))
        .route("/corvid/push/vapid", get(off))
        .route("/webhook/mail", post(|| async { StatusCode::NO_CONTENT }))
}

/// `requests` is refused because it is the path segment the request routes live under.
fn valid_name(name: &str) -> bool {
    name != "requests"
        && NAME_LENGTH.contains(&name.len())
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0; N];
    SystemRandom::new()
        .fill(&mut bytes)
        .expect("the system has no randomness");
    bytes
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct NewRequest {
    name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Created {
    id: String,
    code: String,
    expires_at: String,
}

async fn create(
    State(state): State<Shared>,
    Json(new): Json<NewRequest>,
) -> Result<(StatusCode, Json<Created>), StatusCode> {
    if !valid_name(&new.name) {
        return Err(StatusCode::BAD_REQUEST);
    }
    let created = commit(state, |devices| {
        let now = dates::now_secs();
        let mut next = devices.without_expired_requests(now);
        if next.open_requests(now) >= MAX_OPEN {
            return (None, None);
        }
        let id = hex::encode(random_bytes::<16>());
        let code = format!("{:06}", u32::from_be_bytes(random_bytes()) % 1_000_000);
        let expires_at = now + LIFETIME_SECONDS;
        next.requests.insert(
            id.clone(),
            Request {
                name: new.name,
                code: code.clone(),
                expires_at,
                attempts: 0,
                stage: Stage::Open,
            },
        );
        let expires_at = dates::rfc3339(expires_at);
        (
            Some(next),
            Some(Created {
                id,
                code,
                expires_at,
            }),
        )
    })
    .await?;
    created
        .map(|created| (StatusCode::CREATED, Json(created)))
        .ok_or(StatusCode::TOO_MANY_REQUESTS)
}

fn unrecorded(error: &io::Error) -> StatusCode {
    tracing::warn!(%error, "cannot save the device state");
    StatusCode::INTERNAL_SERVER_ERROR
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Pending {
    id: String,
    name: String,
    expires_at: String,
}

/// The requests an approver can still act on, newest first. The code stays with the device.
async fn list(State(state): State<Shared>) -> Json<Vec<Pending>> {
    let now = dates::now_secs();
    let mut live: Vec<(i64, Pending)> = state.devices.read(|devices| {
        devices
            .requests
            .iter()
            .filter(|(_, request)| request.stage == Stage::Open && request.is_live(now))
            .map(|(id, request)| {
                let pending = Pending {
                    id: id.clone(),
                    name: request.name.clone(),
                    expires_at: dates::rfc3339(request.expires_at),
                };
                (request.expires_at, pending)
            })
            .collect()
    });
    live.sort_by_key(|(expires_at, _)| std::cmp::Reverse(*expires_at));
    Json(live.into_iter().map(|(_, pending)| pending).collect())
}

#[derive(Serialize)]
struct Status {
    status: &'static str,
}

/// An id that is unknown, spent or past its lifetime all read as `expired`.
async fn status(State(state): State<Shared>, Path(id): Path<String>) -> Json<Status> {
    let now = dates::now_secs();
    let status = state
        .devices
        .read(|devices| match devices.requests.get(&id) {
            Some(request) if request.stage == Stage::Approved => "approved",
            Some(request) if request.is_live(now) => "pending",
            _ => "expired",
        });
    Json(Status { status })
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Approval {
    code: String,
}

enum Claim {
    Gone,
    Wrong,
    Claimed(Request),
}

async fn approve(
    State(state): State<Shared>,
    Path(id): Path<String>,
    headers: HeaderMap,
    Json(approval): Json<Approval>,
) -> Result<Json<Approved>, StatusCode> {
    let token = headers
        .get("X-Corvid-Master")
        .and_then(|value| value.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;
    let live = state.devices.read(|devices| {
        devices.requests.get(&id).is_some_and(|request| {
            request.stage == Stage::Open && request.is_live(dates::now_secs())
        })
    });
    if !live {
        return Err(StatusCode::GONE);
    }
    state
        .master
        .verify(token, dates::now_secs())
        .await
        .map_err(|refusal| match refusal {
            Refusal::Invalid(reason) => {
                tracing::info!(reason, "a master token was refused");
                StatusCode::UNAUTHORIZED
            }
            Refusal::NotMaster => StatusCode::FORBIDDEN,
            Refusal::Unreachable => StatusCode::BAD_GATEWAY,
        })?;

    let claim = claim(state.clone(), id.clone(), approval.code).await?;
    let request = match claim {
        Claim::Gone => return Err(StatusCode::GONE),
        Claim::Wrong => return Err(StatusCode::FORBIDDEN),
        Claim::Claimed(request) => request,
    };
    match enroll(&state, &request).await {
        Ok(approved) => {
            record(state, id, request.name, approved.clone()).await?;
            Ok(Json(approved))
        }
        Err(error) => {
            tracing::warn!(%error, "a claimed request could not be enrolled");
            forget(state, id).await?;
            Err(StatusCode::BAD_GATEWAY)
        }
    }
}

/// Spends the request on the first correct code, so the key is made once however many approvals
/// race. A wrong code counts against the request's attempts.
async fn claim(state: Shared, id: String, code: String) -> Result<Claim, StatusCode> {
    commit(state, move |devices| {
        let now = dates::now_secs();
        let Some(request) = devices.requests.get(&id) else {
            return (None, Claim::Gone);
        };
        if request.stage != Stage::Open || !request.is_live(now) {
            return (None, Claim::Gone);
        }
        let right = bool::from(request.code.as_bytes().ct_eq(code.as_bytes()));
        let mut next = devices.clone();
        let entry = next
            .requests
            .get_mut(&id)
            .expect("the request was just read");
        if right {
            entry.stage = Stage::Claimed;
            (Some(next), Claim::Claimed(request.clone()))
        } else {
            entry.attempts += 1;
            (Some(next), Claim::Wrong)
        }
    })
    .await
}

async fn enroll(state: &DeviceState, request: &Request) -> Result<Approved, keys::Error> {
    let old = state
        .devices
        .read(|devices| devices.approved.get(&request.name).cloned());
    if let Some(old) = old {
        state.keys.destroy(&old).await?;
    }
    state.keys.enroll(&request.name).await
}

async fn record(
    state: Shared,
    id: String,
    name: String,
    approved: Approved,
) -> Result<(), StatusCode> {
    commit(state, move |devices| {
        let mut next = devices.clone();
        if let Some(request) = next.requests.get_mut(&id) {
            request.stage = Stage::Approved;
        }
        next.pushes.remove(&name);
        next.approved.insert(name, approved);
        (Some(next), ())
    })
    .await
}

async fn forget(state: Shared, id: String) -> Result<(), StatusCode> {
    commit(state, move |devices| {
        let mut next = devices.clone();
        next.requests.remove(&id);
        (Some(next), ())
    })
    .await
}

async fn read(
    State(state): State<Shared>,
    Path(name): Path<String>,
) -> Result<Json<Approved>, StatusCode> {
    state
        .devices
        .read(|devices| devices.approved.get(&name).cloned())
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

/// How the host removes a device's key. Caddy does not route it, but every container in the pod
/// reaches the loopback port, so the caller signs `revoke <name>` with the webhook secret.
async fn revoke(
    State(state): State<Shared>,
    Path(name): Path<String>,
    headers: HeaderMap,
) -> Result<StatusCode, StatusCode> {
    let signature = headers
        .get("X-Signature")
        .and_then(|value| value.to_str().ok());
    if !crate::verify(
        &state.secret,
        format!("revoke {name}").as_bytes(),
        signature,
    ) {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let approved = state
        .devices
        .read(|devices| devices.approved.get(&name).cloned())
        .ok_or(StatusCode::NOT_FOUND)?;
    state.keys.destroy(&approved).await.map_err(|error| {
        tracing::warn!(%error, "a device key could not be destroyed");
        StatusCode::BAD_GATEWAY
    })?;
    commit(state, move |devices| {
        let mut next = devices.clone();
        next.approved.remove(&name);
        next.pushes.remove(&name);
        (Some(next), ())
    })
    .await?;
    Ok(StatusCode::NO_CONTENT)
}
