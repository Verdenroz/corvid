#[path = "../common/mod.rs"]
mod common;
mod idp;
mod push;
mod stalwart;

use std::path::PathBuf;
use std::sync::Arc;

use corvid_sentinel::auth::soft::signing_key;
use corvid_sentinel::auth::{Audience, Issuer};
use corvid_sentinel::devices::{self, Approved, Devices, Request, Stage};
use idp::{ISSUER, Idp, claims, header, now, sign, token};
use serde_json::{Value, json};
use stalwart::{KEYS_ACCOUNT, MAIL_ACCOUNT, Stalwart, TOKEN};

const ADMIN: &str = "mailuser-admin";
const SECRET: &[u8] = b"secret";
const CONTACT: &str = "operator@elsewhere.test";

struct Rig {
    url: String,
    idp: Idp,
    stalwart: Stalwart,
    state: PathBuf,
    _dir: tempfile::TempDir,
}

async fn rig() -> Rig {
    rig_with(Devices::default()).await
}

async fn rig_with(devices: Devices) -> Rig {
    rig_full(devices, None).await
}

async fn rig_full(devices: Devices, fcm_origin: Option<&str>) -> Rig {
    rig_all(devices, fcm_origin, None).await
}

async fn rig_all(devices: Devices, fcm_origin: Option<&str>, local: Option<Arc<Issuer>>) -> Rig {
    let dir = tempfile::tempdir().unwrap();
    let state = dir.path().join("devices.json");
    let idp = Idp::start(ADMIN).await;
    let stalwart = Stalwart::start().await;
    let mut config = common::config(
        &dir.path().join("sentinel.json"),
        &dir.path().join("no-update"),
    );
    config.devices = Some(devices::Config {
        secret: SECRET.to_vec(),
        state_path: state.clone(),
        devices,
        issuer: ISSUER.to_owned(),
        idp_url: idp.url.clone(),
        admin_user: ADMIN.to_owned(),
        keys_address: "corvid-keys@keys.rig.test".to_owned(),
        mail_address: "mailuser@rig.test".to_owned(),
        stalwart_url: stalwart.url.clone(),
        stalwart_token: TOKEN.to_owned(),
        push: fcm_origin.map(|origin| devices::PushConfig::open(&state, CONTACT, origin).unwrap()),
        local,
    });
    Rig {
        url: common::serve(config).await,
        idp,
        stalwart,
        state,
        _dir: dir,
    }
}

impl Rig {
    async fn open(&self, name: &str) -> reqwest::Response {
        reqwest::Client::new()
            .post(format!("{}/corvid/devices/requests", self.url))
            .json(&json!({"name": name}))
            .send()
            .await
            .unwrap()
    }

    async fn request(&self, name: &str) -> (String, String) {
        let body: Value = self.open(name).await.json().await.unwrap();
        (
            body["id"].as_str().unwrap().to_owned(),
            body["code"].as_str().unwrap().to_owned(),
        )
    }

    async fn status(&self, id: &str) -> String {
        let body: Value = reqwest::get(format!("{}/corvid/devices/requests/{id}", self.url))
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        body["status"].as_str().unwrap().to_owned()
    }

    async fn approve(&self, id: &str, code: &str, master: Option<&str>) -> reqwest::Response {
        let mut request = reqwest::Client::new()
            .post(format!("{}/corvid/devices/requests/{id}/approve", self.url))
            .json(&json!({"code": code}));
        if let Some(master) = master {
            request = request.header("X-Corvid-Master", master);
        }
        request.send().await.unwrap()
    }

    async fn record(&self, name: &str) -> reqwest::Response {
        reqwest::get(format!("{}/corvid/devices/{name}", self.url))
            .await
            .unwrap()
    }

    async fn revoke(&self, name: &str) -> reqwest::Response {
        self.revoke_signed(
            name,
            Some(common::signature(
                SECRET,
                format!("revoke {name}").as_bytes(),
            )),
        )
        .await
    }

