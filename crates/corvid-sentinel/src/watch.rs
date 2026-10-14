use std::io;
use std::path::{Path, PathBuf};
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
    busy: bool,
    #[serde(default)]
    stale: Vec<String>,
}

/// Polls the updater's `status.json` forever, turning each failed or rolled-back update, and each
/// run that found something stale off the host, into a notice once. A tick that errors or panics
/// is logged and the next one still runs.
pub(crate) async fn updates(state: Arc<AppState>, dir: PathBuf, every: Duration) {
    let mut ticks = tokio::time::interval(every);
    loop {
        ticks.tick().await;
        if let Err(error) = check(state.clone(), &dir).await {
            tracing::warn!(%error, "cannot check the update status");
        }
    }
}

async fn check(state: Arc<AppState>, dir: &Path) -> io::Result<()> {
    let body = match tokio::fs::read(dir.join("status.json")).await {
        Ok(body) => body,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    let status: Status = serde_json::from_slice(&body).map_err(io::Error::other)?;
    if status.busy {
        return Ok(());
    }
    let title = match status.result.as_str() {
        "rolledBack" => "Update rolled back",
        "failed" => "Update failed",
        _ if !status.stale.is_empty() => "Out of date off this host",
        _ => return Ok(()),
    };
    let Some(checked_at) = status.checked_at else {
        return Ok(());
    };
    let lines: Vec<String> = status.detail.into_iter().chain(status.stale).collect();
    let detail = if lines.is_empty() {
        "The updater gave no detail.".to_owned()
    } else {
        lines.join("\n")
    };
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
    .map_err(io::Error::other)?
}
