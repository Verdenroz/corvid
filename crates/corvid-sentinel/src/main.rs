use std::io;
use std::process::ExitCode;
use std::time::Duration;

use corvid_config::{or_exit, secret, section};
use corvid_sentinel::{Config, build, devices, open};
use tokio::net::TcpListener;
use tokio::signal;

/// Where the host's update directory is mounted; absent where no host updater exists.
const UPDATE_DIR: &str = "/var/lib/corvid-update";
const FCM_ORIGIN: &str = "https://fcm.googleapis.com";
const UPDATE_POLL: Duration = Duration::from_secs(60);

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
    let push = settings.push.is_some();
    let devices = settings.devices.map(|devices| {
        device_config(
            config.server,
            devices,
            push,
            hmac_secret.as_bytes().to_vec(),
        )
    });
    let bind = settings.bind.clone();
    let (app, watcher) = build(Config {
        secret: hmac_secret.into_bytes(),
        state_path: settings.state,
        seen,
        update_dir: UPDATE_DIR.into(),
        update_poll: UPDATE_POLL,
        devices,
    });

    tokio::spawn(watcher);
    let listener = TcpListener::bind(&bind).await.expect("cannot bind");
    tracing::info!(%bind, "corvid-sentinel listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown())
        .await
        .expect("server failed");
    ExitCode::SUCCESS
}

fn device_config(
    server: Option<corvid_config::Server>,
    settings: corvid_config::Devices,
    push: bool,
    webhook_secret: Vec<u8>,
) -> devices::Config {
    let server = or_exit(section(server, "server"));
    let stalwart_token = or_exit(secret("CORVID_STALWART_TOKEN"));
    let state = devices::open(&settings.state).unwrap_or_else(|error| {
        tracing::error!(%error, state = %settings.state.display(), "refusing to start");
        std::process::exit(1);
    });
    let push = push.then(|| {
        devices::PushConfig::open(&settings.state, server.acme_contact.as_str(), FCM_ORIGIN)
            .unwrap_or_else(|error| {
                tracing::error!(%error, "refusing to start without a readable VAPID key");
                std::process::exit(1);
            })
    });
    devices::Config {
        secret: webhook_secret,
        state_path: settings.state,
        devices: state,
        issuer: settings.issuer.unwrap_or_else(|| server.idp_issuer()),
        idp_url: settings.idp_url,
        admin_user: server.admin_user(),
        keys_address: server.keys_address(),
        mail_address: format!("{}@{}", server.mail_user(), server.domain.as_str()),
        stalwart_url: settings.stalwart_url,
        stalwart_token,
        push,
    }
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
