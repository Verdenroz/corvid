use std::time::{Duration, Instant};

use corvid_sentinel::aliases::stalwart::{Alias, Aliases, Error, Target};
use serde_json::{Map, Value, json};

use crate::stalwart::{MAIL_ACCOUNT, MAIL_DOMAIN, Stalwart, TOKEN};

fn client(stalwart: &Stalwart, timeout: Duration) -> Aliases {
    Aliases::new(
        stalwart.url.clone(),
        TOKEN.to_owned(),
        "mailuser@rig.test".to_owned(),
        timeout,
    )
}

fn patch(pointer: &str, value: Value) -> Map<String, Value> {
    [(pointer.to_owned(), value)].into_iter().collect()
}

fn alias(index: usize, name: &str, enabled: bool) -> Alias {
    Alias {
        index,
        name: name.to_owned(),
        enabled,
    }
}

#[tokio::test]
async fn reading_finds_the_mail_account_by_address_with_its_domain_and_aliases_in_order() {
    let stalwart = Stalwart::start().await;
    stalwart.add_alias("shop.abcdefgh", false);

    let (target, aliases) = client(&stalwart, Duration::from_secs(5))
        .read()
        .await
        .unwrap();

    assert_eq!(
        target,
        Target {
            account: MAIL_ACCOUNT.to_owned(),
            domain: MAIL_DOMAIN.to_owned(),
        }
    );
    assert_eq!(
        aliases,
        vec![
            alias(0, "postmaster", true),
            alias(1, "abuse", true),
            alias(2, "shop.abcdefgh", false),
        ]
    );
}

#[tokio::test]
async fn a_patch_at_the_next_key_appends_an_alias() {
    let stalwart = Stalwart::start().await;
    let client = client(&stalwart, Duration::from_secs(5));
    let (target, _) = client.read().await.unwrap();

    client
        .patch(
            &target,
            patch(
                "aliases/2",
                json!({"name": "shop.abcdefgh", "domainId": target.domain}),
            ),
        )
        .await
        .unwrap();

    assert_eq!(stalwart.aliases()[2], ("shop.abcdefgh".to_owned(), true));
}

#[tokio::test]
async fn a_name_another_account_holds_is_refused() {
    let stalwart = Stalwart::start().await;
    stalwart.hold("shop.abcdefgh");
    let client = client(&stalwart, Duration::from_secs(5));
    let (target, _) = client.read().await.unwrap();

    let result = client
        .patch(
            &target,
            patch(
                "aliases/2",
                json!({"name": "shop.abcdefgh", "domainId": target.domain}),
            ),
        )
        .await;

    assert!(matches!(result, Err(Error::Refused(_))), "{result:?}");
    assert_eq!(stalwart.aliases().len(), 2);
}

#[tokio::test]
async fn one_429_is_waited_out() {
    let stalwart = Stalwart::start().await;
    stalwart.busy(1);

    let result = client(&stalwart, Duration::from_secs(5)).read().await;

    assert!(result.is_ok(), "{result:?}");
}

#[tokio::test]
async fn a_second_429_in_a_row_is_busy() {
    let stalwart = Stalwart::start().await;
    stalwart.busy(2);

    let result = client(&stalwart, Duration::from_secs(5)).read().await;

    assert_eq!(result, Err(Error::Busy));
}

#[tokio::test]
async fn a_server_that_never_answers_fails_within_the_timeout() {
    let stalwart = Stalwart::start().await;
    stalwart.hang();
    let started = Instant::now();

    let result = client(&stalwart, Duration::from_millis(300)).read().await;

    assert!(matches!(result, Err(Error::Failed(_))), "{result:?}");
    assert!(started.elapsed() < Duration::from_secs(5));
}
