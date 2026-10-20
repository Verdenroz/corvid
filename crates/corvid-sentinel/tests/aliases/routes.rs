use std::sync::Arc;
use std::time::{Duration, Instant};

use corvid_sentinel::aliases::state::{Entry, Ledger};
use corvid_sentinel::aliases::{self, minted};
use corvid_sentinel::auth::soft::signing_key;
use corvid_sentinel::auth::{Audience, Issuer};
use corvid_sentinel::now_secs;
use reqwest::StatusCode;
use serde_json::{Value, json};

use crate::common;
use crate::stalwart::{Stalwart, TOKEN};

const ADMIN: &str = "mailuser-admin";
const ISSUER_URL: &str = "http://127.0.0.1:11333/internal/issuer";

struct Rig {
    url: String,
    issuer: Arc<Issuer>,
    stalwart: Stalwart,
    dir: tempfile::TempDir,
    timeout: Duration,
}

async fn rig() -> Rig {
    rig_with(Ledger::default(), Duration::from_secs(5)).await
}

async fn rig_with(ledger: Ledger, timeout: Duration) -> Rig {
    let dir = tempfile::tempdir().unwrap();
    let issuer = Arc::new(Issuer::new(signing_key(), ISSUER_URL.to_owned()));
    let stalwart = Stalwart::start().await;
    let url = serve(&dir, &issuer, &stalwart, ledger, timeout).await;
    Rig {
        url,
        issuer,
        stalwart,
        dir,
        timeout,
    }
}

/// A rig whose Stalwart client gives up after 300 ms.
async fn rig_timing_out() -> Rig {
    rig_with(Ledger::default(), Duration::from_millis(300)).await
}

async fn serve(
    dir: &tempfile::TempDir,
    issuer: &Arc<Issuer>,
    stalwart: &Stalwart,
    ledger: Ledger,
    timeout: Duration,
) -> String {
    let mut config = common::config(
        &dir.path().join("sentinel.json"),
        &dir.path().join("no-update"),
    );
    config.aliases = Some(aliases::Config {
        state_path: dir.path().join("aliases.json"),
        ledger,
        stalwart_url: stalwart.url.clone(),
        stalwart_token: TOKEN.to_owned(),
        mail_address: "mailuser@rig.test".to_owned(),
        admin_user: ADMIN.to_owned(),
        issuer: issuer.clone(),
        timeout,
    });
    common::serve(config).await
}

impl Rig {
    fn ledger(&self) -> Ledger {
        aliases::state::open(&self.dir.path().join("aliases.json")).unwrap()
    }

    /// A second sentinel over the same state file and Stalwart, as after a restart.
    async fn restart(&mut self) {
        let ledger = self.ledger();
        self.url = serve(
            &self.dir,
            &self.issuer,
            &self.stalwart,
            ledger,
            self.timeout,
        )
        .await;
    }

    fn daily(&self) -> String {
        self.issuer
            .mint(Audience::Daily, "handle", "mailuser", now_secs())
    }

    fn master(&self) -> String {
        self.issuer
            .mint(Audience::Master, "handle", ADMIN, now_secs())
    }

    async fn create_as(&self, bearer: &str, label: &str) -> reqwest::Response {
        reqwest::Client::new()
            .post(format!("{}/corvid/aliases", self.url))
            .bearer_auth(bearer)
            .json(&json!({"label": label}))
            .send()
            .await
            .unwrap()
    }

    async fn create(&self, label: &str) -> reqwest::Response {
        self.create_as(&self.daily(), label).await
    }

    async fn created(&self, label: &str) -> String {
        let response = self.create(label).await;
        assert_eq!(response.status(), StatusCode::CREATED);
        let body: Value = response.json().await.unwrap();
        body["name"].as_str().unwrap().to_owned()
    }

    async fn list(&self) -> Vec<Value> {
        reqwest::Client::new()
            .get(format!("{}/corvid/aliases", self.url))
            .bearer_auth(self.daily())
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap()
    }

    async fn switch(&self, name: &str, action: &str, master: Option<&str>) -> reqwest::Response {
        let mut request = reqwest::Client::new()
            .post(format!("{}/corvid/aliases/{name}/{action}", self.url))
            .bearer_auth(self.daily());
        if let Some(master) = master {
            request = request.header("X-Corvid-Master", master);
        }
        request.send().await.unwrap()
    }

    async fn disable(&self, name: &str) -> reqwest::Response {
        self.switch(name, "disable", Some(&self.master())).await
    }

    async fn enable(&self, name: &str) -> reqwest::Response {
        self.switch(name, "enable", Some(&self.master())).await
    }

    fn names(&self) -> Vec<String> {
        self.stalwart
            .aliases()
            .into_iter()
            .map(|(name, _)| name)
            .collect()
    }

    fn enabled(&self, name: &str) -> Vec<bool> {
        self.stalwart
            .aliases()
            .into_iter()
            .filter(|(held, _)| held == name)
            .map(|(_, enabled)| enabled)
            .collect()
    }
}

