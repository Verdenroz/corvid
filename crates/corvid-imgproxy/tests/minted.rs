use std::num::NonZeroUsize;

use corvid_config::Config as Settings;
use corvid_imgproxy::{Config, Signer, router};
use corvid_rewrite::Rewriter;
use tokio::net::TcpListener;

const SECRET: &[u8] = b"integration test secret";

#[tokio::test]
async fn a_url_the_rewriter_mints_from_the_default_base_is_routed_by_the_proxy() {
    let settings = Settings::parse(
        "server:\n  domain: example.com\n  hostname: mail.example.com\n  mail_user: alice\n  acme_contact: ops@elsewhere.example\n",
    )
    .unwrap();
    let base = settings.proxy_base().unwrap();
    let rewriter = Rewriter::new(Signer::new(SECRET), base.clone());
    let (html, _) = rewriter
        .rewrite(r#"<img src="https://cdn.invalid/photo.jpg" width="300" height="200">"#)
        .unwrap();
    let minted = html
        .split('"')
        .find(|part| part.starts_with(&base))
        .unwrap();
    // The proxy sees the whole path after the origin, whatever part of it the base supplied.
    let after_scheme = &minted[minted.find("://").unwrap() + 3..];
    let path = &after_scheme[after_scheme.find('/').unwrap()..];

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = router(Config {
        secret: SECRET.to_vec(),
        max_bytes: 1024,
        cache_entries: NonZeroUsize::new(8).unwrap(),
    });
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

    let response = reqwest::get(format!("http://{addr}{path}")).await.unwrap();

    assert_ne!(
        response.status(),
        404,
        "{minted} does not match the proxy's route"
    );
}