    async fn revoke_signed(&self, name: &str, signature: Option<String>) -> reqwest::Response {
        let mut request =
            reqwest::Client::new().delete(format!("{}/internal/devices/{name}", self.url));
        if let Some(signature) = signature {
            request = request.header("X-Signature", signature);
        }
        request.send().await.unwrap()
    }

    async fn approved(&self, name: &str) -> Approved {
        let (id, code) = self.request(name).await;
        let response = self.approve(&id, &code, Some(&token())).await;
        assert_eq!(response.status(), 200);
        response.json().await.unwrap()
    }
}

fn with_header(field: &str, value: Value) -> String {
    let mut header = header();
    header[field] = value;
    sign(&header, &claims())
}

fn expired_request(name: &str, code: &str) -> Devices {
    let mut devices = Devices::default();
    devices.requests.insert(
        "old".to_owned(),
        Request {
            name: name.to_owned(),
            code: code.to_owned(),
            expires_at: now() - 1,
            attempts: 0,
            stage: Stage::Open,
        },
    );
    devices
}

#[tokio::test]
async fn a_host_without_device_config_answers_every_device_route_with_devices_off() {
    let dir = tempfile::tempdir().unwrap();
    let url = common::serve(common::config(
        &dir.path().join("sentinel.json"),
        &dir.path().join("no-update"),
    ))
    .await;
    let client = reqwest::Client::new();

    for request in [
        client
            .post(format!("{url}/corvid/devices/requests"))
            .json(&json!({"name": "phone"})),
        client.get(format!("{url}/corvid/devices/requests")),
        client.get(format!("{url}/corvid/devices/requests/some-id")),
        client
            .post(format!("{url}/corvid/devices/requests/some-id/approve"))
            .json(&json!({"code": "123456"})),
        client.get(format!("{url}/corvid/devices/phone")),
        client.delete(format!("{url}/internal/devices/phone")),
    ] {
        let response = request.send().await.unwrap();
        let route = response.url().path().to_owned();
        assert_eq!(response.status(), 503, "{route}");
        let body: Value = response.json().await.unwrap();
        assert_eq!(body, json!({"error": "devices_off"}), "{route}");
    }
}

#[tokio::test]
async fn a_new_request_answers_with_an_id_a_six_digit_code_and_an_expiry_and_reads_as_pending() {
    let rig = rig().await;

    let response = rig.open("phone").await;

    assert_eq!(response.status(), 201);
    let body: Value = response.json().await.unwrap();
    let code = body["code"].as_str().unwrap();
    assert!(
        code.len() == 6 && code.bytes().all(|byte| byte.is_ascii_digit()),
        "{code}"
    );
    assert!(body["expiresAt"].as_str().unwrap().ends_with('Z'));
    assert_eq!(rig.status(body["id"].as_str().unwrap()).await, "pending");
}

async fn listed(rig: &Rig) -> Vec<Value> {
    let body: Value = reqwest::get(format!("{}/corvid/devices/requests", rig.url))
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    body.as_array().unwrap().clone()
}

fn request_in(name: &str, expires_at: i64, attempts: u8, stage: Stage) -> Request {
    Request {
        name: name.to_owned(),
        code: "123456".to_owned(),
        expires_at,
        attempts,
        stage,
    }
}

