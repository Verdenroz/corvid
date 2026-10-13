use std::path::Path;
use std::sync::Arc;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use corvid_sentinel::auth::soft::{BACKUP_ELIGIBLE, Soft, signing_key};
use corvid_sentinel::auth::{self, Audience, Auth, Credential, Family, Issuer, legacy};
use corvid_sentinel::now_secs;
use reqwest::Response;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::common;

const SECRET: &[u8] = b"secret";
const RP_ID: &str = "rig.test";
const ORIGIN: &str = "android:apk-key-hash:rig";
const DAILY: &str = "mailuser";
const MASTER: &str = "mailuser-admin";
const ISSUER: &str = "http://127.0.0.1:11333/internal/issuer";

struct Rig {
    url: String,
    issuer: Arc<Issuer>,
    dir: tempfile::TempDir,
}

fn encode(bytes: impl AsRef<[u8]>) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

fn decode(text: &str) -> Vec<u8> {
    URL_SAFE_NO_PAD.decode(text).unwrap()
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

    async fn host_minted(&self, approves: bool) -> Value {
        let message = if approves { "code approver" } else { "code" };
        let response = reqwest::Client::new()
            .post(format!("{}/internal/auth/codes", self.url))
            .header("X-Signature", common::signature(SECRET, message.as_bytes()))
            .json(&json!({"approves": approves}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 200);
        response.json().await.unwrap()
    }

    async fn host_code(&self, approves: bool) -> String {
        self.host_minted(approves).await["code"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    async fn host_status(&self) -> Value {
        reqwest::Client::new()
            .get(format!("{}/internal/auth/status", self.url))
            .header("X-Signature", common::signature(SECRET, b"status"))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap()
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
        self.post(
            "/corvid/auth/register/finish",
            soft.registration_json(&challenge),
        )
        .await
    }

    async fn enrolled_with(&self, approves: bool) -> Soft {
        let mut soft = Soft::new(RP_ID, ORIGIN);
        let code = self.host_code(approves).await;
        let response = self.enroll(&mut soft, &code).await;
        assert_eq!(response.status(), 200);
        soft
    }

    async fn enrolled(&self) -> Soft {
        self.enrolled_with(true).await
    }

    async fn enrolled_plain(&self) -> Soft {
        let soft = self.enrolled_with(false).await;
        let listed = self.passkeys().await;
        let id = encode(&soft.credential_id);
        let entry = listed.iter().find(|p| p["id"] == id).unwrap();
        assert_eq!(entry["approves"], false, "an approver must exist first");
        soft
    }

    async fn allowed(&self, purpose: &str) -> Vec<String> {
        let options: Value = self
            .post("/corvid/auth/login/options", json!({"purpose": purpose}))
            .await
            .json()
            .await
            .unwrap();
        options["allowCredentials"]
            .as_array()
            .unwrap()
            .iter()
            .map(|entry| entry["id"].as_str().unwrap().to_owned())
            .collect()
    }

    async fn passkeys(&self) -> Vec<Value> {
        self.get("/corvid/auth/passkeys")
            .await
            .json()
            .await
            .unwrap()
    }

    async fn login(&self, soft: &mut Soft, purpose: &str) -> Response {
        let options = self
            .post("/corvid/auth/login/options", json!({"purpose": purpose}))
            .await;
        if !options.status().is_success() {
            return options;
        }
        let options: Value = options.json().await.unwrap();
        let challenge = decode(options["challenge"].as_str().unwrap());
        self.finish_login(soft, &challenge).await
    }

    async fn finish_login(&self, soft: &mut Soft, challenge: &[u8]) -> Response {
        self.post("/corvid/auth/login/finish", soft.assertion_json(challenge))
            .await
    }

    async fn signed_in(&self, soft: &mut Soft, purpose: &str) -> Value {
        let response = self.login(soft, purpose).await;
        assert_eq!(response.status(), 200);
        response.json().await.unwrap()
    }

    async fn master_token(&self, soft: &mut Soft) -> String {
        self.signed_in(soft, "approve").await["access_token"]
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

fn with_code(approves: bool, code: &str, expires_at: i64) -> Auth {
    Auth {
        codes: vec![auth::Code {
            hash: code_hash(code),
            expires_at,
            approves,
        }],
        ..Auth::default()
    }
}

#[tokio::test]
async fn a_host_code_enrolls_a_passkey_that_then_signs_in_with_both_tokens() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;

    let tokens = rig.signed_in(&mut soft, "sign-in").await;

    assert_eq!(tokens["token_type"], "Bearer");
    assert_eq!(tokens["expires_in"], 900);
    assert!(tokens["refresh_token"].is_string());
    let claims = rig
        .issuer
        .verify(
            tokens["access_token"].as_str().unwrap(),
            Audience::Daily,
            now_secs(),
        )
        .unwrap();
    assert_eq!(claims.preferred_username, DAILY);
}

#[tokio::test]
async fn registering_answers_with_the_tokens_of_a_sign_in_and_whether_the_key_approves() {
    let rig = rig().await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    let code = rig.host_code(true).await;

    let response = rig.enroll(&mut soft, &code).await;

    assert_eq!(response.status(), 200);
    let tokens: Value = response.json().await.unwrap();
    assert_eq!(tokens["approves"], true);
    assert_eq!(tokens["expires_in"], 900);
    let claims = rig
        .issuer
        .verify(
            tokens["access_token"].as_str().unwrap(),
            Audience::Daily,
            now_secs(),
        )
        .unwrap();
    assert_eq!(claims.preferred_username, DAILY);
    let refreshed = rig.refresh(tokens["refresh_token"].as_str().unwrap()).await;
    assert_eq!(refreshed.status(), 200);
}

#[tokio::test]
async fn registering_with_a_plain_code_answers_that_the_key_does_not_approve() {
    let rig = rig().await;
    rig.enrolled().await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    let code = rig.host_code(false).await;

    let tokens: Value = rig.enroll(&mut soft, &code).await.json().await.unwrap();

    assert_eq!(tokens["approves"], false);
    assert!(tokens["refresh_token"].is_string());
}

#[tokio::test]
async fn an_approver_signs_in_to_approve_with_an_admin_token_and_no_refresh_token() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;

    let tokens = rig.signed_in(&mut soft, "approve").await;

    assert_eq!(tokens["expires_in"], 300);
    assert!(tokens.get("refresh_token").is_none());
    let claims = rig
        .issuer
        .verify(
            tokens["access_token"].as_str().unwrap(),
            Audience::Master,
            now_secs(),
        )
        .unwrap();
    assert_eq!(claims.preferred_username, MASTER);
}

#[tokio::test]
async fn an_enrollment_code_works_once() {
    let rig = rig().await;
    let code = rig.host_code(false).await;
    let mut first = Soft::new(RP_ID, ORIGIN);
    assert_eq!(rig.enroll(&mut first, &code).await.status(), 200);

    let mut second = Soft::new(RP_ID, ORIGIN);

    assert_eq!(rig.enroll(&mut second, &code).await.status(), 401);
}

#[tokio::test]
async fn the_code_may_be_typed_in_lower_case_without_dashes() {
    let rig = rig().await;
    let code = rig.host_code(false).await;
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

    let code = rig.host_code(false).await;

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
async fn several_codes_are_live_at_once_and_each_is_spent_alone() {
    let rig = rig().await;
    rig.enrolled().await;
    let first = rig.host_code(false).await;
    let second = rig.host_code(false).await;

    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), &second)
            .await
            .status(),
        200
    );

    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), &first)
            .await
            .status(),
        200
    );
    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), &second)
            .await
            .status(),
        401
    );
}

