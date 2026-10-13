//! A passkey that lives in a file, for the deploy rig. It speaks the sentinel's public auth routes
//! the way the apps do and prints the final response body. `schema1` rewrites a sentinel's auth
//! state file in the schema-1 shape, approvers under one handle and the rest under another.
//!
//! ```text
//! soft-authenticator STATE URL RP_ID ORIGIN enroll CODE [backup-eligible]
//! soft-authenticator STATE URL RP_ID ORIGIN login PURPOSE
//! soft-authenticator schema1 AUTH_STATE
//! ```

use std::collections::BTreeMap;
use std::path::Path;
use std::process::ExitCode;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use corvid_sentinel::auth::legacy::{self, Role};
use corvid_sentinel::auth::soft::{BACKUP_ELIGIBLE, Soft};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

#[derive(Serialize, Deserialize)]
struct Saved {
    secret: String,
    credential_id: String,
    sign_count: u32,
}

fn encode(bytes: impl AsRef<[u8]>) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

fn decode(text: &str) -> Vec<u8> {
    URL_SAFE_NO_PAD.decode(text).expect("base64url")
}

async fn post(url: &str, body: Value) -> (u16, Value) {
    let response = reqwest::Client::new()
        .post(url)
        .json(&body)
        .send()
        .await
        .expect("the sentinel answers");
    let status = response.status().as_u16();
    (status, response.json().await.unwrap_or(Value::Null))
}

async fn enroll(soft: &mut Soft, url: &str, code: &str) -> (u16, Value) {
    let (status, options) = post(&format!("{url}/register/options"), json!({"code": code})).await;
    if status != 200 {
        return (status, options);
    }
    let challenge = decode(options["challenge"].as_str().expect("a challenge"));
    post(
        &format!("{url}/register/finish"),
        soft.registration_json(&challenge),
    )
    .await
}

async fn login(soft: &mut Soft, url: &str, purpose: &str) -> (u16, Value) {
    let (status, options) =
        post(&format!("{url}/login/options"), json!({"purpose": purpose})).await;
    if status != 200 {
        return (status, options);
    }
    let challenge = decode(options["challenge"].as_str().expect("a challenge"));
    post(
        &format!("{url}/login/finish"),
        soft.assertion_json(&challenge),
    )
    .await
}

fn write_schema1(path: &Path) {
    let auth = corvid_sentinel::auth::open(path).expect("a current auth state");
    let user = |approves: bool| {
        if approves {
            ("master-handle".to_owned(), Role::Master)
        } else {
            ("daily-handle".to_owned(), Role::Daily)
        }
    };
    let old = legacy::Auth {
        users: auth
            .credentials
            .values()
            .map(|credential| user(credential.approves))
            .collect(),
        credentials: auth
            .credentials
            .into_iter()
            .map(|(id, credential)| {
                let migrated = legacy::Credential {
                    user: user(credential.approves).0,
                    public_key: credential.public_key,
                    sign_count: credential.sign_count,
                    backup_eligible: credential.backup_eligible,
                    created_at: credential.created_at,
                };
                (id, migrated)
            })
            .collect(),
        codes: BTreeMap::new(),
        families: auth.families,
    };
    corvid_store::save(path, legacy::SCHEMA, &old).expect("the auth state is writable");
}

#[tokio::main]
async fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if let [command, path] = args.as_slice()
        && command == "schema1"
    {
        write_schema1(Path::new(path));
        return ExitCode::SUCCESS;
    }
    let [state, url, rp_id, origin, command, argument, rest @ ..] = args.as_slice() else {
        eprintln!("usage: soft-authenticator STATE URL RP_ID ORIGIN enroll CODE | login PURPOSE");
        return ExitCode::from(64);
    };
    let url = format!("{}/corvid/auth", url.trim_end_matches('/'));
    let path = Path::new(state);
    let mut soft = match std::fs::read_to_string(path) {
        Ok(text) => {
            let saved: Saved = serde_json::from_str(&text).expect("a saved authenticator");
            Soft::restore(
                rp_id,
                origin,
                &decode(&saved.secret),
                decode(&saved.credential_id),
                saved.sign_count,
            )
        }
        Err(_) => Soft::new(rp_id, origin),
    };
    soft.flags |= if rest.first().is_some_and(|flag| flag == "backup-eligible") {
        BACKUP_ELIGIBLE
    } else {
        0
    };

    let (status, body) = match command.as_str() {
        "enroll" => enroll(&mut soft, &url, argument).await,
        "login" => login(&mut soft, &url, argument).await,
        _ => {
            eprintln!("unknown command {command}");
            return ExitCode::from(64);
        }
    };
    let saved = Saved {
        secret: encode(soft.secret()),
        credential_id: encode(&soft.credential_id),
        sign_count: soft.sign_count,
    };
    std::fs::write(
        path,
        serde_json::to_string(&saved).expect("state serializes"),
    )
    .expect("the state file is writable");
    println!("{body}");
    if status == 200 {
        ExitCode::SUCCESS
    } else {
        eprintln!("the sentinel answered {status}");
        ExitCode::FAILURE
    }
}
