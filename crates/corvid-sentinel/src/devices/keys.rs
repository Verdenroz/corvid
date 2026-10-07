use std::fmt;

use ring::rand::{SecureRandom, SystemRandom};
use serde_json::{Value, json};

use super::state::Approved;

const KEYS_ACCOUNT: &str = "corvid-keys";
const FOLDER: &str = "corvid-devices";
const KEY_VERSION: u8 = 1;
const KEY_LENGTH: usize = 32;
const USING: [&str; 3] = [
    "urn:ietf:params:jmap:core",
    "urn:ietf:params:jmap:filenode",
    "urn:stalwart:jmap",
];

#[derive(Debug)]
pub struct Error(String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
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
    mail_user: String,
}

impl Keys {
    pub fn new(url: String, token: String, mail_user: String) -> Self {
        Self {
            http: reqwest::Client::new(),
            url,
            token,
            mail_user,
        }
    }

    /// Creates the key file for a device with fresh random content and returns where it is.
    pub async fn enroll(&self, device: &str) -> Result<Approved, Error> {
        let keys = self.account(KEYS_ACCOUNT).await?;
        let mail = self.account(&self.mail_user).await?;
        let folder = self.folder(&keys, &mail).await?;
        let mut content = vec![KEY_VERSION; KEY_LENGTH + 1];
        SystemRandom::new()
            .fill(&mut content[1..])
            .map_err(|_| Error("no randomness".to_owned()))?;
        let blob = self.upload(&keys, content).await?;
        let created = self
            .call(
                "FileNode/set",
                json!({"accountId": keys, "create": {"n": {
                    "name": format!("{device}.key"), "parentId": folder, "blobId": blob}}}),
            )
            .await?;
        let node_id = created["created"]["n"]["id"]
            .as_str()
            .ok_or_else(|| {
                Error(format!(
                    "the key was not created: {}",
                    created["notCreated"]
                ))
            })?
            .to_owned();
        let node = self
            .call(
                "FileNode/get",
                json!({"accountId": keys, "ids": [node_id], "properties": ["blobId"]}),
            )
            .await?;
        let blob_id = node["list"][0]["blobId"]
            .as_str()
            .ok_or_else(|| Error("the created key has no blob".to_owned()))?
            .to_owned();
        Ok(Approved {
            account_id: keys,
            node_id,
            blob_id,
        })
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

    async fn account(&self, name: &str) -> Result<String, Error> {
        let found = self
            .call("x:Account/query", json!({"filter": {"name": name}}))
            .await?;
        match found["ids"].as_array().map(Vec::as_slice) {
            Some([id]) => Ok(id.as_str().unwrap_or_default().to_owned()),
            _ => Err(Error(format!("no single account named {name}"))),
        }
    }

    /// The top-level folder, created on first use, shared read-only with the mailbox.
    async fn folder(&self, keys: &str, mail: &str) -> Result<String, Error> {
        let listing = self
            .call(
                "FileNode/get",
                json!({"accountId": keys, "ids": null, "properties": ["id", "name", "parentId", "shareWith"]}),
            )
            .await?;
        let share = json!({(mail): {"mayRead": true}});
        let existing = listing["list"]
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
        let body = json!({"using": USING, "methodCalls": [[method, arguments, "c0"]]});
        let response = self
            .http
            .post(format!("{}/jmap", self.url))
            .bearer_auth(&self.token)
            .json(&body)
            .send()
            .await?
            .error_for_status()?;
        let mut answer: Value = response.json().await?;
        let result = answer["methodResponses"][0].take();
        if result[0] == "error" {
            return Err(Error(format!("{method} failed: {}", result[1])));
        }
        Ok(result[1].clone())
    }
}