#[tokio::test]
async fn a_code_past_its_day_is_refused() {
    let rig = rig_with(with_code(false, "AAAABBBBCCCCDDDD", now_secs() - 1)).await;

    assert_eq!(
        rig.enroll(&mut Soft::new(RP_ID, ORIGIN), "AAAA-BBBB-CCCC-DDDD")
            .await
            .status(),
        401
    );
}

#[tokio::test]
async fn a_plain_code_enrolls_a_key_that_signs_in_but_cannot_approve() {
    let rig = rig().await;
    rig.enrolled().await;
    let mut plain = rig.enrolled_plain().await;

    assert_eq!(rig.login(&mut plain, "sign-in").await.status(), 200);

    assert_eq!(rig.login(&mut plain, "approve").await.status(), 401);
}

#[tokio::test]
async fn approving_lists_only_the_approvers() {
    let rig = rig().await;
    let approver = rig.enrolled().await;
    let plain = rig.enrolled_plain().await;
    let approving = rig.allowed("approve").await;
    let signing_in = rig.allowed("sign-in").await;

    assert_eq!(approving, [encode(&approver.credential_id)]);
    assert!(signing_in.contains(&encode(&approver.credential_id)));
    assert!(signing_in.contains(&encode(&plain.credential_id)));
}

#[tokio::test]
async fn approving_with_no_approver_enrolled_answers_not_found() {
    let mut auth = aged_family(now_secs() - 60, now_secs() - 3600);
    auth.families.clear();
    let rig = rig_with(auth).await;

    let response = rig
        .post("/corvid/auth/login/options", json!({"purpose": "approve"}))
        .await;

    assert_eq!(response.status(), 404);
}