#[tokio::test]
async fn the_list_holds_only_open_live_requests_newest_first() {
    let mut devices = Devices::default();
    for (id, request) in [
        ("old", request_in("older", now() + 100, 0, Stage::Open)),
        ("new", request_in("newer", now() + 500, 4, Stage::Open)),
        ("gone", request_in("expired", now() - 1, 0, Stage::Open)),
        ("dead", request_in("dead", now() + 500, 5, Stage::Open)),
        (
            "done",
            request_in("approved", now() + 500, 0, Stage::Approved),
        ),
        (
            "held",
            request_in("claimed", now() + 500, 0, Stage::Claimed),
        ),
    ] {
        devices.requests.insert(id.to_owned(), request);
    }
    let rig = rig_with(devices).await;

    let list = listed(&rig).await;

    let ids: Vec<_> = list
        .iter()
        .map(|entry| entry["id"].as_str().unwrap())
        .collect();
    assert_eq!(ids, ["new", "old"]);
    assert_eq!(list[0]["name"], "newer");
    assert!(list[0]["expiresAt"].as_str().unwrap().ends_with('Z'));
}

#[tokio::test]
async fn the_list_never_carries_a_code_or_attempt_count() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;

    let list = listed(&rig).await;

    assert_eq!(list.len(), 1);
    assert_eq!(list[0]["id"], id.as_str());
    let mut keys: Vec<_> = list[0].as_object().unwrap().keys().cloned().collect();
    keys.sort();
    assert_eq!(keys, ["expiresAt", "id", "name"]);
    assert!(!list[0].to_string().contains(&code));
}

#[tokio::test]
async fn a_request_leaves_the_list_once_approved() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;
    assert_eq!(listed(&rig).await.len(), 1);

    rig.approve(&id, &code, Some(&token())).await;

    assert!(listed(&rig).await.is_empty());
}

#[tokio::test]
async fn names_outside_the_device_pattern_are_refused() {
    let rig = rig().await;

    for name in [
        "",
        "Phone",
        "has space",
        "under_score",
        &"a".repeat(33),
        "requests",
    ] {
        assert_eq!(rig.open(name).await.status(), 400, "{name}");
    }
    assert_eq!(rig.open(&"a".repeat(32)).await.status(), 201);
}

#[tokio::test]
async fn more_open_requests_than_the_cap_are_refused() {
    let rig = rig().await;

    for _ in 0..16 {
        assert_eq!(rig.open("phone").await.status(), 201);
    }

    assert_eq!(rig.open("phone").await.status(), 429);
}

#[tokio::test]
async fn an_unknown_or_expired_id_reads_as_expired_and_cannot_be_approved() {
    let rig = rig_with(expired_request("phone", "123456")).await;

    assert_eq!(rig.status("old").await, "expired");
    assert_eq!(rig.status("never-issued").await, "expired");
    assert_eq!(
        rig.approve("old", "123456", Some(&token())).await.status(),
        410
    );
    assert!(rig.stalwart.nodes().is_empty());
}

#[tokio::test]
async fn the_admin_approving_with_the_right_code_creates_a_key_the_device_then_finds_by_name() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;

    let response = rig.approve(&id, &code, Some(&token())).await;

    assert_eq!(response.status(), 200);
    assert_eq!(rig.status(&id).await, "approved");
    let node = rig.stalwart.node("phone.key").unwrap();
    let key = rig.stalwart.content(&node);
    assert_eq!(key.len(), 33);
    assert_eq!(key[0], 1);
    let record: Approved = rig.record("phone").await.json().await.unwrap();
    assert_eq!(
        record,
        Approved {
            account_id: KEYS_ACCOUNT.to_owned(),
            node_id: node.id.clone(),
            blob_id: node.blob.clone().unwrap(),
        }
    );
}

#[tokio::test]
async fn the_keys_folder_is_created_shared_read_only_with_the_mailbox() {
    let rig = rig().await;

    rig.approved("phone").await;

    let folder = rig.stalwart.node("corvid-devices").unwrap();
    assert_eq!(folder.parent, None);
    assert_eq!(folder.share, json!({MAIL_ACCOUNT: {"mayRead": true}}));
    assert_eq!(
        rig.stalwart.node("phone.key").unwrap().parent,
        Some(folder.id)
    );
}

