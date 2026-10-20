//! Passkey sign-in: the WebAuthn verifier, the OIDC issuer Stalwart's directory trusts, and the
//! routes that enroll passkeys and hand out tokens.

mod codes;
mod cose;
pub mod legacy;
mod limits;
mod manage;
mod routes;
#[cfg(feature = "soft-authenticator")]
pub mod soft;
mod state;
mod token;
mod webauthn;

use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use axum::Json;
use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::http::{HeaderMap, StatusCode, header};
use axum::routing::any;

use crate::dates;
use crate::http::{AppState, Off, off_json};
use crate::key_file;
use crate::persisted::Persisted;
use crate::state::NoticeKind;
use limits::{Challenges, Failures};
use state::save;

pub use state::{Auth, Code, Credential, Family, Purpose, open};
pub use token::{Audience, Claims, Invalid, Issuer, MasterRefusal};
pub use webauthn::{AssertionResponse, Refusal, Registration, RegistrationResponse, RelyingParty};

/// The issuer for `url`, signing with the key at `key_path`, which is made on first use.
pub fn issuer(key_path: &Path, url: String) -> io::Result<Issuer> {
    key_file::open(key_path).map(|key| Issuer::new(key, url))
}

const REQUEST_BODY_LIMIT: usize = 32 * 1024;

pub struct Config {
    pub secret: Vec<u8>,
    pub state_path: PathBuf,
    pub auth: Auth,
    pub issuer: Arc<Issuer>,
    pub rp_id: String,
    pub origins: Vec<String>,
    pub daily_user: String,
    pub master_user: String,
}

struct AuthState {
    secret: Vec<u8>,
    auth: Persisted<Auth>,
    issuer: Arc<Issuer>,
    relying_party: RelyingParty,
    rp_id: String,
    daily_user: String,
    master_user: String,
    challenges: Challenges,
    failures: Failures,
    app: Arc<AppState>,
    update_dir: PathBuf,
}

type Shared = Arc<AuthState>;

impl AuthState {
    fn username(&self, audience: Audience) -> &str {
        match audience {
            Audience::Daily => &self.daily_user,
            Audience::Master => &self.master_user,
        }
    }
}

/// A token that proves nothing is 401; a genuine one from someone other than the admin is 403.
pub(crate) fn master_status(refusal: MasterRefusal) -> StatusCode {
    match refusal {
        MasterRefusal::Invalid(Invalid(reason)) => {
            tracing::info!(reason, "an admin token was refused");
            StatusCode::UNAUTHORIZED
        }
        MasterRefusal::NotMaster => StatusCode::FORBIDDEN,
    }
}

/// Accepts an admin token for `admin` in `X-Corvid-Master`, which is spent by this one request.
pub(crate) fn require_master(
    issuer: &Issuer,
    headers: &HeaderMap,
    admin: &str,
) -> Result<(), StatusCode> {
    let token = headers
        .get("X-Corvid-Master")
        .and_then(|value| value.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;
    issuer
        .verify_master(token, admin, dates::now_secs())
        .map_err(master_status)
}

/// The claims of the mail token in `Authorization: Bearer`, or a bare 401.
pub(crate) fn daily_claims(issuer: &Issuer, headers: &HeaderMap) -> Result<Claims, StatusCode> {
    let token = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .ok_or(StatusCode::UNAUTHORIZED)?;
    issuer
        .verify(token, Audience::Daily, dates::now_secs())
        .map_err(|_| StatusCode::UNAUTHORIZED)
}

/// Runs a state change off the async threads, since it writes the state file.
async fn commit<T: Send + 'static>(
    state: &Shared,
    change: impl FnOnce(&Auth) -> (Option<Auth>, T) + Send + 'static,
) -> Result<T, StatusCode> {
    Persisted::commit_blocking(state.clone(), |state| &state.auth, "auth", change).await
}

async fn notify(state: &Shared, title: &'static str, detail: String) {
    let app = state.app.clone();
    let recorded = tokio::task::spawn_blocking(move || {
        app.commit(|seen| {
            let mut next = seen.clone();
            next.push_notice(
                NoticeKind::SignIn,
                dates::rfc3339_now(),
                title.to_owned(),
                detail,
            );
            Some(next)
        })
    })
    .await
    .expect("the blocking task panicked");
    if let Err(error) = recorded {
        tracing::warn!(%error, "could not record a notice");
    }
}

pub(crate) fn routes(config: Config, app: Arc<AppState>, update_dir: PathBuf) -> Router {
    let state = Arc::new(AuthState {
        secret: config.secret,
        auth: Persisted::new(config.state_path, config.auth, save),
        relying_party: RelyingParty::new(&config.rp_id, config.origins),
        issuer: config.issuer,
        rp_id: config.rp_id,
        daily_user: config.daily_user,
        master_user: config.master_user,
        challenges: Challenges::default(),
        failures: Failures::default(),
        app,
        update_dir,
    });
    Router::new()
        .merge(routes::routes())
        .merge(manage::routes())
        .layer(DefaultBodyLimit::max(REQUEST_BODY_LIMIT))
        .with_state(state)
}

/// The auth routes on a host whose config has no `sentinel.auth`.
pub(crate) fn off() -> Router {
    async fn off() -> (StatusCode, Json<Off>) {
        off_json("auth_off")
    }
    Router::new()
        .route("/corvid/auth/{*path}", any(off))
        .route("/internal/auth/{*path}", any(off))
        .route("/internal/issuer/{*path}", any(off))
}
