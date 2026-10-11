//! Enrollment codes and the passkey list, for a signed-in master and for the host.

use axum::Json;
use axum::Router;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{delete, get, post};
use serde::Serialize;

use super::state::{CODE_LIFETIME_SECONDS, Code, Role};
use super::{MasterRefusal, Shared, Which, codes, commit};
use crate::dates;

pub fn routes() -> Router<Shared> {
    Router::new()
        .route("/corvid/auth/passkeys", get(list))
        .route("/corvid/auth/passkeys/{id}", delete(remove))
        .route("/corvid/auth/enroll-codes", post(enroll))
        .route("/internal/auth/codes/{role}", post(internal_code))
        .route("/internal/auth/status", get(status))
        .route("/internal/auth/passkeys/{id}", delete(internal_remove))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Passkey {
    id: String,
    role: Role,
    backup_eligible: bool,
    created_at: String,
}

fn passkeys(state: &Shared) -> Vec<Passkey> {
    let mut listed: Vec<(i64, Passkey)> = state.auth.read(|auth| {
        auth.credentials
            .iter()
            .map(|(id, credential)| {
                let passkey = Passkey {
                    id: id.clone(),
                    role: auth.role_of(credential),
                    backup_eligible: credential.backup_eligible,
                    created_at: dates::rfc3339(credential.created_at),
                };
                (credential.created_at, passkey)
            })
            .collect()
    });
    listed.sort_by_key(|(created_at, _)| *created_at);
    listed.into_iter().map(|(_, passkey)| passkey).collect()
}

async fn list(State(state): State<Shared>) -> Json<Vec<Passkey>> {
    Json(passkeys(&state))
}

/// Accepts a master token in `X-Corvid-Master`, which is spent by this one request.
fn require_master(state: &Shared, headers: &HeaderMap) -> Result<(), StatusCode> {
    let token = headers
        .get("X-Corvid-Master")
        .and_then(|value| value.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;
    state
        .issuer
        .verify_master(token, &state.master_user, dates::now_secs())
        .map_err(|refusal| match refusal {
            MasterRefusal::Invalid(reason) => {
                tracing::info!(?reason, "a master token was refused");
                StatusCode::UNAUTHORIZED
            }
            MasterRefusal::NotMaster => StatusCode::FORBIDDEN,
        })
}

/// How the host proves itself: the webhook secret signs what it asks for.
fn require_host(state: &Shared, headers: &HeaderMap, message: &str) -> Result<(), StatusCode> {
    let signature = headers
        .get("X-Signature")
        .and_then(|value| value.to_str().ok());
    if crate::verify(&state.secret, message.as_bytes(), signature) {
        Ok(())
    } else {
        Err(StatusCode::UNAUTHORIZED)
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Minted {
    code: String,
    expires_at: String,
}

/// Replaces any earlier code for the role.
async fn mint(state: &Shared, role: Role) -> Result<Json<Minted>, StatusCode> {
    let now = dates::now_secs();
    let code = codes::generate();
    let stored = Code {
        hash: codes::hash(&code),
        expires_at: now + CODE_LIFETIME_SECONDS,
    };
    let expires_at = dates::rfc3339(stored.expires_at);
    commit(state, move |auth| {
        let mut next = auth.clone();
        next.codes.insert(role, stored);
        (Some(next), ())
    })
    .await?;
    Ok(Json(Minted { code, expires_at }))
}

async fn enroll(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(which): Json<Which>,
) -> Result<Json<Minted>, StatusCode> {
    require_master(&state, &headers)?;
    mint(&state, which.role).await
}

async fn internal_code(
    State(state): State<Shared>,
    headers: HeaderMap,
    Path(role): Path<Role>,
) -> Result<Json<Minted>, StatusCode> {
    require_host(&state, &headers, &format!("code {}", role.name()))?;
    mint(&state, role).await
}

enum Removal {
    Gone,
    Last,
    Done,
}

/// A user's last passkey stays, so a removal cannot lock a role out.
async fn forget(state: &Shared, id: String) -> Result<StatusCode, StatusCode> {
    let removal = commit(state, move |auth| {
        let Some(credential) = auth.credentials.get(&id) else {
            return (None, Removal::Gone);
        };
        let siblings = auth
            .credentials
            .values()
            .filter(|other| other.user == credential.user)
            .count();
        if siblings == 1 {
            return (None, Removal::Last);
        }
        let mut next = auth.clone();
        next.credentials.remove(&id);
        next.families.retain(|_, family| family.credential != id);
        (Some(next), Removal::Done)
    })
    .await?;
    match removal {
        Removal::Gone => Err(StatusCode::NOT_FOUND),
        Removal::Last => Err(StatusCode::CONFLICT),
        Removal::Done => Ok(StatusCode::NO_CONTENT),
    }
}

async fn remove(
    State(state): State<Shared>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, StatusCode> {
    require_master(&state, &headers)?;
    forget(&state, id).await
}

async fn internal_remove(
    State(state): State<Shared>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, StatusCode> {
    require_host(&state, &headers, &format!("remove-passkey {id}"))?;
    forget(&state, id).await
}

#[derive(Serialize)]
struct Status {
    daily: usize,
    master: usize,
    /// The roles holding an enrollment code that has not expired.
    codes: Vec<Role>,
    passkeys: Vec<Passkey>,
}

/// How many passkeys each role has, which is what `deploy.sh` waits on before it cuts over, and
/// which roles still have a code waiting to be used.
async fn status(
    State(state): State<Shared>,
    headers: HeaderMap,
) -> Result<Json<Status>, StatusCode> {
    require_host(&state, &headers, "status")?;
    let passkeys = passkeys(&state);
    let count = |role| {
        passkeys
            .iter()
            .filter(|passkey| passkey.role == role)
            .count()
    };
    let now = dates::now_secs();
    let codes = state
        .auth
        .read(|auth| auth.live_codes(now).map(|(role, _)| role).collect());
    Ok(Json(Status {
        daily: count(Role::Daily),
        master: count(Role::Master),
        codes,
        passkeys,
    }))
}
