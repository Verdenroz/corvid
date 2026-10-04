use std::io;
use std::process::ExitCode;

use corvid_config::{EX_TEMPFAIL, or_exit, secret, section};
use corvid_sentinel::{Config, Jmap, open, router};
use tokio::net::TcpListener;
use tokio::signal;

/// Where the host's update directory is mounted; absent where no host updater exists.
const UPDATE_DIR: &str = "/var/lib/corvid-update";

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "corvid_sentinel=info".into()),
        )
        .init();

    let config = or_exit(corvid_config::Config::load());
    let settings = or_exit(section(config.sentinel, "sentinel"));
    let hmac_secret = or_exit(secret("CORVID_SENTINEL_SECRET"));
    let password = or_exit(secret("CORVID_JMAP_PASSWORD"));

    let seen = match open(&settings.state) {
        Ok(seen) => seen,
        // A corrupt or foreign-schema file is a refusal, not a fresh start: starting empty
        // would silently re-alert on every credential and address the account already uses.
        Err(error) if error.kind() == io::ErrorKind::InvalidData => {
            tracing::error!(%error, state = %settings.state.display(), "refusing to start");
            return ExitCode::FAILURE;
        }
        Err(error) => {
            tracing::error!(%error, "cannot read state");
            return ExitCode::FAILURE;
        }
    };
    // `Jmap::connect` builds a blocking reqwest client, which spins up its own runtime; doing
    // that on a tokio worker thread panics when it is later dropped.
    let jmap_url = settings.jmap_url.clone();
    let jmap_user = settings.jmap_user.clone();
    let connected =
        tokio::task::spawn_blocking(move || Jmap::connect(&jmap_url, &jmap_user, &password))
            .await
            .expect("connecting to the mail server panicked");
    let jmap = match connected {
        Ok(jmap) => jmap,
        Err(error) if corvid_jmap::unreachable(&error) => {
            tracing::error!(%error, "the mail server is not up yet");
            return ExitCode::from(EX_TEMPFAIL);
        }
        Err(error) => {
            tracing::error!(%error, "cannot reach the mail server");
            return ExitCode::FAILURE;
        }
    };

    let bind = settings.bind.clone();
    let app = router(Config {
        secret: hmac_secret.into_bytes(),
        state_path: settings.state,
        seen,
        notifier: jmap,
        update_dir: UPDATE_DIR.into(),
    });

    let listener = TcpListener::bind(&bind).await.expect("cannot bind");
    tracing::info!(%bind, "corvid-sentinel listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown())
        .await
        .expect("server failed");
    ExitCode::SUCCESS
}

/// Returns on Ctrl-C or SIGTERM. As a container's PID 1 this process ignores any signal it has
/// no handler for, so without the SIGTERM arm a stop waits out the runtime's kill timeout.
async fn shutdown() {
    let mut term = signal::unix::signal(signal::unix::SignalKind::terminate())
        .expect("cannot install a SIGTERM handler");
    tokio::select! {
        _ = signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}
