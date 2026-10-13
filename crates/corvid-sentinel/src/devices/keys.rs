use std::fmt;
use std::sync::{Mutex, PoisonError};

use serde_json::{Value, json};

use super::random_bytes;
use super::state::Approved;

const FOLDER: &str = "corvid-devices";
const KEY_VERSION: u8 = 1;
const KEY_LENGTH: usize = 32;
const USING: [&str; 4] = [
    "urn:ietf:params:jmap:core",
    "urn:ietf:params:jmap:filenode",
    "urn:ietf:params:jmap:mail",
    "urn:stalwart:jmap",
];

#[derive(Debug)]
pub struct Error(String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Error {
    pub fn new(reason: &str) -> Self {
        Self(reason.to_owned())
    }
}

impl From<reqwest::Error> for Error {
    fn from(error: reqwest::Error) -> Self {
        Self(error.to_string())
    }
}

/// Writes device keys into the keys account as the admin API key. The mailbox account holds only
/// a read share on the folder, so it can read a key but never replace or remove one.
pub struct Keys {
    http: reqwest::Client,
    url: String,
    token: String,
    keys_address: String,
    mail_address: String,
    target: Mutex<Option<Target>>,
}

/// The ids a key is written under, which do not change once the folder exists.
#[derive(Clone)]
struct Target {
    account: String,
    folder: String,
}

impl Keys {
    pub fn new(url: String, token: String, keys_address: String, mail_address: String) -> Self {
        Self {
            http: reqwest::Client::new(),
            url,
            token,
            keys_address,
            mail_address,
            target: Mutex::default(),
        }
    }

    pub fn mail_address(&self) -> &str {
        &self.mail_address
    }

    /// Creates the key file for a device with fresh random content and returns where it is. A
    /// failure on remembered ids looks them up again before it counts.
    pub async fn enroll(&self, device: &str) -> Result<Approved, Error> {
        let remembered = self
            .target
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone();
        if let Some(target) = remembered
            && let Ok(approved) = self.create(device, &target).await
        {
            return Ok(approved);
        }
        let account = self.account(&self.keys_address).await?;
        let mail = self.account(&self.mail_address).await?;
        let folder = self.folder(&account, &mail).await?;
        let target = Target { account, folder };
        let approved = self.create(device, &target).await?;
        *self.target.lock().unwrap_or_else(PoisonError::into_inner) = Some(target);
        Ok(approved)
    }

    /// Destroys a key file. A node that is already gone counts as destroyed.
    pub async fn destroy(&self, approved: &Approved) -> Result<(), Error> {
        let result = self
            .call(
                "FileNode/set",
                json!({"accountId": approved.account_id, "destroy": [approved.node_id]}),
            )
            .await?;
        match result["notDestroyed"].get(&approved.node_id) {
            Some(refusal) if refusal["type"] != "notFound" => {
                Err(Error(format!("the key was not destroyed: {refusal}")))
            }
            _ => Ok(()),
        }
    }

    async fn create(&self, device: &str, target: &Target) -> Result<Approved, Error> {
        let content = [&[KEY_VERSION][..], &random_bytes::<KEY_LENGTH>()].concat();
        let blob = self.upload(&target.account, content).await?;
        let name = format!("{device}.key");
        let answers = self
            .batch(&[
                (
                    "FileNode/set",
                    json!({"accountId": target.account, "create": {"n": {
                        "name": name, "parentId": target.folder,
                        "blobId": blob}}}),
                ),
                (
                    "FileNode/query",
                    json!({"accountId": target.account, "filter": {"name": name}}),
                ),
                (
                    "FileNode/get",
                    json!({"accountId": target.account, "properties": ["id", "blobId"],
                           "#ids": back("c1", "FileNode/query", "/ids")}),
                ),
            ])
            .await?;
        let created = checked("FileNode/set", &answers[0])?;
        let node_id = created["created"]["n"]["id"]
            .as_str()
            .ok_or_else(|| {
                Error(format!(
                    "the key was not created: {}",
                    created["notCreated"]
                ))
            })?
            .to_owned();
        let blob_id = checked("FileNode/get", &answers[2])?["list"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|node| node["id"] == node_id.as_str())
            .and_then(|node| node["blobId"].as_str())
            .ok_or_else(|| Error("the created key has no blob".to_owned()))?
            .to_owned();
        Ok(Approved {
            account_id: target.account.clone(),
            node_id,
            blob_id,
        })
    }

    /// Looks accounts up by address, since a mailbox user of the same name in the mail domain
    /// gets an account of its own with the same local part.
    pub(super) async fn account(&self, address: &str) -> Result<String, Error> {
        let local = address.split('@').next().unwrap_or_default();
        let answers = self
            .batch(&[
                ("x:Account/query", json!({"filter": {"name": local}})),
                (
                    "x:Account/get",
                    json!({"properties": ["emailAddress"],
                           "#ids": back("c0", "x:Account/query", "/ids")}),
                ),
            ])
            .await?;
        checked("x:Account/get", &answers[1])?["list"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|account| account["emailAddress"] == address)
            .and_then(|account| account["id"].as_str())
            .map(str::to_owned)
            .ok_or_else(|| Error(format!("no account {address}")))
    }

    /// The top-level folder, created on first use, shared read-only with the mailbox.
    async fn folder(&self, keys: &str, mail: &str) -> Result<String, Error> {
        let answers = self
            .batch(&[
                (
                    "FileNode/query",
                    json!({"accountId": keys, "filter": {"name": FOLDER}}),
                ),
                (
                    "FileNode/get",
                    json!({"accountId": keys,
                           "properties": ["id", "name", "parentId", "shareWith"],
                           "#ids": back("c0", "FileNode/query", "/ids")}),
                ),
            ])
            .await?;
        let share = json!({(mail): {"mayRead": true}});
        let existing = checked("FileNode/get", &answers[1])?["list"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|node| node["name"] == FOLDER && node["parentId"].is_null());
        let Some(existing) = existing else {
            let created = self
                .call(
                    "FileNode/set",
                    json!({"accountId": keys, "create": {"f": {
                        "name": FOLDER, "parentId": null, "shareWith": share}}}),
                )
                .await?;
            return created["created"]["f"]["id"]
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| {
                    Error(format!(
                        "the folder was not created: {}",
                        created["notCreated"]
                    ))
                });
        };
        let id = existing["id"].as_str().unwrap_or_default().to_owned();
        if existing["shareWith"].get(mail).is_none() {
            let updated = self
                .call(
                    "FileNode/set",
                    json!({"accountId": keys, "update": {(&id): {"shareWith": share}}}),
                )
                .await?;
            if updated["updated"].get(&id).is_none() {
                return Err(Error(format!(
                    "the folder was not shared: {}",
                    updated["notUpdated"]
                )));
            }
        }
        Ok(id)
    }

    async fn upload(&self, account: &str, content: Vec<u8>) -> Result<String, Error> {
        let response = self
            .http
            .post(format!("{}/jmap/upload/{account}/", self.url))
            .bearer_auth(&self.token)
            .header("content-type", "application/octet-stream")
            .body(content)
            .send()
            .await?
            .error_for_status()?;
        let uploaded: Value = response.json().await?;
        uploaded["blobId"]
            .as_str()
            .map(str::to_owned)
            .ok_or_else(|| Error("the upload returned no blob".to_owned()))
    }

    /// One method call, answered with its arguments. Account-scoped methods carry their own
    /// `accountId`; `x:Account/query` runs as the admin account the key belongs to.
    async fn call(&self, method: &str, arguments: Value) -> Result<Value, Error> {
        let answers = self.batch(&[(method, arguments)]).await?;
        checked(method, &answers[0]).cloned()
    }

    /// Method calls in one request, named `c0`, `c1`, and so on. The answers come back as sent,
    /// errors included, so a refusal is read before the answer to the call that depended on it.
    pub(super) async fn batch(&self, calls: &[(&str, Value)]) -> Result<Vec<Value>, Error> {
        let calls: Vec<Value> = calls
            .iter()
            .enumerate()
            .map(|(index, (method, arguments))| json!([method, arguments, format!("c{index}")]))
            .collect();
        let response = self
            .http
            .post(format!("{}/jmap", self.url))
            .bearer_auth(&self.token)
            .json(&json!({"using": USING, "methodCalls": calls}))
            .send()
            .await?
            .error_for_status()?;
        let mut answer: Value = response.json().await?;
        Ok(answer["methodResponses"]
            .take()
            .as_array()
            .cloned()
            .unwrap_or_default())
    }
}

/// A reference to `path` in the answer of call `call` of the same batch. Stalwart resolves
/// these into query results only, not into the `created` of a set.
fn back(call: &str, method: &str, path: &str) -> Value {
    json!({"resultOf": call, "name": method, "path": path})
}

pub(super) fn checked<'a>(method: &str, answer: &'a Value) -> Result<&'a Value, Error> {
    if answer[0] == "error" {
        return Err(Error(format!("{method} failed: {}", answer[1])));
    }
    Ok(&answer[1])
}