#[tokio::test]
async fn a_second_approval_reuses_the_folder() {
    let rig = rig().await;
    rig.approved("phone").await;
    let folder = rig.stalwart.node("corvid-devices").unwrap();

    rig.approved("tablet").await;

    let folders = rig.stalwart.nodes_named("corvid-devices");
    assert_eq!(folders, [folder]);
}

#[tokio::test]
async fn a_second_approval_costs_one_jmap_request() {
    let rig = rig().await;
    rig.approved("phone").await;
    let before = rig.stalwart.calls();

    rig.approved("tablet").await;

    assert_eq!(rig.stalwart.calls() - before, 1);
}

#[tokio::test]
async fn a_folder_removed_since_the_last_approval_is_made_again() {
    let rig = rig().await;
    rig.approved("phone").await;
    rig.stalwart.remove("corvid-devices");

    let approved = rig.approved("tablet").await;

    let folder = rig.stalwart.node("corvid-devices").unwrap();
    let key = rig.stalwart.node("tablet.key").unwrap();
    assert_eq!(key.parent, Some(folder.id));
    assert_eq!(key.id, approved.node_id);
}

#[tokio::test]
async fn an_existing_folder_that_lost_its_share_is_shared_again() {
    let rig = rig().await;
    let folder = rig.stalwart.plant_folder("corvid-devices");

    rig.approved("phone").await;

    let folders = rig.stalwart.nodes_named("corvid-devices");
    assert_eq!(folders.len(), 1);
    assert_eq!(folders[0].id, folder.id);
    assert_eq!(folders[0].share, json!({MAIL_ACCOUNT: {"mayRead": true}}));
}

#[tokio::test]
async fn a_request_is_single_use() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;
    rig.approve(&id, &code, Some(&token())).await;
    let before = rig.stalwart.nodes();

    let again = rig.approve(&id, &code, Some(&token())).await;

    assert_eq!(again.status(), 410);
    assert_eq!(rig.stalwart.nodes(), before);
}

#[tokio::test]
async fn a_wrong_code_is_refused_and_five_of_them_spend_the_request() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;
    let wrong = if code == "000000" { "000001" } else { "000000" };

    for _ in 0..5 {
        assert_eq!(rig.approve(&id, wrong, Some(&token())).await.status(), 403);
    }

    assert_eq!(rig.status(&id).await, "expired");
    assert_eq!(rig.approve(&id, &code, Some(&token())).await.status(), 410);
    assert!(rig.stalwart.nodes().is_empty());
    assert_eq!(rig.record("phone").await.status(), 404);
}

#[tokio::test]
async fn an_approval_with_no_master_token_is_refused_before_anything_is_written() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;

    assert_eq!(rig.approve(&id, &code, None).await.status(), 401);

    assert_eq!(rig.status(&id).await, "pending");
    assert_eq!(rig.stalwart.calls(), 0);
}

#[tokio::test]
async fn a_token_that_does_not_prove_a_fresh_admin_sign_in_is_refused() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;
    let with = |change: &dyn Fn(&mut Value)| {
        let mut claims = claims();
        change(&mut claims);
        sign(&header(), &claims)
    };
    let tampered = {
        let good = token();
        let (head, rest) = good.split_once('.').unwrap();
        let (_, signature) = rest.split_once('.').unwrap();
        let mut forged = claims();
        forged["aud"] = json!(["corvid-admin"]);
        forged["exp"] = json!(now() + 86400);
        format!("{head}.{}.{signature}", base64_url(&forged.to_string()))
    };
    let refused = [
        (
            "the daily client",
            with(&|c| c["aud"] = json!(["corvid", ISSUER])),
        ),
        (
            "another issuer",
            with(&|c| c["iss"] = json!("https://elsewhere.test")),
        ),
        ("an expired token", with(&|c| c["exp"] = json!(now() - 1))),
        (
            "a sign-in ten minutes old",
            with(&|c| c["iat"] = json!(now() - 600)),
        ),
        (
            "a sign-in from the future",
            with(&|c| c["iat"] = json!(now() + 600)),
        ),
        (
            "no time of sign-in",
            with(&|c| {
                c.as_object_mut().unwrap().remove("iat");
            }),
        ),
        ("an id token", with_header("typ", json!("JWT"))),
        ("an unsigned token", with_header("alg", json!("none"))),
        ("an HMAC token", with_header("alg", json!("HS256"))),
        ("an unknown key", with_header("kid", json!("k2"))),
        ("altered claims", tampered),
        ("not a token", "garbage".to_owned()),
    ];

    for (what, master) in refused {
        let status = rig.approve(&id, &code, Some(&master)).await.status();
        assert_eq!(status, 401, "{what}");
    }

    assert_eq!(rig.status(&id).await, "pending");
    assert_eq!(rig.stalwart.calls(), 0);
}