#[tokio::test]
async fn while_no_approver_is_enrolled_a_plain_code_enrolls_an_approver() {
    let rig = rig().await;

    let minted = rig.host_minted(false).await;

    assert_eq!(minted["approves"], true);
    let mut soft = Soft::new(RP_ID, ORIGIN);
    let tokens: Value = rig
        .enroll(&mut soft, minted["code"].as_str().unwrap())
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(tokens["approves"], true);
}

#[tokio::test]
async fn once_an_approver_exists_a_plain_code_stays_plain() {
    let rig = rig().await;
    rig.enrolled().await;

    let minted = rig.host_minted(false).await;

    assert_eq!(minted["approves"], false);
}

#[tokio::test]
async fn the_sixth_wrong_code_from_one_address_is_rate_limited_and_a_right_one_waits() {
    let rig = rig().await;
    let code = rig.host_code(false).await;
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
async fn a_passkey_that_can_be_backed_up_can_be_an_approver() {
    let rig = rig().await;
    let code = rig.host_code(true).await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    soft.flags |= BACKUP_ELIGIBLE;

    assert_eq!(rig.enroll(&mut soft, &code).await.status(), 200);

    assert_eq!(rig.login(&mut soft, "approve").await.status(), 200);
}

#[tokio::test]
async fn a_passkey_that_can_be_backed_up_is_accepted_as_a_plain_one() {
    let rig = rig().await;
    rig.enrolled().await;
    let code = rig.host_code(false).await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    soft.flags |= BACKUP_ELIGIBLE;

    assert_eq!(rig.enroll(&mut soft, &code).await.status(), 200);
}

#[tokio::test]
async fn signing_in_before_any_passkey_is_enrolled_answers_not_found() {
    let rig = rig().await;

    let response = rig
        .post("/corvid/auth/login/options", json!({"purpose": "sign-in"}))
        .await;

    assert_eq!(response.status(), 404);
}

#[tokio::test]
async fn a_challenge_answers_once() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let options: Value = rig
        .post("/corvid/auth/login/options", json!({"purpose": "sign-in"}))
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
    let mut soft = rig.enrolled().await;

    let response = rig.finish_login(&mut soft, b"made up by the client").await;

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn a_passkey_that_was_never_enrolled_cannot_sign_in() {
    let rig = rig().await;
    rig.enrolled().await;
    let mut stranger = Soft::new(RP_ID, ORIGIN);

    assert_eq!(rig.login(&mut stranger, "sign-in").await.status(), 401);
}

#[tokio::test]
async fn a_challenge_issued_for_signing_in_does_not_give_an_admin_token() {
    let rig = rig().await;
    let mut approver = rig.enrolled().await;
    let options: Value = rig
        .post("/corvid/auth/login/options", json!({"purpose": "sign-in"}))
        .await
        .json()
        .await
        .unwrap();
    let challenge = decode(options["challenge"].as_str().unwrap());

    let tokens: Value = rig
        .finish_login(&mut approver, &challenge)
        .await
        .json()
        .await
        .unwrap();

    let admin = rig.issuer.verify(
        tokens["access_token"].as_str().unwrap(),
        Audience::Master,
        now_secs(),
    );
    assert!(admin.is_err());
}

#[tokio::test]
async fn a_sign_count_that_goes_backwards_is_refused_and_leaves_a_notice() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    rig.signed_in(&mut soft, "sign-in").await;
    soft.sign_count = 0;

    assert_eq!(rig.login(&mut soft, "sign-in").await.status(), 401);

    assert_eq!(rig.notices().await, ["Passkey counter went backwards"]);
}

#[tokio::test]
async fn a_synced_passkey_that_always_reports_zero_keeps_signing_in() {
    let rig = rig().await;
    let mut soft = Soft::new(RP_ID, ORIGIN);
    soft.frozen_counter = true;
    let code = rig.host_code(false).await;
    assert_eq!(rig.enroll(&mut soft, &code).await.status(), 200);

    rig.signed_in(&mut soft, "sign-in").await;
    rig.signed_in(&mut soft, "sign-in").await;
}

#[tokio::test]
async fn a_refresh_token_rotates_and_the_new_one_works() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let first = rig.signed_in(&mut soft, "sign-in").await["refresh_token"]
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
async fn a_refreshed_access_token_verifies_for_the_mail_user() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let refresh = rig.signed_in(&mut soft, "sign-in").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let body: Value = rig.refresh(&refresh).await.json().await.unwrap();

    let claims = rig
        .issuer
        .verify(
            body["access_token"].as_str().unwrap(),
            Audience::Daily,
            now_secs(),
        )
        .unwrap();
    assert_eq!(claims.preferred_username, DAILY);
}

