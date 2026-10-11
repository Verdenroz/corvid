use std::path::Path;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use corvid_sentinel::auth::soft::{BACKUP_ELIGIBLE, Soft, signing_key};
use corvid_sentinel::auth::{self, Audience, Auth, Family, Issuer, Role};
use hmac::{Hmac, KeyInit, Mac};
use reqwest::Response;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::common;

const SECRET: &[u8] = b"secret";
const RP_ID: &str = "rig.test";
const ORIGIN: &str = "android:apk-key-hash:rig";
const DAILY: &str = "mailuser";
const MASTER: &str = "mailuser-admin";
const ISSUER: &str = "http://127.0.0.1:11333/corvid/auth";

struct Rig {
    url: String,
    issuer: Arc<Issuer>,
    dir: tempfile::TempDir,
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
}

fn encode(bytes: impl AsRef<[u8]>) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

fn decode(text: &str) -> Vec<u8> {
    URL_SAFE_NO_PAD.decode(text).unwrap()
}

fn signature(message: &str) -> String {
    let mut mac = <Hmac<Sha256>>::new_from_slice(SECRET).unwrap();
    mac.update(message.as_bytes());
    STANDARD.encode(mac.finalize().into_bytes())
}

fn code_hash(code: &str) -> String {
    hex::encode(Sha256::digest(code))
}

async fn start(dir: &Path, auth: Auth, issuer: Arc<Issuer>) -> String {
    let mut config = common::config(&dir.join("sentinel.json"), &dir.join("no-update"));
    config.auth = Some(auth::Config {
        secret: SECRET.to_vec(),
        state_path: dir.join("auth.json"),
        auth,
        issuer,
        rp_id: RP_ID.to_owned(),
        origins: vec![ORIGIN.to_owned()],
        daily_user: DAILY.to_owned(),
        master_user: MASTER.to_owned(),
    });
    common::serve(config).await
}

async fn rig() -> Rig {
    rig_with(Auth::default()).await
}

async fn rig_with(auth: Auth) -> Rig {
    let dir = tempfile::tempdir().unwrap();
    let issuer = Arc::new(Issuer::new(signing_key(), ISSUER.to_owned()));
    let url = start(dir.path(), auth, issuer.clone()).await;
    Rig { url, issuer, dir }
}

impl Rig {
    async fn post(&self, path: &str, body: Value) -> Response {
        reqwest::Client::new()
            .post(format!("{}{path}", self.url))
            .json(&body)
            .send()
            .await
            .unwrap()
    }

    async fn post_from(&self, path: &str, body: Value, address: &str) -> Response {
        reqwest::Client::new()
            .post(format!("{}{path}", self.url))
            .header("X-Forwarded-For", address)
            .json(&body)
            .send()
            .await
            .unwrap()
    }

    async fn get(&self, path: &str) -> Response {
        reqwest::get(format!("{}{path}", self.url)).await.unwrap()
    }