#[tokio::test]
async fn a_token_for_a_user_other_than_the_admin_is_refused() {
    let rig = rig().await;
    rig.idp.sign_in_as("mailuser");
    let (id, code) = rig.request("phone").await;

    let response = rig.approve(&id, &code, Some(&token())).await;

    assert_eq!(response.status(), 403);
    assert_eq!(rig.status(&id).await, "pending");
    assert_eq!(rig.stalwart.calls(), 0);
}

#[tokio::test]
async fn an_unapproved_name_has_no_record() {
    let rig = rig().await;
    rig.request("phone").await;

    assert_eq!(rig.record("phone").await.status(), 404);
}

#[tokio::test]
async fn a_file_planted_beside_the_key_changes_nothing_the_device_reads() {
    let rig = rig().await;
    let approved = rig.approved("phone").await;
    let folder = rig.stalwart.node("corvid-devices").unwrap();

    let planted = rig.stalwart.plant("phone.key", &folder.id, b"attacker");
    rig.stalwart.plant("laptop.key", &folder.id, b"attacker");

    let record: Approved = rig.record("phone").await.json().await.unwrap();
    assert_eq!(record, approved);
    assert_ne!(record.node_id, planted.id);
    assert_eq!(rig.record("laptop").await.status(), 404);
    let original = rig
        .stalwart
        .nodes()
        .into_iter()
        .find(|node| node.id == approved.node_id)
        .unwrap();
    assert_eq!(rig.stalwart.content(&original).len(), 33);
}

#[tokio::test]
async fn approving_a_name_again_replaces_its_key() {
    let rig = rig().await;
    let first = rig.approved("phone").await;

    let second = rig.approved("phone").await;

    assert_ne!(first.node_id, second.node_id);
    assert_eq!(rig.stalwart.nodes_named("phone.key").len(), 1);
    let record: Approved = rig.record("phone").await.json().await.unwrap();
    assert_eq!(record, second);
}

#[tokio::test]
async fn a_key_that_cannot_be_created_leaves_no_record_and_spends_the_request() {
    let rig = rig().await;
    let (id, code) = rig.request("phone").await;
    rig.stalwart.refuse_creates();

    let response = rig.approve(&id, &code, Some(&token())).await;

    assert_eq!(response.status(), 502);
    assert_eq!(rig.status(&id).await, "expired");
    assert_eq!(rig.record("phone").await.status(), 404);
}

#[tokio::test]
async fn approved_devices_survive_a_restart() {
    let rig = rig().await;
    let approved = rig.approved("phone").await;

    let saved = devices::open(&rig.state).unwrap();
    let restarted = rig_with(saved).await;

    let record: Approved = restarted.record("phone").await.json().await.unwrap();
    assert_eq!(record, approved);
}

#[tokio::test]
async fn revoking_removes_the_key_file_and_the_record() {
    let rig = rig().await;
    rig.approved("phone").await;
    rig.approved("tablet").await;

    assert_eq!(rig.revoke("phone").await.status(), 204);

    assert!(rig.stalwart.node("phone.key").is_none());
    assert!(rig.stalwart.node("tablet.key").is_some());
    assert_eq!(rig.record("phone").await.status(), 404);
    assert_eq!(rig.record("tablet").await.status(), 200);
    assert_eq!(rig.revoke("phone").await.status(), 404);
}

