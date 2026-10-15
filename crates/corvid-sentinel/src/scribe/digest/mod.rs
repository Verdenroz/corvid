//! The overnight digest: Inbox mail since the last one, grouped by label with each thread's stored
//! summary. It is assembled without a model, so it can only repeat what a message says about itself.

mod build;
mod serve;
mod source;

use std::sync::Arc;
use std::time::Duration;

use axum::Json;
use axum::Router;
use axum::http::StatusCode;
use axum::routing::get;
use jiff::Timestamp;
use jiff::civil::Time;
use jiff::tz::TimeZone;

use super::{Shared, commit};
use crate::devices::Courier;
use crate::http::{Off, off_json};

const FIRST_WINDOW_SECONDS: i64 = 24 * 3600;
/// A window closes this long before it is built, so mail still being delivered falls into the next.
const SETTLE_SECONDS: i64 = 60;

pub struct Settings {
    pub at: Time,
    pub zone: TimeZone,
    /// How often the schedule is checked.
    pub poll: Duration,
    /// Seconds since the epoch.
    pub clock: Arc<dyn Fn() -> i64 + Send + Sync>,
}

pub(super) fn routes(state: Shared) -> Router {
    Router::new()
        .route("/corvid/digest", get(serve::list))
        .route("/corvid/digest/latest", get(serve::latest))
        .route("/corvid/digest/{id}", get(serve::one))
        .with_state(state)
}

pub(super) fn off() -> Router {
    async fn off() -> (StatusCode, Json<Off>) {
        off_json("digest_off")
    }
    Router::new()
        .route("/corvid/digest", get(off))
        .route("/corvid/digest/latest", get(off))
        .route("/corvid/digest/{id}", get(off))
}

/// Checks the schedule for the life of the process.
pub(super) async fn run(state: Shared, courier: Courier, settings: Settings) {
    loop {
        tick(&state, &courier, &settings).await;
        tokio::time::sleep(settings.poll).await;
    }
}

/// The local date, as the id of the digest due now: `at` has passed today and none exists for
/// today yet.
fn due(settings: &Settings, last: Option<&str>, now: i64) -> Option<String> {
    let local = Timestamp::from_second(now)
        .expect("a clock reading is a valid timestamp")
        .to_zoned(settings.zone.clone());
    let id = local.date().to_string();
    (local.time() >= settings.at && last != Some(id.as_str())).then_some(id)
}

async fn tick(state: &Shared, courier: &Courier, settings: &Settings) {
    let now = (settings.clock)();
    let (last_id, last_to) = state.scribe.read(|scribe| {
        let last = scribe.digests.last();
        (
            last.map(|digest| digest.id.clone()),
            last.map(|digest| digest.to),
        )
    });
    let Some(id) = due(settings, last_id.as_deref(), now) else {
        return;
    };
    let to = now - SETTLE_SECONDS;
    let from = last_to.unwrap_or(to - FIRST_WINDOW_SECONDS);
    let messages = match source::inbox(courier.keys(), from, to).await {
        Ok(messages) => messages,
        Err(error) => {
            tracing::warn!(%error, "digest: cannot read the Inbox");
            return;
        }
    };
    let summaries = state.scribe.read(|scribe| scribe.summaries.clone());
    let announced = id.clone();
    let digest = super::state::Digest {
        id,
        from,
        to,
        groups: build::groups(messages, &summaries),
    };
    let has_mail = !digest.groups.is_empty();
    let kept = commit(state.clone(), "digest", move |scribe| {
        (Some(scribe.with_digest(digest)), ())
    })
    .await;
    match kept {
        Ok(()) if has_mail => courier.announce_digest(&announced).await,
        Ok(()) => {}
        Err(_) => tracing::warn!("digest: cannot keep the digest"),
    }
}