    async fn host_code(&self, role: &str) -> String {
        let response = reqwest::Client::new()
            .post(format!("{}/internal/auth/codes/{role}", self.url))
            .header("X-Signature", signature(&format!("code {role}")))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 200);
        let body: Value = response.json().await.unwrap();
        body["code"].as_str().unwrap().to_owned()
    }

    async fn enroll(&self, soft: &mut Soft, code: &str) -> Response {
        let options = self
            .post("/corvid/auth/register/options", json!({"code": code}))
            .await;
        if !options.status().is_success() {
            return options;
        }
        let options: Value = options.json().await.unwrap();
        let challenge = decode(options["challenge"].as_str().unwrap());
        let created = soft.create(&challenge);
        self.post(
            "/corvid/auth/register/finish",
            json!({"id": encode(&soft.credential_id), "response": {
                "clientDataJSON": encode(&created.client_data_json),
                "attestationObject": encode(&created.attestation_object),
            }}),
        )
        .await
    }

    async fn enrolled(&self, role: &str) -> Soft {
        let mut soft = Soft::new(RP_ID, ORIGIN);
        let code = self.host_code(role).await;
        let response = self.enroll(&mut soft, &code).await;
        assert_eq!(response.status(), 200);
        soft
    }

    async fn login(&self, soft: &mut Soft, role: &str) -> Response {
        let options = self
            .post("/corvid/auth/login/options", json!({"role": role}))
            .await;
        if !options.status().is_success() {
            return options;
        }
        let options: Value = options.json().await.unwrap();
        let challenge = decode(options["challenge"].as_str().unwrap());
        self.finish_login(soft, &challenge).await
    }

    async fn finish_login(&self, soft: &mut Soft, challenge: &[u8]) -> Response {
        let asserted = soft.get(challenge);
        self.post(
            "/corvid/auth/login/finish",
            json!({"id": encode(&soft.credential_id), "response": {
                "clientDataJSON": encode(&asserted.client_data_json),
                "authenticatorData": encode(&asserted.authenticator_data),
                "signature": encode(&asserted.signature),
            }}),
        )
        .await
    }

    async fn signed_in(&self, soft: &mut Soft, role: &str) -> Value {
        let response = self.login(soft, role).await;
        assert_eq!(response.status(), 200);
        response.json().await.unwrap()
    }

    async fn master_token(&self, soft: &mut Soft) -> String {
        self.signed_in(soft, "master").await["access_token"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    async fn refresh(&self, token: &str) -> Response {
        self.post("/corvid/auth/token", json!({"refresh_token": token}))
            .await
    }

    async fn with_master(
        &self,
        method: reqwest::Method,
        path: &str,
        token: &str,
        body: Value,
    ) -> Response {
        reqwest::Client::new()
            .request(method, format!("{}{path}", self.url))
            .header("X-Corvid-Master", token)
            .json(&body)
            .send()
            .await
            .unwrap()
    }

    async fn notices(&self) -> Vec<String> {
        let body: Value = self.get("/corvid/notices").await.json().await.unwrap();
        body["notices"]
            .as_array()
            .unwrap()
            .iter()
            .map(|notice| notice["title"].as_str().unwrap().to_owned())
            .collect()
    }
}

fn with_code(role: Role, code: &str, expires_at: i64) -> Auth {
    let mut auth = Auth::default();
    auth.codes.insert(
        role,
        auth::Code {
            hash: code_hash(code),
            expires_at,
        },
    );
    auth
}

#[tokio::test]
async fn a_host_code_enrolls_a_daily_passkey_that_then_signs_in_with_both_tokens() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;

    let tokens = rig.signed_in(&mut soft, "daily").await;

    assert_eq!(tokens["token_type"], "Bearer");
    assert_eq!(tokens["expires_in"], 900);
    assert_eq!(tokens["role"], "daily");
    assert!(tokens["refresh_token"].is_string());
    let claims = rig
        .issuer
        .verify(
            tokens["access_token"].as_str().unwrap(),
            Audience::Daily,
            now(),
        )
        .unwrap();
    assert_eq!(claims.preferred_username, DAILY);
}

#[tokio::test]
async fn a_master_passkey_signs_in_with_an_admin_token_and_no_refresh_token() {
    let rig = rig().await;
    let mut soft = rig.enrolled("master").await;

    let tokens = rig.signed_in(&mut soft, "master").await;

    assert_eq!(tokens["expires_in"], 300);
    assert!(tokens.get("refresh_token").is_none());
    let claims = rig
        .issuer
        .verify(
            tokens["access_token"].as_str().unwrap(),
            Audience::Master,
            now(),
        )
        .unwrap();
    assert_eq!(claims.preferred_username, MASTER);
}

#[tokio::test]
async fn an_enrollment_code_works_once() {
    let rig = rig().await;
    let code = rig.host_code("daily").await;
    let mut first = Soft::new(RP_ID, ORIGIN);
    assert_eq!(rig.enroll(&mut first, &code).await.status(), 200);

    let mut second = Soft::new(RP_ID, ORIGIN);

    assert_eq!(rig.enroll(&mut second, &code).await.status(), 401);
}

#[tokio::test]
async fn the_code_may_be_typed_in_lower_case_without_dashes() {
    let rig = rig().await;
    let code = rig.host_code("daily").await;
    let typed = code.replace('-', "").to_lowercase();

    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), &typed)
            .await
            .status(),
        200
    );
}

#[tokio::test]
async fn a_code_is_four_groups_of_four_base32_characters() {
    let rig = rig().await;

    let code = rig.host_code("daily").await;

    let groups: Vec<_> = code.split('-').collect();
    assert_eq!(groups.len(), 4);
    for group in groups {
        assert_eq!(group.len(), 4);
        assert!(
            group
                .bytes()
                .all(|b| matches!(b, b'A'..=b'Z' | b'2'..=b'7'))
        );
    }
}

