use std::process::ExitCode;
use std::thread::sleep;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use corvid_config::{Config, EX_TEMPFAIL, or_exit, secret, section};
use corvid_labeler::{ActionLog, Budget, Jmap, Model, cold_start, poll_observed};

const MINE_EVERY: Duration = Duration::from_secs(86400);

fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "corvid_labeler=info".into()),
        )
        .init();

    // As a container's PID 1 this process ignores signals it has no handler for. The state file
    // is written atomically, so exiting mid-pass loses at most the message in flight.
    ctrlc::set_handler(|| std::process::exit(0)).expect("cannot install a SIGTERM handler");

    let config = or_exit(Config::load());
    let labeler = or_exit(section(config.labeler, "labeler"));
    let password = or_exit(secret("CORVID_JMAP_PASSWORD"));
    let (url, state) = (labeler.jmap_url, labeler.state);
    let actions_path = state.with_file_name("actions.json");
    let mut actions = match ActionLog::open(&actions_path) {
        Ok(actions) => actions,
        Err(error) => {
            tracing::error!(%error, state = %actions_path.display(), "refusing to continue");
            return ExitCode::FAILURE;
        }
    };
    let interval = Duration::from_secs(labeler.interval_secs);

    let cold = match labeler.model.map(ColdPass::new).transpose() {
        Ok(cold) => cold,
        Err(error) => {
            tracing::error!(%error, "cannot build a model client");
            return ExitCode::FAILURE;
        }
    };

    let mailbox = match Jmap::connect(&url, &labeler.jmap_user, &password) {
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
    tracing::info!(%url, "corvid-labeler polling");

    // The cold-start pass shares this loop because it writes the same state file; a separate
    // writer's save would drop whatever the other learned in between.
    let mut due = Instant::now();
    let mut mine_due = Instant::now();

    loop {
        match poll_observed(&state, &mailbox, |delta| {
            actions.record(&mailbox, &labeler.jmap_user, unix_now(), delta)
        }) {
            Ok(summary) => tracing::info!(
                labelled = summary.labelled,
                written = summary.written,
                unkeyed = summary.unkeyed,
                "polled"
            ),
            // Stop rather than retry: deleting the state to "fix" it would discard the prior
            // and census and look like a clean first run.
            Err(error) if error.kind() == std::io::ErrorKind::InvalidData => {
                tracing::error!(%error, state = %state.display(), "refusing to continue");
                return ExitCode::FAILURE;
            }
            Err(error) => tracing::warn!(%error, "poll failed; retrying"),
        }

        if let Some(cold) = &cold
            && Instant::now() >= due
        {
            due = Instant::now() + cold.interval;
            match cold_start(&state, &mailbox, &cold.model, &cold.budget, cold.confidence) {
                Ok(summary) => tracing::info!(
                    attempted = summary.attempted,
                    labelled = summary.labelled,
                    none_fit = summary.none_fit,
                    unconfident = summary.unconfident,
                    off_options = summary.off_options,
                    out_of_time = summary.out_of_time,
                    too_many_labels = summary.too_many_labels,
                    "cold start"
                ),
                Err(error) if error.kind() == std::io::ErrorKind::InvalidData => {
                    tracing::error!(%error, state = %state.display(), "refusing to continue");
                    return ExitCode::FAILURE;
                }
                // The model host is off most of the time, which is the ordinary case here.
                Err(error) => tracing::info!(%error, "cold start did not run"),
            }
        }

        if Instant::now() >= mine_due {
            mine_due = Instant::now() + MINE_EVERY;
            match actions.mine(unix_now()) {
                Ok(candidates) => tracing::info!(candidates = candidates.len(), "mined"),
                Err(error) => tracing::warn!(%error, "mined candidates not saved"),
            }
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

/// The model and what one cold-start pass may spend on it.
struct ColdPass {
    model: Model,
    budget: Budget,
    confidence: f64,
    interval: Duration,
}

impl ColdPass {
    fn new(settings: corvid_config::Model) -> std::io::Result<Self> {
        Ok(Self {
            model: Model::connect(&settings.url, &settings.name, settings.request_extra)?,
            // A pass holds this thread, and slow calls never error, so the message count alone
            // cannot bound how long polling stops.
            budget: Budget {
                messages: settings.budget,
                time: Duration::from_secs(settings.deadline_secs),
            },
            confidence: settings.confidence.fraction(),
            interval: Duration::from_secs(settings.interval_secs),
        })
    }
}
