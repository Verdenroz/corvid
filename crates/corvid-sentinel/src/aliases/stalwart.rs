use std::fmt;
use std::sync::{Mutex, PoisonError};
use std::time::Duration;

use reqwest::StatusCode;
use serde_json::{Map, Value, json};

use crate::devices::keys::{back, batch, checked};

const BACKOFF: Duration = Duration::from_secs(1);

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    /// Stalwart answered 429 twice.
    Busy,
    /// Stalwart refused the patch, as it does a name another account already holds.
    Refused(String),
    Failed(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Busy => f.write_str("Stalwart is rate-limiting"),
            Self::Refused(reason) => write!(f, "Stalwart refused the change: {reason}"),
            Self::Failed(reason) => f.write_str(reason),
        }
    }
}

impl From<crate::devices::keys::Error> for Error {
    fn from(error: crate::devices::keys::Error) -> Self {
        Self::Failed(error.to_string())
    }
}

/// The mail account and its domain, which do not change once found.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Target {
    pub account: String,
    pub domain: String,
}

/// One entry of `Account.aliases`. `index` is its list position, which is also its patch key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Alias {
    pub index: usize,
    pub name: String,
    pub enabled: bool,
}

/// Reads and patches the mail account's aliases as the admin API key, since an account cannot
/// change its own.
pub struct Aliases {
    http: reqwest::Client,
    url: String,
    token: String,
    mail_address: String,
    target: Mutex<Option<Target>>,
}

impl Aliases {
    pub fn new(url: String, token: String, mail_address: String, timeout: Duration) -> Self {
        Self {
            http: reqwest::Client::builder()
                .timeout(timeout)
                .build()
                .expect("a client with a timeout builds"),
            url,
            token,
            mail_address,
            target: Mutex::default(),
        }
    }

    /// The account, its domain and its aliases in list order.
    pub async fn read(&self) -> Result<(Target, Vec<Alias>), Error> {
        self.fetch().await.inspect_err(|_| self.forget())
    }

    /// Applies `patch` to the account, keyed by JSON pointers such as `aliases/2/enabled`.
    pub async fn patch(&self, target: &Target, patch: Map<String, Value>) -> Result<(), Error> {
        self.update(target, patch)
            .await
            .inspect_err(|_| self.forget())
    }

    fn forget(&self) {
        *self.target.lock().unwrap_or_else(PoisonError::into_inner) = None;
    }

    async fn fetch(&self) -> Result<(Target, Vec<Alias>), Error> {
        let properties = json!(["emailAddress", "domainId", "aliases"]);
        let remembered = self
            .target
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone();
        let calls = match &remembered {
            Some(target) => vec![(
                "x:Account/get",
                json!({"ids": [target.account], "properties": properties}),
            )],
            None => {
                let local = self.mail_address.split('@').next().unwrap_or_default();
                vec![
                    ("x:Account/query", json!({"filter": {"name": local}})),
                    (
                        "x:Account/get",
                        json!({"properties": properties,
                               "#ids": back("c0", "x:Account/query", "/ids")}),
                    ),
                ]
            }
        };
        let answers = self.send(&calls).await?;
        let got = answers
            .last()
            .ok_or_else(|| Error::Failed("Stalwart answered no method call".to_owned()))?;
        let account = checked("x:Account/get", got)?["list"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|account| account["emailAddress"] == self.mail_address.as_str())
            .ok_or_else(|| Error::Failed(format!("no account {}", self.mail_address)))?;
        let target = Target {
            account: string(account, "id")?,
            domain: string(account, "domainId")?,
        };
        *self.target.lock().unwrap_or_else(PoisonError::into_inner) = Some(target.clone());
        Ok((target, aliases(&account["aliases"])?))
    }

    async fn update(&self, target: &Target, patch: Map<String, Value>) -> Result<(), Error> {
        let answers = self
            .send(&[(
                "x:Account/set",
                json!({"update": {(&target.account): patch}}),
            )])
            .await?;
        let answer = checked("x:Account/set", &answers[0])?;
        if answer["updated"].get(&target.account).is_some() {
            return Ok(());
        }
        Err(Error::Refused(
            answer["notUpdated"][&target.account].to_string(),
        ))
    }

    /// One batch, retried once after a pause if Stalwart answers 429.
    async fn send(&self, calls: &[(&str, Value)]) -> Result<Vec<Value>, Error> {
        for attempt in 0..2 {
            match batch(&self.http, &self.url, &self.token, calls).await {
                Ok(answers) => return Ok(answers),
                Err(error) if error.status() == Some(StatusCode::TOO_MANY_REQUESTS) => {
                    if attempt == 0 {
                        tokio::time::sleep(BACKOFF).await;
                    }
                }
                Err(error) => return Err(Error::Failed(error.to_string())),
            }
        }
        Err(Error::Busy)
    }
}

fn string(value: &Value, field: &str) -> Result<String, Error> {
    value[field]
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| Error::Failed(format!("the account has no {field}")))
}

/// The aliases map in key order. A writer checks the keys run 0, 1, 2 before it trusts a position.
fn aliases(map: &Value) -> Result<Vec<Alias>, Error> {
    let mut list = Vec::new();
    for (key, alias) in map.as_object().into_iter().flatten() {
        let index = key
            .parse()
            .map_err(|_| Error::Failed(format!("an alias key is not a position: {key}")))?;
        list.push(Alias {
            index,
            name: string(alias, "name")?,
            enabled: alias["enabled"].as_bool().unwrap_or(true),
        });
    }
    list.sort_by_key(|alias| alias.index);
    Ok(list)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_alias_with_no_enabled_field_is_enabled() {
        let list = aliases(&json!({"0": {"name": "postmaster", "domainId": "d"}})).unwrap();

        assert!(list[0].enabled);
    }

    #[test]
    fn the_aliases_come_back_in_numeric_key_order() {
        let list = aliases(&json!({"10": {"name": "b"}, "9": {"name": "a"}})).unwrap();

        assert_eq!(
            list.iter().map(|alias| alias.index).collect::<Vec<_>>(),
            [9, 10]
        );
    }
}
