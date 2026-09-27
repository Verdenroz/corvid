use axum::Router;
use axum::routing::get;
use corvid_imgproxy::{Config, Signer, router};
use std::num::NonZeroUsize;
use tokio::net::TcpListener;

const SECRET: &[u8] = b"integration test secret";
const GIF: [u8; 6] = *b"GIF89a";

async fn serve(app: Router) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}")
}

async fn upstream() -> String {
    serve(Router::new().route(
        "/pixel.gif",
        get(async || {
            (
                [(axum::http::header::CONTENT_TYPE, "image/gif")],
                GIF.to_vec(),
            )
        }),
    ))
    .await
}

async fn proxy() -> String {
    serve(router(Config {
        secret: SECRET.to_vec(),
        max_bytes: 1024,
        cache_entries: NonZeroUsize::new(8).unwrap(),
    }))
    .await
}

#[tokio::test]
async fn a_minted_loopback_literal_is_not_fetched() {
    let target = format!("{}/pixel.gif", upstream().await);
    let proxy = proxy().await;
    let response = reqwest::get(format!("{proxy}/i/{}", Signer::new(SECRET).mint(&target)))
        .await
        .unwrap();

    assert_ne!(response.status(), 200, "proxy fetched a loopback address");
}

#[tokio::test]
async fn a_forged_signature_is_refused() {
    let proxy = proxy().await;
    let payload = Signer::new(b"a different key".to_vec()).mint("https://example.net/pixel.gif");
    let (_, payload) = payload.split_once('/').unwrap();
    let response = reqwest::get(format!("{proxy}/i/bm90YXNpZw/{payload}"))
        .await
        .unwrap();

    assert_eq!(response.status(), 403);
}
