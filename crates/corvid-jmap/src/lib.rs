//! Session discovery and one method call, shared by everything here that speaks JMAP.
//!
//! **It owns the transport; each consumer owns its own method arguments and response shapes.**
//! Nothing here knows what an `Email` is, and a caller that adds a method still decides for
//! itself what a short or capped response means.

use std::io;

use reqwest::blocking::Client as Http;
use serde_json::{Value, json};

const CORE: &str = "urn:ietf:params:jmap:core";
const MAIL: &str = "urn:ietf:params:jmap:mail";

pub struct Client {
    http: Http,
    api_url: String,
    download_url: String,
    upload_url: String,
    account_id: String,
    user: String,
    password: String,
}

impl Client {
    /// Opens a session against `base_url`, and keeps talking to it.
    ///
    /// The session's advertised URLs are rebased onto `base_url` rather than used as given.
    /// Stalwart builds them from its configured hostname, always `https` and with no port, so
    /// they are wrong for a caller on loopback or a web tier not on 443, whatever `base_url` is.
    ///
    /// This deviates from RFC 8620, which says a client uses the session's URLs: only the path
    /// is taken from the server, and the host is the one the caller dialled.
    pub fn connect(base_url: &str, user: &str, password: &str) -> io::Result<Self> {
        let http = Http::builder().build().map_err(io::Error::other)?;
        let session: Value = http
            .get(format!("{base_url}/.well-known/jmap"))
            .basic_auth(user, Some(password))
            .send()
            .and_then(|response| response.error_for_status())
            .and_then(|response| response.json())
            .map_err(io::Error::other)?;

        Ok(Self {
            http,
            api_url: rebase(
                base_url,
                &text(&session["apiUrl"]).ok_or_else(|| missing("apiUrl"))?,
            ),
            download_url: rebase(
                base_url,
                &text(&session["downloadUrl"]).ok_or_else(|| missing("downloadUrl"))?,
            ),
            upload_url: rebase(
                base_url,
                &text(&session["uploadUrl"]).ok_or_else(|| missing("uploadUrl"))?,
            ),
            account_id: text(&session["primaryAccounts"][MAIL])
                .ok_or_else(|| missing("a primary mail account"))?,
            user: user.to_owned(),
            password: password.to_owned(),
        })
    }

    pub fn account_id(&self) -> &str {
        &self.account_id
    }

    pub fn api_url(&self) -> &str {
        &self.api_url
    }

    pub fn user(&self) -> &str {
        &self.user
    }

    pub fn call(&self, name: &str, arguments: Value) -> io::Result<Value> {
        let request = json!({
            "using": [CORE, MAIL],
            "methodCalls": [[name, arguments, "c0"]],
        });
        let mut response: Value = self
            .http
            .post(&self.api_url)
            .basic_auth(&self.user, Some(&self.password))
            .json(&request)
            .send()
            .and_then(|response| response.error_for_status())
            .and_then(|response| response.json())
            .map_err(io::Error::other)?;

        let call = response["methodResponses"][0].take();
        match text(&call[0]).as_deref() {
            Some("error") => Err(io::Error::other(format!("{name}: {}", call[1]))),
            _ => Ok(call[1].clone()),
        }
    }

    /// Fetches a blob's bytes. Not a method call: blobs come over plain HTTP from a template
    /// the session advertises.
    pub fn download(&self, blob_id: &str) -> io::Result<Vec<u8>> {
        let url = self
            .download_url
            .replace("{accountId}", &self.account_id)
            .replace("{blobId}", blob_id)
            .replace("{type}", "application/octet-stream")
            .replace("{name}", "message");
        let body = self
            .http
            .get(url)
            .basic_auth(&self.user, Some(&self.password))
            .send()
            .and_then(|response| response.error_for_status())
            .and_then(|response| response.bytes())
            .map_err(io::Error::other)?;
        Ok(body.to_vec())
    }

    /// Uploads a blob and returns its id, for a call such as `Email/import` that takes one
    /// rather than raw content. Also plain HTTP, like [`Client::download`].
    pub fn upload(&self, content_type: &str, bytes: Vec<u8>) -> io::Result<String> {
        let url = self.upload_url.replace("{accountId}", &self.account_id);
        let response: Value = self
            .http
            .post(url)
            .basic_auth(&self.user, Some(&self.password))
            .header("Content-Type", content_type)
            .body(bytes)
            .send()
            .and_then(|response| response.error_for_status())
            .and_then(|response| response.json())
            .map_err(io::Error::other)?;
        text(&response["blobId"]).ok_or_else(|| missing("a blobId"))
    }
}

/// Moves an advertised URL onto the host that was actually dialled, keeping its path.
///
/// A string operation rather than a URL parse, because `downloadUrl` is a template carrying
/// `{accountId}` and friends, and those braces do not survive a round trip through a parser.
fn rebase(base: &str, advertised: &str) -> String {
    let base = base.trim_end_matches('/');
    let path = match advertised.find("://") {
        Some(scheme) => match advertised[scheme + 3..].find('/') {
            Some(slash) => &advertised[scheme + 3 + slash..],
            None => "/",
        },
        None => advertised,
    };
    format!("{base}{path}")
}

pub fn text(value: &Value) -> Option<String> {
    value.as_str().map(str::to_owned)
}

pub fn ids(value: &Value) -> Vec<String> {
    value
        .as_array()
        .map(|list| list.iter().filter_map(text).collect())
        .unwrap_or_default()
}

pub fn missing(what: &str) -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        format!("the server did not return {what}"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_advertised_url_moves_onto_the_host_that_was_dialled() {
        assert_eq!(
            rebase("http://127.0.0.1:8080", "https://mail.example.test/jmap/"),
            "http://127.0.0.1:8080/jmap/"
        );
    }

    #[test]
    fn a_template_keeps_its_placeholders() {
        let advertised =
            "https://mail.example.test/jmap/download/{accountId}/{blobId}/{name}?accept={type}";

        assert_eq!(
            rebase("http://127.0.0.1:8080", advertised),
            "http://127.0.0.1:8080/jmap/download/{accountId}/{blobId}/{name}?accept={type}"
        );
    }

    #[test]
    fn a_port_in_either_place_is_handled() {
        assert_eq!(
            rebase(
                "https://mail.example.test:4443",
                "https://mail.example.test/jmap/"
            ),
            "https://mail.example.test:4443/jmap/"
        );
        assert_eq!(
            rebase(
                "http://127.0.0.1:8080",
                "https://mail.example.test:443/jmap/"
            ),
            "http://127.0.0.1:8080/jmap/"
        );
    }

    #[test]
    fn a_trailing_slash_on_the_base_does_not_double() {
        assert_eq!(
            rebase("http://127.0.0.1:8080/", "https://mail.example.test/jmap/"),
            "http://127.0.0.1:8080/jmap/"
        );
    }

    #[test]
    fn a_bare_authority_becomes_the_root() {
        assert_eq!(
            rebase("http://127.0.0.1:8080", "https://mail.example.test"),
            "http://127.0.0.1:8080/"
        );
    }

    #[test]
    fn a_relative_url_is_joined_as_a_path() {
        assert_eq!(
            rebase("http://127.0.0.1:8080", "/jmap/"),
            "http://127.0.0.1:8080/jmap/"
        );
    }
}
