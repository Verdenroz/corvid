use std::io::ErrorKind;
use std::process::ExitCode;
use std::thread::sleep;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use corvid_config::{Config, EX_TEMPFAIL, or_exit, secret, section};
use corvid_model::Model;
use corvid_scribe::{Jmap, Sentinel, Settings, run};

fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "corvid_scribe=info".into()),
        )
        .init();

    // As a container's PID 1 this process ignores signals it has no handler for. The state file
    // is written atomically, so exiting mid-pass loses at most the pass in flight.
    ctrlc::set_handler(|| std::process::exit(0)).expect("cannot install a SIGTERM handler");

    let config = or_exit(Config::load());
    let scribe = or_exit(section(config.scribe, "scribe"));
    let password = or_exit(secret("CORVID_JMAP_PASSWORD"));
    let shared = or_exit(secret("CORVID_SCRIBE_SECRET"));

    let sentinel = match Sentinel::connect(&scribe.sentinel_url, &shared) {
        Ok(sentinel) => sentinel,
        Err(error) => {
            tracing::error!(%error, "cannot build a sentinel client");
            return ExitCode::FAILURE;
        }
    };
    let model = match Model::connect(
        &scribe.model.url,
        &scribe.model.name,
        scribe.model.request_extra,
    ) {
        Ok(model) => model,
        Err(error) => {
            tracing::error!(%error, "cannot build a model client");
            return ExitCode::FAILURE;
        }
    };
    let mailbox = match Jmap::connect(&scribe.jmap_url, &scribe.jmap_user, &password) {
        Ok(mailbox) => mailbox,
        Err(error) if corvid_jmap::unreachable(&error) => {
            tracing::error!(%error, "the mail server is not up yet");
            return ExitCode::from(EX_TEMPFAIL);
        }
        Err(error) => {
            tracing::error!(%error, "cannot reach the mail server");
            return ExitCode::FAILURE;
        }
    };

    let settings = Settings {
        exclude_mailboxes: scribe.exclude_mailboxes,
        min_messages: scribe.min_messages,
        long_body_chars: scribe.long_body_chars,
        max_input_chars: scribe.max_input_chars,
        model: scribe.model.name,
        max_tokens: scribe.model.max_tokens,
        budget: scribe.model.budget,
        deadline: Duration::from_secs(scribe.model.deadline_secs),
    };
    let interval = Duration::from_secs(scribe.interval_secs);
    tracing::info!(url = %scribe.jmap_url, "corvid-scribe polling");

    loop {
        match run(
            &scribe.state,
            &mailbox,
            &sentinel,
            &model,
            &settings,
            unix_now(),
        ) {
            Ok(summary) => tracing::info!(
                stored = summary.stored,
                unusable = summary.unusable,
                deleted = summary.deleted,
                waiting = summary.waiting,
                "passed"
            ),
            // Stop rather than retry: deleting the state to "fix" it would discard the cursors
            // and look like a clean first run, which skips every thread changed since.
            Err(error) if error.kind() == ErrorKind::InvalidData => {
                tracing::error!(%error, state = %scribe.state.display(), "refusing to continue");
                return ExitCode::FAILURE;
            }
            Err(error) => tracing::warn!(%error, "pass failed; retrying"),
        }
        sleep(interval);
    }
}

fn unix_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("the clock is after 1970")
        .as_secs() as i64
}