fn recent(count: usize) -> Ledger {
    Ledger {
        aliases: (0..count)
            .map(|n| {
                (
                    format!("old{n}.abcdefgh"),
                    Entry {
                        created_at: now_secs() - 60,
                        disabled_at: None,
                    },
                )
            })
            .collect(),
    }
}

#[tokio::test]
async fn a_created_alias_is_the_label_and_a_tag_and_takes_mail_on_the_account() {
    let rig = rig().await;

    let response = rig.create("Shop").await;

    assert_eq!(response.status(), StatusCode::CREATED);
    let body: Value = response.json().await.unwrap();
    let name = body["name"].as_str().unwrap();
    assert!(minted(name) && name.starts_with("shop."), "{name}");
    assert_eq!(body["address"], format!("{name}@rig.test"));
    assert_eq!(body["label"], "shop");
    assert_eq!(body["enabled"], true);
    assert_eq!(body["missing"], false);
    assert_eq!(rig.stalwart.aliases()[2], (name.to_owned(), true));
}

#[tokio::test]
async fn the_list_shows_minted_aliases_with_their_labels_and_not_postmaster_or_abuse() {
    let rig = rig().await;
    let name = rig.created("shop").await;

    let list = rig.list().await;

    assert_eq!(list.len(), 1);
    assert_eq!(list[0]["name"], name.as_str());
    assert_eq!(list[0]["label"], "shop");
    assert!(list[0]["createdAt"].is_string());
}

#[tokio::test]
async fn two_concurrent_creates_both_land() {
    let rig = rig().await;

    let (first, second) = tokio::join!(rig.create("one"), rig.create("two"));

    assert_eq!(first.status(), StatusCode::CREATED);
    assert_eq!(second.status(), StatusCode::CREATED);
    let names = rig.names();
    assert_eq!(names.len(), 4);
    assert!(names[2..].iter().all(|name| minted(name)), "{names:?}");
    assert_ne!(names[2], names[3]);
}

#[tokio::test]
async fn a_label_outside_letters_digits_and_hyphens_is_refused() {
    let rig = rig().await;

    let response = rig.create("aliases/0").await;

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert_eq!(rig.stalwart.aliases().len(), 2);
}

#[tokio::test]
async fn disabling_turns_off_every_entry_holding_the_name() {
    let rig = rig().await;
    let name = rig.created("shop").await;
    rig.stalwart.add_alias(&name, true);

    let response = rig.disable(&name).await;

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(rig.enabled(&name), vec![false, false]);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["enabled"], false);
}

#[tokio::test]
async fn a_disabled_alias_can_be_enabled_again() {
    let rig = rig().await;
    let name = rig.created("shop").await;
    rig.disable(&name).await;

    let response = rig.enable(&name).await;

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(rig.enabled(&name), vec![true]);
    assert_eq!(rig.list().await[0]["enabled"], true);
}

#[tokio::test]
async fn postmaster_and_abuse_cannot_be_disabled() {
    let rig = rig().await;

    for name in ["postmaster", "abuse"] {
        let response = rig.disable(name).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{name}");
    }
    assert_eq!(rig.enabled("postmaster"), vec![true]);
}

