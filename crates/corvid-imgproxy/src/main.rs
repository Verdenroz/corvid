use corvid_config::or_exit;
use corvid_imgproxy::{Config, router};
use tokio::net::TcpListener;
use tokio::signal;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "corvid_imgproxy=info".into()),
        )
        .init();

    let settings = or_exit(corvid_config::Config::load())
        .imgproxy
        .unwrap_or_default();
    let secret = or_exit(corvid_config::secret("CORVID_IMGPROXY_SECRET"));
    let bind = settings.bind;
    let config = Config {
        secret: secret.into_bytes(),
        max_bytes: settings.max_bytes,
        cache_entries: settings.cache_entries,
    };

    let listener = TcpListener::bind(&bind).await.expect("cannot bind");
    tracing::info!(%bind, "corvid-imgproxy listening");
    axum::serve(listener, router(config))
        .with_graceful_shutdown(async {
            shutdown().await;
        })
        .await
        .expect("server failed");
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
