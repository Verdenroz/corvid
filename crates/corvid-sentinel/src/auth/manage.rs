//! Setup codes and the passkey list, for a signed-in approver and for the host.

use axum::Json;
use axum::Router;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{delete, get, post};
use serde::{Deserialize, Serialize};

use super::state::{Auth, CODE_LIFETIME_SECONDS, Code};
use super::{Shared, codes, commit, require_master};
use crate::dates;

pub fn routes() -> Router<Shared> {
    Router::new()
        .route("/corvid/auth/passkeys", get(list))
        .route("/corvid/auth/passkeys/{id}", delete(remove))
        .route("/corvid/auth/enroll-codes", post(enroll))
        .route("/internal/auth/codes", post(internal_code))
        .route("/internal/auth/status", get(status))
        .route("/internal/auth/passkeys/{id}", delete(internal_remove))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Passkey {
    id: String,
    approves: bool,
    backup_eligible: bool,
    created_at: String,
}

fn passkeys(auth: &Auth) -> Vec<Passkey> {
    let mut listed: Vec<(i64, Passkey)> = auth
        .credentials
        .iter()
        .map(|(id, credential)| {
            let passkey = Passkey {
                id: id.clone(),
                approves: credential.approves,
                backup_eligible: credential.backup_eligible,
                created_at: dates::rfc3339(credential.created_at),
            };
            (credential.created_at, passkey)
        })
        .collect();
    listed.sort_by_key(|(created_at, _)| *created_at);
    listed.into_iter().map(|(_, passkey)| passkey).collect()
}

async fn list(State(state): State<Shared>) -> Json<Vec<Passkey>> {
    Json(state.auth.read(passkeys))
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
    approves: bool,
    expires_at: String,
}

#[derive(Deserialize)]
struct Minting {
    approves: bool,
}

async fn mint(state: &Shared, approves: bool) -> Result<Json<Minted>, StatusCode> {
    let now = dates::now_secs();
    let code = codes::generate();
    let hash = codes::hash(&code);
    let expires_at = now + CODE_LIFETIME_SECONDS;
    let approves = commit(state, move |auth| {
        let approves = auth.grants_approval(approves);
        let mut next = auth.clone();
        next.codes.retain(|code| code.is_live(now));
        next.codes.push(Code {
            hash,
            expires_at,
            approves,
        });
        (Some(next), approves)
    })
    .await?;
    Ok(Json(Minted {
        code,
        approves,
        expires_at: dates::rfc3339(expires_at),
    }))
}

async fn enroll(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(minting): Json<Minting>,
) -> Result<Json<Minted>, StatusCode> {
    require_master(&state.issuer, &headers, &state.master_user)?;
    mint(&state, minting.approves).await
}

async fn internal_code(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(minting): Json<Minting>,
) -> Result<Json<Minted>, StatusCode> {
    let message = if minting.approves {
        "code approver"
    } else {
        "code"
    };
    require_host(&state, &headers, message)?;
    mint(&state, minting.approves).await
}

enum Removal {
    Gone,
    Last,
    Done,
}

/// The last passkey and the last approver stay, so a removal cannot lock everyone out.
async fn forget(state: &Shared, id: String) -> Result<StatusCode, StatusCode> {
    let removal = commit(state, move |auth| {
        let Some(credential) = auth.credentials.get(&id) else {
            return (None, Removal::Gone);
        };
        if auth.credentials.len() == 1 || credential.approves && auth.approvers() == 1 {
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
    require_master(&state.issuer, &headers, &state.master_user)?;
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
#[serde(rename_all = "camelCase")]
struct WaitingCode {
    approves: bool,
    expires_at: String,
}

#[derive(Serialize)]
struct Status {
    approvers: usize,
    codes: Vec<WaitingCode>,
    passkeys: Vec<Passkey>,
}

/// The passkeys, how many approve, and the codes still waiting, which is what `deploy.sh` reads
/// before it cuts over.
async fn status(
    State(state): State<Shared>,
    headers: HeaderMap,
) -> Result<Json<Status>, StatusCode> {
    require_host(&state, &headers, "status")?;
    let now = dates::now_secs();
    Ok(Json(state.auth.read(|auth| {
        Status {
            approvers: auth.approvers(),
            codes: auth
                .live_codes(now)
                .map(|code| WaitingCode {
                    approves: code.approves,
                    expires_at: dates::rfc3339(code.expires_at),
                })
                .collect(),
            passkeys: passkeys(auth),
        }
    })))
}