#[tokio::test]
async fn reusing_an_old_refresh_token_revokes_the_whole_family() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let first = rig.signed_in(&mut soft, "sign-in").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let second: Value = rig.refresh(&first).await.json().await.unwrap();
    let second = second["refresh_token"].as_str().unwrap().to_owned();
    let third: Value = rig.refresh(&second).await.json().await.unwrap();
    let third = third["refresh_token"].as_str().unwrap().to_owned();

    let reuse = rig.refresh(&first).await;

    assert_eq!(reuse.status(), 400);
    let body: Value = reuse.json().await.unwrap();
    assert_eq!(body["error"], "invalid_grant");
    assert_eq!(rig.refresh(&third).await.status(), 400);
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
    let mut auth = Auth {
        user: Some("handle".to_owned()),
        ..Auth::default()
    };
    auth.credentials.insert(
        "credential".to_owned(),
        Credential {
            public_key: String::new(),
            sign_count: 0,
            backup_eligible: true,
            approves: false,
            created_at,
        },
    );
    auth.families.insert(
        "fam".to_owned(),
        Family {
            credential: "credential".to_owned(),
            current: code_hash("secret"),
            previous: None,
            rotated_at: created_at,
            created_at,
            last_used,
        },
    );
    auth
}

#[tokio::test]
async fn a_refresh_token_idle_for_a_day_is_an_invalid_grant() {
    let rig = rig_with(aged_family(
        now_secs() - 24 * 3600 - 1,
        now_secs() - 25 * 3600,
    ))
    .await;

    assert_eq!(rig.refresh("fam.secret").await.status(), 400);
}

#[tokio::test]
async fn a_refresh_token_in_use_for_a_week_is_an_invalid_grant() {
    let rig = rig_with(aged_family(now_secs() - 60, now_secs() - 7 * 24 * 3600 - 1)).await;

    assert_eq!(rig.refresh("fam.secret").await.status(), 400);
}

#[tokio::test]
async fn a_refresh_token_inside_both_limits_is_accepted() {
    let rig = rig_with(aged_family(now_secs() - 60, now_secs() - 3600)).await;

    assert_eq!(rig.refresh("fam.secret").await.status(), 200);
}

#[tokio::test]
async fn enrolled_passkeys_and_refresh_tokens_survive_a_restart() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let refresh = rig.signed_in(&mut soft, "sign-in").await["refresh_token"]
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
        .json(&json!({"purpose": "sign-in"}))
        .send()
        .await
        .unwrap();
    assert_eq!(sign_in.status(), 200);
}

#[tokio::test]
async fn the_discovery_document_and_key_set_are_served_for_stalwart() {
    let rig = rig().await;

    let discovery: Value = rig
        .get("/internal/issuer/.well-known/openid-configuration")
        .await
        .json()
        .await
        .unwrap();
    let jwks: Value = rig.get("/internal/issuer/jwks").await.json().await.unwrap();

    assert_eq!(discovery["issuer"], ISSUER);
    assert_eq!(jwks, rig.issuer.jwks());
}

