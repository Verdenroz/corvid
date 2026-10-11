//! Passkey sign-in: the WebAuthn verifier, the OIDC issuer Stalwart's directory trusts, and the
//! routes that enroll passkeys and hand out tokens.

mod codes;
mod cose;
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
use axum::http::StatusCode;
use axum::routing::any;
use serde::{Deserialize, Serialize};

use crate::dates;
use crate::http::AppState;
use crate::key_file;
use crate::persisted::Persisted;
use crate::state::NoticeKind;
use limits::{Challenges, Failures};
use state::save;

pub use state::{Auth, Code, Credential, Family, Role, open};
pub use token::{Audience, Claims, Invalid, Issuer, MasterRefusal};
pub use webauthn::{
    AssertionResponse, Policy, Refusal, Registration, RegistrationResponse, RelyingParty,
};

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
}

type Shared = Arc<AuthState>;

#[derive(Deserialize)]
struct Which {
    role: Role,
}

impl AuthState {
    fn username(&self, role: Role) -> &str {
        match role {
            Role::Daily => &self.daily_user,
            Role::Master => &self.master_user,
        }
    }
}

/// Runs a state change off the async threads, since it writes the state file.
async fn commit<T: Send + 'static>(
    state: &Shared,
    change: impl FnOnce(&Auth) -> (Option<Auth>, T) + Send + 'static,
) -> Result<T, StatusCode> {
    let state = state.clone();
    tokio::task::spawn_blocking(move || state.auth.commit(change))
        .await
        .expect("the blocking task panicked")
        .map_err(|error| unrecorded(&error))
}

fn unrecorded(error: &io::Error) -> StatusCode {
    tracing::warn!(%error, "cannot save the auth state");
    StatusCode::INTERNAL_SERVER_ERROR
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

pub(crate) fn routes(config: Config, app: Arc<AppState>) -> Router {
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
    });
    Router::new()
        .merge(routes::routes())
        .merge(manage::routes())
        .layer(DefaultBodyLimit::max(REQUEST_BODY_LIMIT))
        .with_state(state)
}

#[derive(Serialize)]
struct Off {
    error: &'static str,
}

/// The auth routes on a host whose config has no `sentinel.auth`.
pub(crate) fn off() -> Router {
    async fn off() -> (StatusCode, Json<Off>) {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(Off { error: "auth_off" }),
        )
    }
    Router::new()
        .route("/corvid/auth/{*path}", any(off))
        .route("/internal/auth/{*path}", any(off))
        .route("/internal/issuer/{*path}", any(off))
}