#[tokio::test]
async fn revoking_without_the_webhook_secret_is_refused_and_removes_nothing() {
    let rig = rig().await;
    rig.approved("phone").await;
    let wrong = common::signature(b"another secret", b"revoke phone");
    let for_another_device = common::signature(SECRET, b"revoke tablet");

    for signature in [None, Some(wrong), Some(for_another_device)] {
        assert_eq!(rig.revoke_signed("phone", signature).await.status(), 401);
    }

    assert!(rig.stalwart.node("phone.key").is_some());
    assert_eq!(rig.record("phone").await.status(), 200);
}

fn base64_url(text: &str) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(text)
}

const AUTH_URL: &str = "http://127.0.0.1:11333/internal/issuer";

fn issuer() -> Arc<Issuer> {
    Arc::new(Issuer::new(signing_key(), AUTH_URL.to_owned()))
}

#[tokio::test]
async fn a_master_token_the_sentinel_issued_approves_a_device() {
    let issuer = issuer();
    let rig = rig_all(Devices::default(), None, Some(issuer.clone())).await;
    let (id, code) = rig.request("phone").await;
    let token = issuer.mint(Audience::Master, "handle", ADMIN, now());

    let response = rig.approve(&id, &code, Some(&token)).await;

    assert_eq!(response.status(), 200);
    assert_eq!(rig.status(&id).await, "approved");
}

#[tokio::test]
async fn a_sentinel_master_token_approves_one_request_and_no_second() {
    let issuer = issuer();
    let rig = rig_all(Devices::default(), None, Some(issuer.clone())).await;
    let token = issuer.mint(Audience::Master, "handle", ADMIN, now());
    let (first, first_code) = rig.request("phone").await;
    let (second, second_code) = rig.request("tablet").await;
    assert_eq!(
        rig.approve(&first, &first_code, Some(&token))
            .await
            .status(),
        200
    );

    assert_eq!(
        rig.approve(&second, &second_code, Some(&token))
            .await
            .status(),
        401
    );

    assert_eq!(rig.status(&second).await, "pending");
}

#[tokio::test]
async fn a_sentinel_daily_token_cannot_approve_a_device() {
    let issuer = issuer();
    let rig = rig_all(Devices::default(), None, Some(issuer.clone())).await;
    let (id, code) = rig.request("phone").await;
    let token = issuer.mint(Audience::Daily, "handle", ADMIN, now());

    assert_eq!(rig.approve(&id, &code, Some(&token)).await.status(), 401);
}

#[tokio::test]
async fn a_sentinel_master_token_for_another_user_is_refused() {
    let issuer = issuer();
    let rig = rig_all(Devices::default(), None, Some(issuer.clone())).await;
    let (id, code) = rig.request("phone").await;
    let token = issuer.mint(Audience::Master, "handle", "someone-else", now());

    assert_eq!(rig.approve(&id, &code, Some(&token)).await.status(), 403);
}

#[tokio::test]
async fn a_pocket_id_token_still_approves_while_the_sentinel_issues_its_own() {
    let rig = rig_all(Devices::default(), None, Some(issuer())).await;
    let (id, code) = rig.request("phone").await;

    assert_eq!(rig.approve(&id, &code, Some(&token())).await.status(), 200);
}

#[tokio::test]
async fn a_sentinel_token_from_another_key_is_refused() {
    let rig = rig_all(Devices::default(), None, Some(issuer())).await;
    let (id, code) = rig.request("phone").await;
    let token = issuer().mint(Audience::Master, "handle", ADMIN, now());

    assert_eq!(rig.approve(&id, &code, Some(&token)).await.status(), 401);
}