#[tokio::test]
async fn userinfo_names_the_user_of_a_mail_token() {
    let rig = rig().await;
    let token = rig
        .issuer
        .mint(Audience::Daily, "handle", DAILY, now_secs());

    let body: Value = reqwest::Client::new()
        .get(format!("{}/internal/issuer/userinfo", rig.url))
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
async fn userinfo_refuses_an_admin_token() {
    let rig = rig().await;
    let token = rig
        .issuer
        .mint(Audience::Master, "handle", MASTER, now_secs());

    let response = reqwest::Client::new()
        .get(format!("{}/internal/issuer/userinfo", rig.url))
        .bearer_auth(token)
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn an_approver_mints_a_code_for_another_passkey_and_the_token_is_spent() {
    let rig = rig().await;
    let mut approver = rig.enrolled().await;
    let token = rig.master_token(&mut approver).await;

    let response = rig
        .with_master(
            reqwest::Method::POST,
            "/corvid/auth/enroll-codes",
            &token,
            json!({"approves": false}),
        )
        .await;

    assert_eq!(response.status(), 200);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["approves"], false);
    let code = body["code"].as_str().unwrap();
    let tokens: Value = rig
        .enroll(&mut Soft::new(RP_ID, ORIGIN), code)
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(tokens["approves"], false);
    let again = rig
        .with_master(
            reqwest::Method::POST,
            "/corvid/auth/enroll-codes",
            &token,
            json!({"approves": false}),
        )
        .await;
    assert_eq!(again.status(), 401);
}

#[tokio::test]
async fn an_approver_can_ask_for_a_code_that_enrolls_another_approver() {
    let rig = rig().await;
    let mut approver = rig.enrolled().await;
    let token = rig.master_token(&mut approver).await;

    let body: Value = rig
        .with_master(
            reqwest::Method::POST,
            "/corvid/auth/enroll-codes",
            &token,
            json!({"approves": true}),
        )
        .await
        .json()
        .await
        .unwrap();

    let tokens: Value = rig
        .enroll(
            &mut Soft::new(RP_ID, ORIGIN),
            body["code"].as_str().unwrap(),
        )
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(tokens["approves"], true);
}

#[tokio::test]
async fn minting_a_code_without_an_admin_token_is_refused() {
    let rig = rig().await;

    let response = rig
        .post("/corvid/auth/enroll-codes", json!({"approves": false}))
        .await;

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn a_mail_token_cannot_mint_a_code() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let token = rig.signed_in(&mut soft, "sign-in").await["access_token"]
        .as_str()
        .unwrap()
        .to_owned();

    let response = rig
        .with_master(
            reqwest::Method::POST,
            "/corvid/auth/enroll-codes",
            &token,
            json!({"approves": false}),
        )
        .await;

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn the_passkey_list_says_which_credentials_approve() {
    let rig = rig().await;
    let approver = rig.enrolled().await;
    let plain = rig.enrolled_plain().await;

    let list = rig.passkeys().await;

    assert_eq!(list.len(), 2);
    let approves = |soft: &Soft| {
        list.iter()
            .find(|p| p["id"] == encode(&soft.credential_id))
            .unwrap()["approves"]
            .clone()
    };
    assert_eq!(approves(&approver), true);
    assert_eq!(approves(&plain), false);
}

#[tokio::test]
async fn the_last_passkey_cannot_be_removed() {
    let rig = rig().await;
    let mut only = rig.enrolled().await;
    let token = rig.master_token(&mut only).await;

    let response = rig
        .with_master(
            reqwest::Method::DELETE,
            &format!("/corvid/auth/passkeys/{}", encode(&only.credential_id)),
            &token,
            json!({}),
        )
        .await;

    assert_eq!(response.status(), 409);
}

#[tokio::test]
async fn the_last_approver_cannot_be_removed_while_plain_passkeys_remain() {
    let rig = rig().await;
    let mut approver = rig.enrolled().await;
    rig.enrolled_plain().await;
    let token = rig.master_token(&mut approver).await;

    let response = rig
        .with_master(
            reqwest::Method::DELETE,
            &format!("/corvid/auth/passkeys/{}", encode(&approver.credential_id)),
            &token,
            json!({}),
        )
        .await;

    assert_eq!(response.status(), 409);
    assert_eq!(rig.passkeys().await.len(), 2);
}

#[tokio::test]
async fn an_approver_can_be_removed_while_another_remains() {
    let rig = rig().await;
    let mut first = rig.enrolled().await;
    let second = rig.enrolled().await;
    let token = rig.master_token(&mut first).await;

    let response = rig
        .with_master(
            reqwest::Method::DELETE,
            &format!("/corvid/auth/passkeys/{}", encode(&second.credential_id)),
            &token,
            json!({}),
        )
        .await;

    assert_eq!(response.status(), 204);
}

#[tokio::test]
async fn a_plain_passkey_can_be_removed_by_an_approver() {
    let rig = rig().await;
    let mut approver = rig.enrolled().await;
    let plain = rig.enrolled_plain().await;
    let token = rig.master_token(&mut approver).await;

    let response = rig
        .with_master(
            reqwest::Method::DELETE,
            &format!("/corvid/auth/passkeys/{}", encode(&plain.credential_id)),
            &token,
            json!({}),
        )
        .await;

    assert_eq!(response.status(), 204);
    let list = rig.passkeys().await;
    assert!(
        list.iter()
            .any(|p| p["id"] == encode(&approver.credential_id))
    );
    assert!(!list.iter().any(|p| p["id"] == encode(&plain.credential_id)));
}

#[tokio::test]
async fn removing_a_passkey_needs_an_admin_token() {
    let rig = rig().await;
    let first = rig.enrolled().await;
    rig.enrolled().await;

    let response = reqwest::Client::new()
        .delete(format!(
            "{}/corvid/auth/passkeys/{}",
            rig.url,
            encode(&first.credential_id)
        ))
        .send()
        .await
        .unwrap();

    assert_eq!(response.status(), 401);
}

#[tokio::test]
async fn the_host_status_counts_approvers_among_the_passkeys() {
    let rig = rig().await;
    rig.enrolled().await;
    rig.enrolled_plain().await;

    let body = rig.host_status().await;

    assert_eq!(body["approvers"], 1);
    assert_eq!(body["passkeys"].as_array().unwrap().len(), 2);
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
        .post(format!("{}/internal/auth/codes", rig.url))
        .header("X-Signature", common::signature(SECRET, b"code"))
        .json(&json!({"approves": true}))
        .send()
        .await
        .unwrap();

    assert_eq!(unsigned.status(), 401);
    assert_eq!(wrong.status(), 401);
}

#[tokio::test]
async fn the_host_removes_a_second_passkey_by_id() {
    let rig = rig().await;
    rig.enrolled().await;
    let second = rig.enrolled().await;
    let id = encode(&second.credential_id);

    let response = reqwest::Client::new()
        .delete(format!("{}/internal/auth/passkeys/{id}", rig.url))
        .header(
            "X-Signature",
            common::signature(SECRET, format!("remove-passkey {id}").as_bytes()),
        )
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

    for path in [
        "/corvid/auth/passkeys",
        "/internal/auth/status",
        "/internal/issuer/jwks",
    ] {
        let response = reqwest::get(format!("{url}{path}")).await.unwrap();
        assert_eq!(response.status(), 503);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["error"], "auth_off");
    }
}

fn rotated_family(rotated_at: i64) -> Auth {
    let mut auth = aged_family(now_secs() - 60, now_secs() - 3600);
    let family = auth.families.get_mut("fam").unwrap();
    family.current = code_hash("new");
    family.previous = Some(code_hash("old"));
    family.rotated_at = rotated_at;
    auth
}

#[tokio::test]
async fn the_token_a_lost_response_replaced_still_refreshes_inside_the_grace() {
    let rig = rig_with(rotated_family(now_secs() - 30)).await;

    let response = rig.refresh("fam.old").await;

    assert_eq!(response.status(), 200);
    assert!(rig.notices().await.is_empty());
}

#[tokio::test]
async fn the_token_a_retry_replaced_is_the_one_that_works_after_it() {
    let rig = rig_with(rotated_family(now_secs() - 30)).await;
    let body: Value = rig.refresh("fam.old").await.json().await.unwrap();
    let retried = body["refresh_token"].as_str().unwrap().to_owned();

    assert_eq!(rig.refresh(&retried).await.status(), 200);
}

#[tokio::test]
async fn the_replaced_token_presented_after_the_grace_revokes_the_family_and_notices() {
    let rig = rig_with(rotated_family(now_secs() - 61)).await;

    assert_eq!(rig.refresh("fam.old").await.status(), 400);

    assert_eq!(rig.refresh("fam.new").await.status(), 400);
    assert_eq!(rig.notices().await, ["Refresh token reused"]);
}

#[tokio::test]
async fn removing_a_passkey_ends_the_sign_ins_it_made() {
    let rig = rig().await;
    let mut phone = rig.enrolled().await;
    rig.enrolled().await;
    let mut master = rig.enrolled().await;
    let refresh = rig.signed_in(&mut phone, "sign-in").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();
    let token = rig.master_token(&mut master).await;

    let removed = rig
        .with_master(
            reqwest::Method::DELETE,
            &format!("/corvid/auth/passkeys/{}", encode(&phone.credential_id)),
            &token,
            json!({}),
        )
        .await;

    assert_eq!(removed.status(), 204);
    assert_eq!(rig.refresh(&refresh).await.status(), 400);
}

#[tokio::test]
async fn removing_one_passkey_leaves_the_sign_ins_of_another() {
    let rig = rig().await;
    let mut phone = rig.enrolled().await;
    let mut tablet = rig.enrolled().await;
    let mut master = rig.enrolled().await;
    let kept = rig.signed_in(&mut tablet, "sign-in").await["refresh_token"]
        .as_str()
        .unwrap()
        .to_owned();
    rig.signed_in(&mut phone, "sign-in").await;
    let token = rig.master_token(&mut master).await;

    rig.with_master(
        reqwest::Method::DELETE,
        &format!("/corvid/auth/passkeys/{}", encode(&phone.credential_id)),
        &token,
        json!({}),
    )
    .await;

    assert_eq!(rig.refresh(&kept).await.status(), 200);
}

#[tokio::test]
async fn one_address_cannot_crowd_another_out_of_sign_in() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let mut challenges = Vec::new();
    for _ in 0..100 {
        let response = rig
            .post_from(
                "/corvid/auth/login/options",
                json!({"purpose": "sign-in"}),
                "203.0.113.5",
            )
            .await;
        assert_eq!(response.status(), 200);
        let options: Value = response.json().await.unwrap();
        challenges.push(decode(options["challenge"].as_str().unwrap()));
    }

    let elsewhere = rig
        .post_from(
            "/corvid/auth/login/options",
            json!({"purpose": "sign-in"}),
            "203.0.113.6",
        )
        .await;

    assert_eq!(elsewhere.status(), 200);
    assert_eq!(
        rig.finish_login(&mut soft, challenges.last().unwrap())
            .await
            .status(),
        200
    );
}