#[tokio::test]
async fn minting_a_new_code_replaces_the_previous_one_for_that_role() {
    let rig = rig().await;
    let old = rig.host_code("daily").await;
    let new = rig.host_code("daily").await;

    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), &old)
            .await
            .status(),
        401
    );
    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), &new)
            .await
            .status(),
        200
    );
}

#[tokio::test]
async fn a_code_past_its_day_is_refused() {
    let rig = rig_with(with_code(Role::Daily, "AAAABBBBCCCCDDDD", now() - 1)).await;

    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), "AAAA-BBBB-CCCC-DDDD")
            .await
            .status(),
        401
    );
}

#[tokio::test]
async fn enrolling_with_a_daily_code_does_not_open_a_master_sign_in() {
    let rig = rig().await;
    let code = rig.host_code("daily").await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    assert_eq!(rig.enroll(&mut soft, &code).await.status(), 200);

    let response = rig.login(&mut soft, "master").await;

    assert_eq!(response.status(), 404);
}

#[tokio::test]
async fn the_sixth_wrong_code_from_one_address_is_rate_limited_and_a_right_one_waits() {
    let rig = rig().await;
    let code = rig.host_code("daily").await;
    for _ in 0..5 {
        let response = rig
            .post_from(
                "/corvid/auth/register/options",
                json!({"code": "WRNG-WRNG-WRNG-WRNG"}),
                "203.0.113.9",
            )
            .await;
        assert_eq!(response.status(), 401);
    }

    let response = rig
        .post_from(
            "/corvid/auth/register/options",
            json!({"code": code}),
            "203.0.113.9",
        )
        .await;

    assert_eq!(response.status(), 429);
    let elsewhere = rig
        .post_from(
            "/corvid/auth/register/options",
            json!({"code": code}),
            "203.0.113.10",
        )
        .await;
    assert_eq!(elsewhere.status(), 200);
}

#[tokio::test]
async fn an_address_a_client_put_in_front_of_the_proxys_does_not_dodge_the_limit() {
    let rig = rig().await;
    for n in 0..5 {
        let response = rig
            .post_from(
                "/corvid/auth/register/options",
                json!({"code": "WRNG-WRNG-WRNG-WRNG"}),
                &format!("10.0.0.{n}, 203.0.113.9"),
            )
            .await;
        assert_eq!(response.status(), 401);
    }

    let response = rig
        .post_from(
            "/corvid/auth/register/options",
            json!({"code": "WRNG-WRNG-WRNG-WRNG"}),
            "10.0.0.99, 203.0.113.9",
        )
        .await;

    assert_eq!(response.status(), 429);
}

#[tokio::test]
async fn failed_redemptions_from_many_addresses_stop_everyone_at_the_global_limit() {
    let rig = rig().await;
    for n in 0..20 {
        rig.post_from(
            "/corvid/auth/register/options",
            json!({"code": "WRNG-WRNG-WRNG-WRNG"}),
            &format!("203.0.113.{n}"),
        )
        .await;
    }

    let response = rig
        .post_from(
            "/corvid/auth/register/options",
            json!({"code": "WRNG-WRNG-WRNG-WRNG"}),
            "198.51.100.1",
        )
        .await;

    assert_eq!(response.status(), 429);
}

#[tokio::test]
async fn a_master_passkey_that_can_be_backed_up_is_refused_at_enrollment() {
    let rig = rig().await;
    let code = rig.host_code("master").await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    soft.flags |= BACKUP_ELIGIBLE;

    assert_eq!(rig.enroll(&mut soft, &code).await.status(), 401);
}

#[tokio::test]
async fn a_daily_passkey_that_can_be_backed_up_is_accepted() {
    let rig = rig().await;
    let code = rig.host_code("daily").await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    soft.flags |= BACKUP_ELIGIBLE;

    assert_eq!(rig.enroll(&mut soft, &code).await.status(), 200);
}

#[tokio::test]
async fn signing_in_before_any_passkey_is_enrolled_answers_not_found() {
    let rig = rig().await;

    let response = rig
        .post("/corvid/auth/login/options", json!({"role": "daily"}))
        .await;

    assert_eq!(response.status(), 404);
}

