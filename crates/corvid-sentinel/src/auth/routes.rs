//! The ceremonies and token routes, plus the issuer's discovery documents for Stalwart.

use axum::Json;
use axum::Router;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::limits::Pending;
use super::state::{Credential, Family, Role};
use super::webauthn::{AssertionResponse, Refusal, RegistrationResponse};
use super::{Audience, Shared, codes, commit, notify};
use crate::dates;
use crate::webpush::random_bytes;

const CEREMONY_TIMEOUT_MS: u32 = 300_000;

pub fn routes() -> Router<Shared> {
    Router::new()
        .route("/corvid/auth/register/options", post(register_options))
        .route("/corvid/auth/register/finish", post(register_finish))
        .route("/corvid/auth/login/options", post(login_options))
        .route("/corvid/auth/login/finish", post(login_finish))
        .route("/corvid/auth/token", post(refresh))
        .route(
            "/corvid/auth/.well-known/openid-configuration",
            get(discovery),
        )
        .route("/corvid/auth/jwks", get(jwks))
        .route("/corvid/auth/userinfo", get(userinfo))
}

fn encode(bytes: impl AsRef<[u8]>) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

fn decode(text: &str) -> Result<Vec<u8>, StatusCode> {
    URL_SAFE_NO_PAD
        .decode(text)
        .map_err(|_| StatusCode::BAD_REQUEST)
}

/// The address Caddy appended last to `X-Forwarded-For`; what a client sent itself comes before.
fn client_address(headers: &HeaderMap) -> String {
    headers
        .get("X-Forwarded-For")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.rsplit(',').next())
        .map(|address| address.trim().to_owned())
        .unwrap_or_default()
}

/// The challenge a client signed, read from its client data so the ceremony can be found.
fn challenge_of(client_data_json: &[u8]) -> Result<Vec<u8>, StatusCode> {
    #[derive(Deserialize)]
    struct ClientData {
        challenge: String,
    }
    let data: ClientData =
        serde_json::from_slice(client_data_json).map_err(|_| StatusCode::BAD_REQUEST)?;
    decode(&data.challenge)
}

fn refused(refusal: &Refusal) -> StatusCode {
    tracing::info!(?refusal, "a passkey ceremony was refused");
    StatusCode::UNAUTHORIZED
}

#[derive(Deserialize)]
struct Redemption {
    code: String,
}

async fn register_options(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(redemption): Json<Redemption>,
) -> Result<Json<Value>, StatusCode> {
    let now = dates::now_secs();
    let address = client_address(&headers);
    if !state.failures.allows(&address, now) {
        return Err(StatusCode::TOO_MANY_REQUESTS);
    }
    let code = codes::hash(&redemption.code);
    let Some(role) = state.auth.read(|auth| auth.code_role(&code, now)) else {
        state.failures.record(&address, now);
        return Err(StatusCode::UNAUTHORIZED);
    };
    let (handle, enrolled) = state.auth.read(|auth| {
        let ids: Vec<_> = auth.credential_ids(role).into_iter().cloned().collect();
        (auth.handle_of(role).cloned(), ids)
    });
    let handle = handle.unwrap_or_else(|| hex::encode(random_bytes::<16>()));
    let challenge = state
        .challenges
        .issue(
            Pending::Register {
                role,
                code,
                handle: handle.clone(),
            },
            &address,
            now,
        )
        .ok_or(StatusCode::TOO_MANY_REQUESTS)?;
    let username = state.username(role);
    Ok(Json(json!({
        "challenge": encode(challenge),
        "rp": {"id": state.rp_id, "name": "Corvid"},
        "user": {"id": encode(&handle), "name": username, "displayName": username},
        "pubKeyCredParams": [{"type": "public-key", "alg": -7}],
        "authenticatorSelection": {"residentKey": "preferred", "userVerification": "required"},
        "attestation": "none",
        "timeout": CEREMONY_TIMEOUT_MS,
        "excludeCredentials": enrolled
            .iter()
            .map(|id| json!({"type": "public-key", "id": id}))
            .collect::<Vec<_>>(),
    })))
}