#[tokio::test]
async fn an_address_past_its_share_loses_its_oldest_challenge() {
    let rig = rig().await;
    let mut soft = rig.enrolled().await;
    let mut first = None;
    for _ in 0..5 {
        let options: Value = rig
            .post_from(
                "/corvid/auth/login/options",
                json!({"purpose": "sign-in"}),
                "203.0.113.5",
            )
            .await
            .json()
            .await
            .unwrap();
        first.get_or_insert(decode(options["challenge"].as_str().unwrap()));
    }

    assert_eq!(
        rig.finish_login(&mut soft, &first.unwrap()).await.status(),
        401
    );
}

#[tokio::test]
async fn the_host_status_lists_the_codes_waiting_and_what_they_enroll() {
    let rig = rig().await;
    let minted = rig.host_minted(true).await;

    let body = rig.host_status().await;

    assert_eq!(
        body["codes"],
        json!([{"approves": true, "expiresAt": minted["expiresAt"]}])
    );
}

const MASTER_HANDLE: &str = "master-handle";
const DAILY_HANDLE: &str = "daily-handle";

fn credential_of(soft: &Soft, user: &str) -> (String, legacy::Credential) {
    (
        encode(&soft.credential_id),
        legacy::Credential {
            user: user.to_owned(),
            public_key: encode(soft.public_key().to_sec1_point(false).as_bytes()),
            sign_count: soft.sign_count,
            backup_eligible: false,
            created_at: 1_700_000_000,
        },
    )
}

