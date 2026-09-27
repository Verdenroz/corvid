use std::sync::Arc;

use corvid_config::{or_exit, secret, section};
use corvid_milter::daemon::callbacks;
use corvid_rewrite::Rewriter;
use corvid_sign::Signer;
use indymilter::Config;
use tokio::net::TcpListener;
use tokio::signal;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "corvid_milter=info".into()),
        )
        .init();

    let config = or_exit(corvid_config::Config::load());
    let proxy_base = or_exit(config.proxy_base());
    let milter = or_exit(section(config.milter, "milter"));
    let key = or_exit(secret("CORVID_IMGPROXY_SECRET"));
    let bind = milter.bind;

    let rewriter = Arc::new(Rewriter::new(Signer::new(key.into_bytes()), proxy_base));
    let listener = TcpListener::bind(&bind).await.expect("cannot bind");
    tracing::info!(%bind, "corvid-milter listening");

    indymilter::run(listener, callbacks(rewriter), Config::default(), async {
        shutdown().await;
    })
    .await
    .expect("milter failed");
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