#[derive(Deserialize)]
struct Attestation {
    response: AttestationBody,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AttestationBody {
    #[serde(rename = "clientDataJSON")]
    client_data_json: String,
    attestation_object: String,
}

#[derive(Serialize)]
struct Registered {
    role: Role,
}

enum Registration {
    Done,
    CodeGone,
    Duplicate,
}

async fn register_finish(
    State(state): State<Shared>,
    Json(attestation): Json<Attestation>,
) -> Result<Json<Registered>, StatusCode> {
    let now = dates::now_secs();
    let response = RegistrationResponse {
        client_data_json: decode(&attestation.response.client_data_json)?,
        attestation_object: decode(&attestation.response.attestation_object)?,
    };
    let challenge = challenge_of(&response.client_data_json)?;
    let Some(Pending::Register { role, code, handle }) = state.challenges.take(&challenge, now)
    else {
        return Err(StatusCode::UNAUTHORIZED);
    };
    let registration = state
        .relying_party
        .register(&response, &challenge, role.policy())
        .map_err(|refusal| refused(&refusal))?;

    let id = encode(&registration.credential_id);
    let public_key = encode(registration.key.to_sec1_point(false).as_bytes());
    let outcome = commit(&state, move |auth| {
        let live = auth
            .codes
            .get(&role)
            .is_some_and(|stored| stored.hash == code && stored.expires_at > now);
        if !live {
            return (None, Registration::CodeGone);
        }
        if auth.credentials.contains_key(&id) {
            return (None, Registration::Duplicate);
        }
        let mut next = auth.clone();
        next.codes.remove(&role);
        let user = next.handle_of(role).cloned().unwrap_or(handle);
        next.users.insert(user.clone(), super::state::User { role });
        next.credentials.insert(
            id,
            Credential {
                user,
                public_key,
                sign_count: registration.sign_count,
                backup_eligible: registration.backup_eligible,
                aaguid: hex::encode(registration.aaguid),
                created_at: now,
            },
        );
        (Some(next), Registration::Done)
    })
    .await?;
    match outcome {
        Registration::Done => Ok(Json(Registered { role })),
        Registration::CodeGone => Err(StatusCode::UNAUTHORIZED),
        Registration::Duplicate => Err(StatusCode::CONFLICT),
    }
}

#[derive(Deserialize)]
struct Which {
    role: Role,
}

async fn login_options(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(which): Json<Which>,
) -> Result<Json<Value>, StatusCode> {
    let role = which.role;
    let enrolled: Vec<String> = state
        .auth
        .read(|auth| auth.credential_ids(role).into_iter().cloned().collect());
    if enrolled.is_empty() {
        return Err(StatusCode::NOT_FOUND);
    }
    let challenge = state
        .challenges
        .issue(
            Pending::Login { role },
            &client_address(&headers),
            dates::now_secs(),
        )
        .ok_or(StatusCode::TOO_MANY_REQUESTS)?;
    Ok(Json(json!({
        "challenge": encode(challenge),
        "rpId": state.rp_id,
        "userVerification": "required",
        "timeout": CEREMONY_TIMEOUT_MS,
        "allowCredentials": enrolled
            .iter()
            .map(|id| json!({"type": "public-key", "id": id}))
            .collect::<Vec<_>>(),
    })))
}

#[derive(Deserialize)]
struct Assertion {
    id: String,
    response: AssertionBody,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AssertionBody {
    #[serde(rename = "clientDataJSON")]
    client_data_json: String,
    authenticator_data: String,
    signature: String,
}

#[derive(Serialize)]
struct Tokens {
    access_token: String,
    token_type: &'static str,
    expires_in: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    refresh_token: Option<String>,
    role: Role,
}

fn refresh_token(family: &str, secret: &str) -> String {
    format!("{family}.{secret}")
}

fn secret_hash(secret: &str) -> String {
    hex::encode(Sha256::digest(secret))
}

async fn login_finish(
    State(state): State<Shared>,
    Json(assertion): Json<Assertion>,
) -> Result<Json<Tokens>, StatusCode> {
    let now = dates::now_secs();
    let response = AssertionResponse {
        client_data_json: decode(&assertion.response.client_data_json)?,
        authenticator_data: decode(&assertion.response.authenticator_data)?,
        signature: decode(&assertion.response.signature)?,
    };
    let challenge = challenge_of(&response.client_data_json)?;
    let Some(Pending::Login { role }) = state.challenges.take(&challenge, now) else {
        return Err(StatusCode::UNAUTHORIZED);
    };
    let credential = state
        .auth
        .read(|auth| {
            let credential = auth.credentials.get(&assertion.id)?;
            (auth.role_of(credential) == role).then(|| credential.clone())
        })
        .ok_or(StatusCode::UNAUTHORIZED)?;
    let count = match state.relying_party.assert(
        &response,
        &credential.key(),
        credential.sign_count,
        &challenge,
        role.policy(),
    ) {
        Ok(count) => count,
        Err(Refusal::CounterRegression) => {
            notify(
                &state,
                "Passkey counter went backwards",
                format!(
                    "A {} passkey signed in with a lower sign count than before, which a copied \
                     key would do.",
                    role.name()
                ),
            )
            .await;
            return Err(StatusCode::UNAUTHORIZED);
        }
        Err(refusal) => return Err(refused(&refusal)),
    };

    let id = assertion.id;
    let user = credential.user.clone();
    let family = (role == Role::Daily).then(|| {
        (
            hex::encode(random_bytes::<16>()),
            encode(random_bytes::<32>()),
        )
    });
    let new_family = family.clone();
    let recorded = commit(&state, move |auth| {
        if !auth.credentials.contains_key(&id) {
            return (None, false);
        }
        let mut next = auth.clone();
        next.credentials
            .get_mut(&id)
            .expect("the credential was just found")
            .sign_count = count;
        if let Some((family, secret)) = new_family {
            next.families.retain(|_, family| family.is_live(now));
            next.families.insert(
                family,
                Family {
                    user,
                    credential: id,
                    current: secret_hash(&secret),
                    previous: None,
                    rotated_at: now,
                    created_at: now,
                    last_used: now,
                },
            );
        }
        (Some(next), true)
    })
    .await?;
    if !recorded {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let refresh = family.map(|(family, secret)| refresh_token(&family, &secret));
    Ok(Json(tokens(&state, role, &credential.user, now, refresh)))
}

fn tokens(state: &Shared, role: Role, subject: &str, now: i64, refresh: Option<String>) -> Tokens {
    Tokens {
        access_token: state
            .issuer
            .mint(role.audience(), subject, state.username(role), now),
        token_type: "Bearer",
        expires_in: role.audience().lifetime_seconds(),
        refresh_token: refresh,
        role,
    }
}

#[derive(Deserialize)]
struct Refresh {
    refresh_token: String,
}

enum Grant {
    Invalid,
    Reused,
    Rotated { user: String },
}

type GrantError = (StatusCode, Json<Value>);

fn invalid_grant() -> GrantError {
    (
        StatusCode::BAD_REQUEST,
        Json(json!({"error": "invalid_grant"})),
    )
}

async fn refresh(
    State(state): State<Shared>,
    Json(body): Json<Refresh>,
) -> Result<Json<Tokens>, GrantError> {
    let now = dates::now_secs();
    let (family, secret) = body
        .refresh_token
        .split_once('.')
        .ok_or_else(invalid_grant)?;
    let family = family.to_owned();
    let presented = secret_hash(secret);
    let next_secret = encode(random_bytes::<32>());
    let grant = commit(&state, {
        let (family, next_secret) = (family.clone(), next_secret.clone());
        move |auth| {
            let Some(found) = auth.families.get(&family) else {
                return (None, Grant::Invalid);
            };
            let mut next = auth.clone();
            if !found.is_live(now) {
                next.families.remove(&family);
                return (Some(next), Grant::Invalid);
            }
            let retry = found.is_retry(&presented, now);
            if found.current != presented && !retry {
                next.families.remove(&family);
                return (Some(next), Grant::Reused);
            }
            let user = found.user.clone();
            let entry = next
                .families
                .get_mut(&family)
                .expect("the family was just found");
            if retry {
                entry.current = secret_hash(&next_secret);
            } else {
                entry.previous = Some(std::mem::replace(
                    &mut entry.current,
                    secret_hash(&next_secret),
                ));
                entry.rotated_at = now;
            }
            entry.last_used = now;
            (Some(next), Grant::Rotated { user })
        }
    })
    .await
    .map_err(|status| (status, Json(json!({"error": "server_error"}))))?;
    match grant {
        Grant::Invalid => Err(invalid_grant()),
        Grant::Reused => {
            notify(
                &state,
                "Refresh token reused",
                "A refresh token that had already been used was presented, so its sign-in was \
                 ended."
                    .to_owned(),
            )
            .await;
            Err(invalid_grant())
        }
        Grant::Rotated { user } => Ok(Json(tokens(
            &state,
            Role::Daily,
            &user,
            now,
            Some(refresh_token(&family, &next_secret)),
        ))),
    }
}

async fn discovery(State(state): State<Shared>) -> Json<Value> {
    Json(state.issuer.discovery())
}

async fn jwks(State(state): State<Shared>) -> Json<Value> {
    Json(state.issuer.jwks())
}

async fn userinfo(
    State(state): State<Shared>,
    headers: HeaderMap,
) -> Result<Json<Value>, StatusCode> {
    let token = headers
        .get("Authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .ok_or(StatusCode::UNAUTHORIZED)?;
    let claims = state
        .issuer
        .verify(token, Audience::Daily, dates::now_secs())
        .map_err(|_| StatusCode::UNAUTHORIZED)?;
    Ok(Json(
        json!({"sub": claims.sub, "preferred_username": claims.preferred_username}),
    ))
}
