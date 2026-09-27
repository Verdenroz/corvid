mod cache;
mod error;
mod fetch;
mod resolver;

use std::num::NonZeroUsize;
use std::sync::Arc;

use axum::Router;
use axum::extract::{Path, State};
use axum::http::header::{
    CACHE_CONTROL, CONTENT_SECURITY_POLICY, CONTENT_TYPE, X_CONTENT_TYPE_OPTIONS,
};
use axum::response::{IntoResponse, Response};
use axum::routing::get;

pub use corvid_sign::Signer;

use cache::Cache;
use error::ProxyError;

pub struct Config {
    pub secret: Vec<u8>,
    pub max_bytes: usize,
    pub cache_entries: NonZeroUsize,
}

pub struct AppState {
    signer: Signer,
    client: reqwest::Client,
    cache: Cache,
    max_bytes: usize,
}

pub fn router(config: Config) -> Router {
    let state = Arc::new(AppState {
        signer: Signer::new(config.secret),
        client: fetch::client(),
        cache: Cache::new(config.cache_entries),
        max_bytes: config.max_bytes,
    });
    Router::new()
        .route("/healthz", get(async || "ok"))
        .route("/i/{signature}/{payload}", get(image))
        .with_state(state)
}

async fn image(
    State(state): State<Arc<AppState>>,
    Path((signature, payload)): Path<(String, String)>,
) -> Result<Response, ProxyError> {
    let Some(url) = state.signer.open(&signature, &payload) else {
        return Err(ProxyError::Unsigned);
    };
    let image = match state.cache.get(&payload) {
        Some(image) => image,
        None => {
            let image = fetch::fetch(&state.client, &url, state.max_bytes).await?;
            state.cache.put(payload, image.clone());
            image
        }
    };
    Ok((
        [
            (CONTENT_TYPE, image.content_type),
            (CACHE_CONTROL, "private, max-age=86400".to_owned()),
            (X_CONTENT_TYPE_OPTIONS, "nosniff".to_owned()),
            (CONTENT_SECURITY_POLICY, "default-src 'none'".to_owned()),
        ],
        image.body,
    )
        .into_response())
}
