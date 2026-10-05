use std::io;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use serde::Deserialize;

use crate::http::AppState;
use crate::state::NoticeKind;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Status {
    result: String,
    checked_at: Option<String>,
    detail: Option<String>,
}

/// Polls the updater's `status.json` forever, turning each failed or rolled-back update into a
/// notice once. A tick that errors or panics is logged and the next one still runs.
pub(crate) async fn updates(state: Arc<AppState>, dir: PathBuf, every: Duration) {
    let mut ticks = tokio::time::interval(every);
    loop {
        ticks.tick().await;
        match tokio::spawn(check(state.clone(), dir.clone())).await {
            Ok(Ok(())) => {}
            Ok(Err(error)) => tracing::warn!(%error, "cannot check the update status"),
            Err(error) => tracing::error!(%error, "the update status check panicked"),
        }
    }
}

async fn check(state: Arc<AppState>, dir: PathBuf) -> io::Result<()> {
    let body = match tokio::fs::read(dir.join("status.json")).await {
        Ok(body) => body,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    let status: Status = serde_json::from_slice(&body).map_err(io::Error::other)?;
    let title = match status.result.as_str() {
        "rolledBack" => "Update rolled back",
        "failed" => "Update failed",
        _ => return Ok(()),
    };
    let Some(checked_at) = status.checked_at else {
        return Ok(());
    };
    let detail = status
        .detail
        .unwrap_or_else(|| "The updater gave no detail.".to_owned());
    tokio::task::spawn_blocking(move || {
        state.commit(|seen| {
            if seen.update_noticed.as_deref() == Some(&checked_at) {
                return None;
            }
            let mut next = seen.clone();
            next.update_noticed = Some(checked_at.clone());
            next.push_notice(NoticeKind::Update, checked_at, title.to_owned(), detail);
            Some(next)
        })
    })
    .await
    .expect("the blocking task panicked")
}