#[tokio::test]
async fn a_challenge_answers_once() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;
    let options: Value = rig
        .post("/corvid/auth/login/options", json!({"role": "daily"}))
        .await
        .json()
        .await
        .unwrap();
    let challenge = decode(options["challenge"].as_str().unwrap());
    assert_eq!(rig.finish_login(&mut soft, &challenge).await.status(), 200);

    assert_eq!(rig.finish_login(&mut soft, &challenge).await.status(), 401);
}

#[tokio::test]
async fn a_challenge_nobody_issued_is_refused() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;

    let response = rig.finish_login(&mut soft, b"made up by the client").await;

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn a_passkey_that_was_never_enrolled_cannot_sign_in() {
    let rig = rig().await;
    rig.enrolled("daily").await;
    let mut stranger = Soft::new(RP_ID, ORIGIN);

    assert_eq!(rig.login(&mut stranger, "daily").await.status(), 401);
}

#[tokio::test]
async fn a_master_passkey_cannot_answer_a_daily_challenge() {
    let rig = rig().await;
    rig.enrolled("daily").await;
    let mut master = rig.enrolled("master").await;

    assert_eq!(rig.login(&mut master, "daily").await.status(), 401);
}

#[tokio::test]
async fn a_sign_count_that_goes_backwards_is_refused_and_leaves_a_notice() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;
    rig.signed_in(&mut soft, "daily").await;
    soft.sign_count = 0;

    assert_eq!(rig.login(&mut soft, "daily").await.status(), 401);

    assert_eq!(rig.notices().await, ["Passkey counter went backwards"]);
}

#[tokio::test]
async fn a_synced_passkey_that_always_reports_zero_keeps_signing_in() {
    let rig = rig().await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    soft.frozen_counter = true;
    let code = rig.host_code("daily").await;
    assert_eq!(rig.enroll(&mut soft, &code).await.status(), 200);

    rig.signed_in(&mut soft, "daily").await;
    rig.signed_in(&mut soft, "daily").await;
}

#[tokio::test]
async fn a_refresh_token_rotates_and_the_new_one_works() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;
    let first = rig.signed_in(&mut soft, "daily").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let response = rig.refresh(&first).await;

    assert_eq!(response.status(), 200);
    let second: Value = response.json().await.unwrap();
    let second = second["refresh_token"].as_str().unwrap().to_owned();
    assert_ne!(first, second);
    assert_eq!(rig.refresh(&second).await.status(), 200);
}

#[tokio::test]
async fn a_refreshed_access_token_verifies_for_the_daily_user() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;
    let refresh = rig.signed_in(&mut soft, "daily").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let body: Value = rig.refresh(&refresh).await.json().await.unwrap();

    let claims = rig
        .issuer
        .verify(
            body["access_token"].as_str().unwrap(),
            Audience::Daily,
            now(),
        )
        .unwrap();
    assert_eq!(claims.preferred_username, DAILY);
}

#[tokio::test]
async fn reusing_an_old_refresh_token_revokes_the_whole_family() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;
    let first = rig.signed_in(&mut soft, "daily").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let second: Value = rig.refresh(&first).await.json().await.unwrap();
    let second = second["refresh_token"].as_str().unwrap().to_owned();

    let reuse = rig.refresh(&first).await;

    assert_eq!(reuse.status(), 400);
    let body: Value = reuse.json().await.unwrap();
    assert_eq!(body["error"], "invalid_grant");
    assert_eq!(rig.refresh(&second).await.status(), 400);
    assert_eq!(rig.notices().await, ["Refresh token reused"]);
}

#[tokio::test]
async fn a_refresh_token_nobody_issued_is_an_invalid_grant() {
    let rig = rig().await;

    for token in ["nonsense", "abc.def"] {
        let response = rig.refresh(token).await;
        assert_eq!(response.status(), 400);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["error"], "invalid_grant");
    }
}

fn aged_family(last_used: i64, created_at: i64) -> Auth {
    let mut auth = Auth::default();
    auth.families.insert(
        "fam".to_owned(),
        Family {
            user: "handle".to_owned(),
            current: code_hash("secret"),
            created_at,
            last_used,
        },
    );
    auth
}