fn schema_one(master: &Soft) -> legacy::Auth {
    let (id, credential) = credential_of(master, MASTER_HANDLE);
    legacy::Auth {
        users: [(MASTER_HANDLE.to_owned(), legacy::Role::Master)].into(),
        credentials: [(id, credential)].into(),
        codes: [(
            legacy::Role::Daily,
            legacy::Code {
                hash: code_hash("AAAABBBBCCCCDDDD"),
                expires_at: now_secs() + 3600,
            },
        )]
        .into(),
        families: Default::default(),
    }
}

fn write_schema_one(dir: &Path, old: &legacy::Auth) {
    corvid_store::save(&dir.join("auth.json"), legacy::SCHEMA, old).unwrap();
}

async fn migrated_rig(master: &Soft) -> Rig {
    let dir = tempfile::tempdir().unwrap();
    write_schema_one(dir.path(), &schema_one(master));
    let state = auth::open(&dir.path().join("auth.json")).unwrap();
    let issuer = Arc::new(Issuer::new(signing_key(), ISSUER.to_owned()));
    let url = start(dir.path(), state, issuer.clone()).await;
    Rig { url, issuer, dir }
}

fn enrolled_master() -> Soft {
    let mut soft = Soft::new(RP_ID, ORIGIN);
    soft.sign_count = 3;
    soft
}

#[tokio::test]
async fn a_schema_one_master_credential_migrates_to_an_approver_that_signs_in() {
    let mut master = enrolled_master();
    let rig = migrated_rig(&master).await;

    let tokens = rig.signed_in(&mut master, "sign-in").await;
    let admin = rig.signed_in(&mut master, "approve").await;

    assert!(tokens["refresh_token"].is_string());
    rig.issuer
        .verify(
            admin["access_token"].as_str().unwrap(),
            Audience::Master,
            now_secs(),
        )
        .unwrap();
    assert_eq!(rig.host_status().await["approvers"], 1);
}

#[test]
fn a_schema_one_state_with_both_handles_keeps_the_master_one() {
    let master = enrolled_master();
    let mut old = schema_one(&master);
    old.users
        .insert(DAILY_HANDLE.to_owned(), legacy::Role::Daily);

    let migrated = legacy::migrate(old);

    assert_eq!(migrated.user.as_deref(), Some(MASTER_HANDLE));
}

#[tokio::test]
async fn a_schema_one_daily_code_migrates_to_a_code_for_a_plain_passkey() {
    let master = enrolled_master();
    let rig = migrated_rig(&master).await;

    let status = rig.host_status().await;

    assert_eq!(status["codes"].as_array().unwrap().len(), 1);
    assert_eq!(status["codes"][0]["approves"], false);
    let tokens: Value = rig
        .enroll(&mut Soft::new(RP_ID, ORIGIN), "AAAA-BBBB-CCCC-DDDD")
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(tokens["approves"], false);
}

