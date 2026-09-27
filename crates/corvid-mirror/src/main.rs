use std::process::ExitCode;

use corvid_config::{Config, or_exit, secret, section};
use corvid_mirror::{Mailbox, audit, sync, verify};

fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "corvid_mirror=info".into()),
        )
        .init();

    let config = or_exit(Config::load());
    let mirror = or_exit(section(config.mirror, "mirror"));
    let password = or_exit(secret("CORVID_JMAP_PASSWORD"));
    let (root, url, user) = (mirror.root, mirror.jmap_url, mirror.jmap_user);

    let mailbox = match Mailbox::connect(&url, &user, &password) {
        Ok(mailbox) => mailbox,
        Err(error) => {
            tracing::error!(%error, "cannot reach the mail server");
            return ExitCode::FAILURE;
        }
    };

    // Sync cannot see a message that never arrived, audit cannot see damage to what did, and
    // only verify runs without the server.
    let fetched = match sync(&root, &mailbox) {
        Ok(summary) => summary,
        Err(error) => {
            tracing::error!(%error, "sync failed");
            return ExitCode::FAILURE;
        }
    };
    tracing::info!(
        fetched = fetched.fetched,
        already_held = fetched.already_held,
        "synced"
    );
    for id in &fetched.unavailable {
        tracing::warn!(%id, "listed but not returned; deleted since, or a fetch that failed");
    }

    let report = match audit(&root, &mailbox) {
        Ok(report) => report,
        Err(error) => {
            tracing::error!(%error, "audit failed");
            return ExitCode::FAILURE;
        }
    };
    for id in &report.vanished {
        tracing::info!(%id, "no longer on the server; the copy here is kept");
    }
    if !report.missing.is_empty() {
        tracing::error!(
            missing = report.missing.len(),
            first = %report.missing[0],
            "the server holds messages this archive does not"
        );
        return ExitCode::FAILURE;
    }

    match verify(&root) {
        Ok(damaged) if damaged.is_empty() => {
            tracing::info!("archive verified");
            ExitCode::SUCCESS
        }
        Ok(damaged) => {
            tracing::error!(damaged = damaged.len(), first = %damaged[0], "stored bodies do not match their digests");
            ExitCode::FAILURE
        }
        Err(error) => {
            tracing::error!(%error, "verification failed");
            ExitCode::FAILURE
        }
    }
}