#[tokio::test]
async fn a_refresh_token_idle_for_a_day_is_an_invalid_grant() {
    let rig = rig_with(aged_family(now() - 24 * 3600 - 1, now() - 25 * 3600)).await;

    assert_eq!(rig.refresh("fam.secret").await.status(), 400);
}

#[tokio::test]
async fn a_refresh_token_in_use_for_a_week_is_an_invalid_grant() {
    let rig = rig_with(aged_family(now() - 60, now() - 7 * 24 * 3600 - 1)).await;

    assert_eq!(rig.refresh("fam.secret").await.status(), 400);
}

#[tokio::test]
async fn a_refresh_token_inside_both_limits_is_accepted() {
    let mut auth = aged_family(now() - 60, now() - 3600);
    auth.users
        .insert("handle".to_owned(), auth::User { role: Role::Daily });
    let rig = rig_with(auth).await;

    assert_eq!(rig.refresh("fam.secret").await.status(), 200);
}

#[tokio::test]
async fn enrolled_passkeys_and_refresh_tokens_survive_a_restart() {
    let rig = rig().await;
    let mut soft = rig.enrolled("daily").await;
    let refresh = rig.signed_in(&mut soft, "daily").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let state = auth::open(&rig.dir.path().join("auth.json")).unwrap();
    let restarted = tempfile::tempdir().unwrap();
    let url = start(restarted.path(), state, rig.issuer.clone()).await;

    let response = reqwest::Client::new()
        .post(format!("{url}/corvid/auth/token"))
        .json(&json!({"refresh_token": refresh}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let sign_in = reqwest::Client::new()
        .post(format!("{url}/corvid/auth/login/options"))
        .json(&json!({"role": "daily"}))
        .send()
        .await
        .unwrap();
    assert_eq!(sign_in.status(), 200);
}

#[tokio::test]
async fn the_discovery_document_and_key_set_are_served_for_stalwart() {
    let rig = rig().await;

    let discovery: Value = rig
        .get("/corvid/auth/.well-known/openid-configuration")
        .await
        .json()
        .await
        .unwrap();
    let jwks: Value = rig.get("/corvid/auth/jwks").await.json().await.unwrap();

    assert_eq!(discovery["issuer"], ISSUER);
    assert_eq!(jwks, rig.issuer.jwks());
}

#[tokio::test]
async fn userinfo_names_the_user_of_a_daily_token() {
    let rig = rig().await;
    let token = rig.issuer.mint(Audience::Daily, "handle", DAILY, now());

    let body: Value = reqwest::Client::new()
        .get(format!("{}/corvid/auth/userinfo", rig.url))
        .bearer_auth(token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    assert_eq!(body["preferred_username"], DAILY);
}

#[tokio::test]
async fn userinfo_refuses_a_master_token() {
    let rig = rig().await;
    let token = rig.issuer.mint(Audience::Master, "handle", MASTER, now());

    let response = reqwest::Client::new()
        .get(format!("{}/corvid/auth/userinfo", rig.url))
        .bearer_auth(token)
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn a_master_mints_a_code_for_another_passkey_and_the_token_is_spent() {
    let rig = rig().await;
    let mut master = rig.enrolled("master").await;
    let token = rig.master_token(&mut master).await;

    let response = rig
        .with_master(
            reqwest::Method::POST,
            "/corvid/auth/enroll-codes",
            &token,
            json!({"role": "daily"}),
        )
        .await;

    assert_eq!(response.status(), 200);
    let body: Value = response.json().await.unwrap();
    let code = body["code"].as_str().unwrap();
    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), code)
            .await
            .status(),
        200
    );
    let again = rig
        .with_master(
            reqwest::Method::POST,
            "/corvid/auth/enroll-codes",
            &token,
            json!({"role": "daily"}),
        )
        .await;
    assert_eq!(again.status(), 401);
}

#[tokio::test]
async fn minting_a_code_without_a_master_token_is_refused() {
    let rig = rig().await;

    let response = rig
        .post("/corvid/auth/enroll-codes", json!({"role": "daily"}))
        .await;

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn a_daily_token_cannot_mint_a_code() {
    let rig = rig().await;
    let mut daily = rig.enrolled("daily").await;
    let token = rig.signed_in(&mut daily, "daily").await["access_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let response = rig
        .with_master(
            reqwest::Method::POST,
            "/corvid/auth/enroll-codes",
            &token,
            json!({"role": "daily"}),
        )
        .await;

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn the_passkey_list_names_each_credential_with_its_role() {
    let rig = rig().await;
    let daily = rig.enrolled("daily").await;
    rig.enrolled("master").await;

    let list: Vec<Value> = rig.get("/corvid/auth/passkeys").await.json().await.unwrap();

    assert_eq!(list.len(), 2);
    let roles: Vec<_> = list.iter().map(|p| p["role"].as_str().unwrap()).collect();
    assert!(roles.contains(&"daily") && roles.contains(&"master"));
    assert!(list.iter().any(|p| p["id"] == encode(&daily.credential_id)));
}

#[tokio::test]
async fn a_users_last_passkey_cannot_be_removed() {
    let rig = rig().await;
    let daily = rig.enrolled("daily").await;
    let mut master = rig.enrolled("master").await;
    let token = rig.master_token(&mut master).await;

    let response = rig
        .with_master(
            reqwest::Method::DELETE,
            &format!("/corvid/auth/passkeys/{}", encode(&daily.credential_id)),
            &token,
            json!({}),
        )
        .await;

    assert_eq!(response.status(), 409);
}

#[tokio::test]
async fn a_second_passkey_can_be_removed_by_a_master() {
    let rig = rig().await;
    let first = rig.enrolled("daily").await;
    let second = rig.enrolled("daily").await;
    let mut master = rig.enrolled("master").await;
    let token = rig.master_token(&mut master).await;

    let response = rig
        .with_master(
            reqwest::Method::DELETE,
            &format!("/corvid/auth/passkeys/{}", encode(&second.credential_id)),
            &token,
            json!({}),
        )
        .await;

    assert_eq!(response.status(), 204);
    let list: Vec<Value> = rig.get("/corvid/auth/passkeys").await.json().await.unwrap();
    assert!(list.iter().any(|p| p["id"] == encode(&first.credential_id)));
    assert!(
        !list
            .iter()
            .any(|p| p["id"] == encode(&second.credential_id))
    );
}

#[tokio::test]
async fn removing_a_passkey_needs_a_master_token() {
    let rig = rig().await;
    let daily = rig.enrolled("daily").await;
    rig.enrolled("daily").await;

    let response = reqwest::Client::new()
        .delete(format!(
            "{}/corvid/auth/passkeys/{}",
            rig.url,
            encode(&daily.credential_id)
        ))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn the_host_status_counts_passkeys_by_role() {
    let rig = rig().await;
    rig.enrolled("daily").await;

    let response = reqwest::Client::new()
        .get(format!("{}/internal/auth/status", rig.url))
        .header("X-Signature", signature("status"))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 200);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["daily"], 1);
    assert_eq!(body["master"], 0);
}

#[tokio::test]
async fn the_host_routes_refuse_an_unsigned_or_wrongly_signed_call() {
    let rig = rig().await;
    let client = reqwest::Client::new();

    let unsigned = client
        .get(format!("{}/internal/auth/status", rig.url))
        .send()
        .await
        .unwrap();
    let wrong = client
        .post(format!("{}/internal/auth/codes/daily", rig.url))
        .header("X-Signature", signature("code master"))
        .send()
        .await
        .unwrap();

    assert_eq!(unsigned.status(), 401);
    assert_eq!(wrong.status(), 401);
}

#[tokio::test]
async fn the_host_removes_a_second_passkey_by_id() {
    let rig = rig().await;
    rig.enrolled("daily").await;
    let second = rig.enrolled("daily").await;
    let id = encode(&second.credential_id);

    let response = reqwest::Client::new()
        .delete(format!("{}/internal/auth/passkeys/{id}", rig.url))
        .header("X-Signature", signature(&format!("remove-passkey {id}")))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 204);
}

#[tokio::test]
async fn a_host_without_auth_config_answers_every_auth_route_with_auth_off() {
    let dir = tempfile::tempdir().unwrap();
    let config = common::config(&dir.path().join("sentinel.json"), &dir.path().join("none"));
    let url = common::serve(config).await;

    for path in ["/corvid/auth/passkeys", "/internal/auth/status"] {
        let response = reqwest::get(format!("{url}{path}")).await.unwrap();
        assert_eq!(response.status(), 503);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["error"], "auth_off");
    }
}