#[tokio::test]
async fn a_minted_name_the_account_does_not_hold_is_not_found() {
    let rig = rig().await;

    let response = rig.disable("shop.abcdefgh").await;

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn a_bearer_that_is_not_a_passkey_session_is_refused_without_a_sign_out() {
    let rig = rig().await;

    let response = rig.create_as("an-app-password", "shop").await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert!(response.headers().get("www-authenticate").is_none());
    assert_eq!(rig.stalwart.aliases().len(), 2);
}

#[tokio::test]
async fn a_passkey_session_of_another_user_is_refused() {
    let rig = rig().await;
    let other = rig
        .issuer
        .mint(Audience::Daily, "handle", "someone", now_secs());

    let response = rig.create_as(&other, "shop").await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn an_admin_token_is_not_a_passkey_session() {
    let rig = rig().await;

    let response = rig.create_as(&rig.master(), "shop").await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn the_list_needs_a_passkey_session() {
    let rig = rig().await;

    let response = reqwest::get(format!("{}/corvid/aliases", rig.url))
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn disabling_without_an_admin_token_is_refused() {
    let rig = rig().await;
    let name = rig.created("shop").await;

    let response = rig.switch(&name, "disable", None).await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(rig.enabled(&name), vec![true]);
}

#[tokio::test]
async fn an_admin_token_is_good_for_one_switch_only() {
    let rig = rig().await;
    let name = rig.created("shop").await;
    let master = rig.master();
    rig.switch(&name, "disable", Some(&master)).await;

    let response = rig.switch(&name, "enable", Some(&master)).await;

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(rig.enabled(&name), vec![false]);
}

#[tokio::test]
async fn a_stalwart_that_never_answers_is_a_bad_gateway_within_the_timeout() {
    let rig = rig_timing_out().await;
    rig.stalwart.hang();
    let started = Instant::now();

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
    assert!(started.elapsed() < Duration::from_secs(5));
}

#[tokio::test]
async fn a_stalwart_that_keeps_rate_limiting_is_unavailable() {
    let rig = rig().await;
    rig.stalwart.busy(2);

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn the_twenty_first_create_in_an_hour_is_too_many() {
    let rig = rig_with(recent(20), Duration::from_secs(5)).await;

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn a_create_past_four_hundred_enabled_aliases_is_too_many() {
    let rig = rig().await;
    for n in 0..400 {
        rig.stalwart.add_alias(&format!("x{n}.abcdefgh"), true);
    }

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn a_create_past_a_thousand_aliases_disabled_or_not_is_too_many() {
    let rig = rig().await;
    for n in 0..1000 {
        rig.stalwart.add_alias(&format!("x{n}.abcdefgh"), false);
    }

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn enabling_past_four_hundred_enabled_aliases_is_too_many() {
    let rig = rig().await;
    rig.stalwart.add_alias("off.abcdefgh", false);
    for n in 0..400 {
        rig.stalwart.add_alias(&format!("x{n}.abcdefgh"), true);
    }

    let response = rig.enable("off.abcdefgh").await;

    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(rig.enabled("off.abcdefgh"), vec![false]);
}

#[tokio::test]
async fn an_alias_a_wipe_removed_is_listed_as_missing() {
    let rig = rig().await;
    let name = rig.created("shop").await;
    rig.stalwart.wipe_aliases();

    let list = rig.list().await;

    assert_eq!(list.len(), 1);
    assert_eq!(list[0]["name"], name.as_str());
    assert_eq!(list[0]["missing"], true);
    assert_eq!(list[0]["enabled"], false);
}

#[tokio::test]
async fn no_alias_is_written_while_postmaster_and_abuse_are_out_of_place() {
    let rig = rig().await;
    rig.stalwart.remove_alias(0);

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
    assert_eq!(rig.stalwart.aliases().len(), 1);
}

#[tokio::test]
async fn without_aliases_configured_every_route_says_so() {
    let dir = tempfile::tempdir().unwrap();
    let url = common::serve(common::config(
        &dir.path().join("sentinel.json"),
        &dir.path().join("no-update"),
    ))
    .await;

    let response = reqwest::get(format!("{url}/corvid/aliases")).await.unwrap();

    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["error"], "aliases_off");
}

#[tokio::test]
async fn no_alias_is_written_over_keys_with_a_gap() {
    let rig = rig().await;
    rig.stalwart.add_alias("shop.abcdefgh", true);
    rig.stalwart.gap_alias(2);

    let response = rig.create("other").await;

    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
    assert_eq!(rig.stalwart.aliases().len(), 3);
}

#[tokio::test]
async fn a_created_alias_keeps_its_label_and_creation_time_across_a_restart() {
    let mut rig = rig().await;
    let name = rig.created("Shop").await;
    let before = rig.list().await;

    rig.restart().await;

    let after = rig.list().await;
    assert_eq!(after[0]["name"], name.as_str());
    assert_eq!(after[0]["label"], "shop");
    assert_eq!(after[0]["createdAt"], before[0]["createdAt"]);
    assert!(after[0]["createdAt"].is_string());
}

#[tokio::test]
async fn a_refused_name_is_minted_again_and_leaves_one_alias_and_one_record() {
    let rig = rig().await;
    rig.stalwart.refuse_next_set();

    let name = rig.created("shop").await;

    let minted_names: Vec<String> = rig.stalwart.aliases()[2..]
        .iter()
        .map(|(name, _)| name.clone())
        .collect();
    assert_eq!(minted_names, vec![name.clone()]);
    assert_eq!(
        rig.ledger().aliases.keys().cloned().collect::<Vec<_>>(),
        vec![name]
    );
}

#[tokio::test]
async fn a_patch_that_lands_and_then_times_out_is_created() {
    let rig = rig_timing_out().await;
    rig.stalwart.hang_after_next_set();

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::CREATED);
    let body: Value = response.json().await.unwrap();
    assert_eq!(
        rig.stalwart.aliases()[2],
        (body["name"].as_str().unwrap().to_owned(), true)
    );
    assert_eq!(rig.ledger().aliases.len(), 1);
}

#[tokio::test]
async fn a_patch_that_times_out_without_landing_leaves_no_record() {
    let rig = rig_timing_out().await;
    rig.stalwart.hang_before_next_set();

    let response = rig.create("shop").await;

    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
    assert_eq!(rig.stalwart.aliases().len(), 2);
    assert!(rig.ledger().aliases.is_empty());
    assert!(rig.list().await.is_empty());
}

#[tokio::test]
async fn an_unknown_name_costs_no_admin_token() {
    let rig = rig().await;
    let name = rig.created("shop").await;
    let master = rig.master();

    let missing = rig.switch("gone.abcdefgh", "disable", Some(&master)).await;
    let disabled = rig.switch(&name, "disable", Some(&master)).await;

    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
    assert_eq!(disabled.status(), StatusCode::OK);
}

#[tokio::test]
async fn a_switch_whose_read_back_finds_no_entry_is_a_bad_gateway() {
    let rig = rig().await;
    let name = rig.created("shop").await;
    rig.stalwart.wipe_after_next_set();

    let response = rig.disable(&name).await;

    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
}