#[test]
fn a_schema_one_master_code_migrates_to_an_approver_code_and_families_carry_over() {
    let master = enrolled_master();
    let mut old = schema_one(&master);
    old.codes.insert(
        legacy::Role::Master,
        legacy::Code {
            hash: code_hash("EEEEFFFFGGGGHHHH"),
            expires_at: now_secs() + 3600,
        },
    );
    old.families = aged_family(now_secs() - 60, now_secs() - 3600).families;

    let migrated = legacy::migrate(old);

    let approver_codes: Vec<_> = migrated.codes.iter().filter(|c| c.approves).collect();
    assert_eq!(approver_codes.len(), 1);
    assert_eq!(approver_codes[0].hash, code_hash("EEEEFFFFGGGGHHHH"));
    assert_eq!(migrated.families.len(), 1);
}

#[tokio::test]
async fn the_migrated_state_is_written_as_schema_two_on_the_next_change() {
    let master = enrolled_master();
    let rig = migrated_rig(&master).await;

    rig.host_code(false).await;

    let saved: Option<Auth> = corvid_store::load(&rig.dir.path().join("auth.json"), 2).unwrap();
    assert_eq!(saved.unwrap().codes.len(), 2);
}

#[test]
fn a_daily_only_schema_one_state_keeps_its_handle_and_has_no_approver() {
    let mut old = legacy::Auth::default();
    old.users
        .insert(DAILY_HANDLE.to_owned(), legacy::Role::Daily);

    let migrated = legacy::migrate(old);

    assert_eq!(migrated.user.as_deref(), Some(DAILY_HANDLE));
    assert_eq!(migrated.approvers(), 0);
}

#[tokio::test]
async fn redeeming_a_migrated_plain_code_on_a_server_with_no_approver_enrolls_an_approver() {
    let dir = tempfile::tempdir().unwrap();
    let mut old = legacy::Auth::default();
    old.codes.insert(
        legacy::Role::Daily,
        legacy::Code {
            hash: code_hash("AAAABBBBCCCCDDDD"),
            expires_at: now_secs() + 3600,
        },
    );
    write_schema_one(dir.path(), &old);
    let state = auth::open(&dir.path().join("auth.json")).unwrap();
    let issuer = Arc::new(Issuer::new(signing_key(), ISSUER.to_owned()));
    let url = start(dir.path(), state, issuer.clone()).await;
    let rig = Rig { url, issuer, dir };

    let tokens: Value = rig
        .enroll(&mut Soft::new(RP_ID, ORIGIN), "AAAA-BBBB-CCCC-DDDD")
        .await
        .json()
        .await
        .unwrap();

    assert_eq!(tokens["approves"], true);
    assert_eq!(rig.host_status().await["approvers"], 1);
}

fn request_word(dir: &Path) -> Option<String> {
    std::fs::read_to_string(dir.join("update").join("request")).ok()
}

async fn rig_with_updater() -> Rig {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir(dir.path().join("update")).unwrap();
    let issuer = Arc::new(Issuer::new(signing_key(), ISSUER.to_owned()));
    let mut config = common::config(
        &dir.path().join("sentinel.json"),
        &dir.path().join("update"),
    );
    config.auth = Some(auth::Config {
        secret: SECRET.to_vec(),
        state_path: dir.path().join("auth.json"),
        auth: Auth::default(),
        issuer: issuer.clone(),
        rp_id: RP_ID.to_owned(),
        origins: vec![ORIGIN.to_owned()],
        daily_user: DAILY.to_owned(),
        master_user: MASTER.to_owned(),
    });
    let url = common::serve(config).await;
    Rig { url, issuer, dir }
}

#[tokio::test]
async fn the_first_approver_registering_asks_the_host_to_deploy() {
    let rig = rig_with_updater().await;
    assert_eq!(request_word(rig.dir.path()), None);

    rig.enrolled().await;

    assert_eq!(request_word(rig.dir.path()).as_deref(), Some("deploy"));
}

#[tokio::test]
async fn a_second_key_asks_the_host_for_nothing() {
    let rig = rig_with_updater().await;
    rig.enrolled().await;
    std::fs::remove_file(rig.dir.path().join("update").join("request")).unwrap();

    rig.enrolled_plain().await;
    rig.enrolled().await;

    assert_eq!(request_word(rig.dir.path()), None);
}

#[tokio::test]
async fn a_deploy_request_does_not_replace_a_held_apply_and_a_check_does_not_replace_a_deploy() {
    let rig = rig_with_updater().await;
    let held = rig.dir.path().join("update").join("request");
    std::fs::write(&held, "apply").unwrap();
    rig.enrolled().await;
    assert_eq!(request_word(rig.dir.path()).as_deref(), Some("apply"));

    std::fs::write(&held, "deploy").unwrap();
    let update = reqwest::Client::new()
        .post(format!("{}/corvid/update", rig.url))
        .json(&json!({"action": "check"}))
        .send()
        .await
        .unwrap();

    assert_eq!(update.status(), 202);
    assert_eq!(request_word(rig.dir.path()).as_deref(), Some("deploy"));
}
