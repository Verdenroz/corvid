//! The settings every Corvid service reads from one `config.yaml`.
//!
//! The file holds no secrets, so it can be shared, committed or mounted as a ConfigMap. Secrets
//! come from environment variables, read with [`secret`]. Each section is optional in the file
//! and required by the service that uses it, so one host's file can carry only what it runs.
//!
//! Unknown keys are refused at every level: a misspelt setting fails at startup rather than
//! silently taking its default.

use std::fmt;
use std::num::NonZeroUsize;
use std::path::{Path, PathBuf};

use serde::Deserialize;
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

pub const DEFAULT_PATH: &str = "/etc/corvid/config.yaml";

/// `EX_CONFIG`, the exit status for missing or malformed configuration.
pub const EX_CONFIG: u8 = 78;

/// `EX_TEMPFAIL`, the exit status for a dependency not up yet, which systemd retries.
pub const EX_TEMPFAIL: u8 = 75;

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub server: Option<Server>,
    pub labeler: Option<Labeler>,
    pub milter: Option<Milter>,
    pub imgproxy: Option<Imgproxy>,
    pub mirror: Option<Mirror>,
    pub sentinel: Option<Sentinel>,
}

/// The deployment itself, read by the deploy scripts through `corvid-config shell`.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Server {
    pub domain: Name,
    /// The name the server announces itself as. Its A record and the IP's PTR must agree.
    pub hostname: Name,
    /// Local part only. Stalwart appends the domain, so `alice@example.com` would silently
    /// become `alice@example.com@example.com`.
    pub mail_user: LocalPart,
    /// Let's Encrypt's contact, at an address outside this server.
    pub acme_contact: Name,
    /// Where the web tier answers. Clients rebase advertised URLs onto it, so 443 is optional.
    #[serde(default = "web_port")]
    pub web_port: WebPort,
    /// The zone `hostname` belongs to, when it lies outside `domain`. Stalwart keeps a certificate
    /// for it through the same DNS provider and publishes no records there.
    pub host_zone: Option<Name>,
    /// Where Pocket-ID answers, on the same `web_port`. Defaults to `idp.<domain>`; Pocket-ID
    /// refuses an `APP_URL` with a path, so it needs a hostname of its own.
    pub idp_hostname: Option<Name>,
    /// Who may reach the ports mail apps use: 465, 587, 993 and `web_port`.
    #[serde(default)]
    pub client_access: ClientAccess,
    /// Start in testing; switch to enforce once mail delivery looks right. Applies whether the
    /// policy is served by Stalwart on 443 or by a Cloudflare Worker elsewhere.
    #[serde(default)]
    pub mta_sts_mode: MtaStsMode,
}

/// Whether the MTA-STS policy tells senders to test or enforce TLS (RFC 8461).
#[derive(Debug, Clone, Copy, Default, PartialEq, Deserialize)]
#[serde(try_from = "String")]
pub enum MtaStsMode {
    #[default]
    Testing,
    Enforce,
}

impl MtaStsMode {
    fn as_str(self) -> &'static str {
        match self {
            Self::Testing => "testing",
            Self::Enforce => "enforce",
        }
    }
}

impl TryFrom<String> for MtaStsMode {
    type Error = String;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        match value.as_str() {
            "testing" => Ok(Self::Testing),
            "enforce" => Ok(Self::Enforce),
            _ => Err(format!("{value:?} is not testing or enforce")),
        }
    }
}

/// RFC 8461's `max_age`, in seconds: how long a sender may cache the policy.
const MTA_STS_MAX_AGE_SECS: u32 = 604_800;

/// Where mail apps may connect from. Ports 25 and 80 stay open to everyone whatever this says:
/// other mail servers deliver on 25, and Let's Encrypt checks the web certificate on 80.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(try_from = "ClientAccessValue")]
pub enum ClientAccess {
    #[default]
    Anywhere,
    /// Only programs on the server itself, such as a VPN whose traffic leaves through it.
    Host,
    Networks(Vec<Network>),
}

// Networks arrive as plain strings and are checked afterwards: an untagged enum reports a bad
// entry only as "did not match any variant", losing the reason.
#[derive(Deserialize)]
#[serde(untagged)]
enum ClientAccessValue {
    Word(String),
    Networks(Vec<String>),
}

impl TryFrom<ClientAccessValue> for ClientAccess {
    type Error = String;

    fn try_from(value: ClientAccessValue) -> Result<Self, Self::Error> {
        match value {
            ClientAccessValue::Word(word) if word == "anywhere" => Ok(Self::Anywhere),
            ClientAccessValue::Word(word) if word == "host" => Ok(Self::Host),
            ClientAccessValue::Word(word) => Err(format!(
                "{word:?} is not anywhere, host or a list of networks"
            )),
            ClientAccessValue::Networks(networks) if networks.is_empty() => {
                Err("an empty list admits nobody; write host for this server alone".to_owned())
            }
            ClientAccessValue::Networks(networks) => networks
                .into_iter()
                .map(Network::try_from)
                .collect::<Result<_, _>>()
                .map(Self::Networks),
        }
    }
}

impl ClientAccess {
    /// The firewall sources, space-separated: `any`, nothing for `host`, or each network.
    fn sources(&self) -> String {
        match self {
            Self::Anywhere => "any".to_owned(),
            Self::Host => String::new(),
            Self::Networks(networks) => networks
                .iter()
                .map(|network| network.0.as_str())
                .collect::<Vec<_>>()
                .join(" "),
        }
    }
}

/// An IPv4 address, or one with a prefix length such as `192.0.2.0/24`. The pod has IPv4 only,
/// so an IPv6 source could never match a rule for it.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(try_from = "String")]
pub struct Network(String);

impl TryFrom<String> for Network {
    type Error = String;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        let (address, prefix) = value.split_once('/').unwrap_or((&value, ""));
        if address.parse::<std::net::Ipv4Addr>().is_err() {
            return Err(format!("{value:?} is not an IPv4 address or network"));
        }
        match prefix {
            "" => Ok(Self(value)),
            _ if prefix.parse::<u8>().is_ok_and(|bits| bits <= 32) => Ok(Self(value)),
            _ => Err(format!("{value:?} has a prefix longer than 32 bits")),
        }
    }
}

/// Ports something inside the pod already listens on. The pod shares one network namespace,
/// so the web tier cannot take any of them.
pub const POD_PORTS: [u16; 12] = [
    25, 80, 465, 587, 993, 1411, 5335, 8080, 8081, 8443, 11332, 11333,
];

#[derive(Debug, Clone, Copy, PartialEq, Deserialize)]
#[serde(try_from = "u16")]
pub struct WebPort(u16);

impl TryFrom<u16> for WebPort {
    type Error = String;

    fn try_from(port: u16) -> Result<Self, Self::Error> {
        if port != 0 && !POD_PORTS.contains(&port) {
            Ok(Self(port))
        } else {
            Err(format!(
                "{port} is taken inside the pod; choose another web port"
            ))
        }
    }
}

impl Server {
    fn check_host_zone(&self) -> Result<(), Error> {
        let Some(zone) = &self.host_zone else {
            return Ok(());
        };
        if zone.0 == self.domain.0 {
            return Err(Error(format!(
                "server.host_zone {} is the mail domain; leave it unset",
                zone.0
            )));
        }
        if !self.hostname.0.ends_with(&format!(".{}", zone.0)) {
            return Err(Error(format!(
                "server.hostname {} is not within server.host_zone {}",
                self.hostname.0, zone.0
            )));
        }
        Ok(())
    }

    // Caddy needs two different site addresses; the same hostname on both would leave one
    // handler unreachable.
    fn check_idp_hostname(&self) -> Result<(), Error> {
        if self
            .idp_hostname
            .as_ref()
            .is_some_and(|idp| idp.0 == self.hostname.0)
        {
            return Err(Error(format!(
                "server.idp_hostname {} is server.hostname; give Pocket-ID a hostname of its own",
                self.hostname.0
            )));
        }
        Ok(())
    }

    pub fn web_url(&self) -> String {
        format!("https://{}:{}", self.hostname.0, self.web_port.0)
    }

    /// The MTA-STS policy file (RFC 8461), naming this server as the only MX.
    pub fn mta_sts_policy(&self) -> String {
        format!(
            "version: STSv1\nmode: {}\nmx: {}\nmax_age: {}\n",
            self.mta_sts_mode.as_str(),
            self.hostname.0,
            MTA_STS_MAX_AGE_SECS,
        )
    }

    /// An id for the `_mta-sts` TXT record that changes exactly when the policy does, so a
    /// sender caching the old one refetches it.
    pub fn mta_sts_id(&self) -> String {
        hex::encode(&Sha256::digest(self.mta_sts_policy().as_bytes())[..8])
    }
}

/// A value the deploy scripts put in DNS records, JSON and shell: letters, digits and `.-_@+`.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(try_from = "String")]
pub struct Name(String);

impl Name {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl TryFrom<String> for Name {
    type Error = String;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        let allowed = |c: char| c.is_ascii_alphanumeric() || ".-_@+".contains(c);
        if !value.is_empty() && value.chars().all(allowed) {
            Ok(Self(value))
        } else {
            Err(format!("{value:?} may only hold letters, digits and .-_@+"))
        }
    }
}

/// A [`Name`] without `@`.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(try_from = "String")]
pub struct LocalPart(String);

impl TryFrom<String> for LocalPart {
    type Error = String;

    fn try_from(value: String) -> Result<Self, Self::Error> {
        if value.contains('@') {
            return Err(format!(
                "{value:?} is an address; give the local part alone"
            ));
        }
        Name::try_from(value).map(|name| Self(name.0))
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Labeler {
    pub jmap_url: String,
    pub jmap_user: String,
    pub state: PathBuf,
    #[serde(default = "sixty")]
    pub interval_secs: u64,
    pub model: Option<Model>,
}

/// The optional cold-start model. Any OpenAI-compatible chat endpoint that returns logprobs.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Model {
    pub url: String,
    pub name: String,
    /// Percent a choice must reach before its label is written.
    #[serde(default = "eighty")]
    pub confidence: Percent,
    /// Messages per pass. Each costs one call per label, plus one.
    #[serde(default = "twenty")]
    pub budget: usize,
    #[serde(default = "three_hundred")]
    pub deadline_secs: u64,
    #[serde(default = "an_hour")]
    pub interval_secs: u64,
    /// Server-specific fields merged into every request, such as a thinking-mode switch.
    #[serde(default)]
    pub request_extra: Map<String, Value>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Milter {
    #[serde(default = "milter_bind")]
    pub bind: String,
    /// The origin rewritten image URLs point at, by default the server's web URL. The milter
    /// appends `/i/`, the path the proxy serves. Changing it breaks images in delivered mail.
    pub proxy_base: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Imgproxy {
    #[serde(default = "imgproxy_bind")]
    pub bind: String,
    #[serde(default = "five_mib")]
    pub max_bytes: usize,
    #[serde(default = "five_hundred_twelve")]
    pub cache_entries: NonZeroUsize,
}

impl Default for Imgproxy {
    fn default() -> Self {
        Self {
            bind: imgproxy_bind(),
            max_bytes: five_mib(),
            cache_entries: five_hundred_twelve(),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Mirror {
    pub jmap_url: String,
    pub jmap_user: String,
    pub root: PathBuf,
    /// An age recipient (public key) the nightly mirror encrypts to. The matching identity
    /// is never read here; it is needed only for `verify --deep` and `restore`.
    pub age_recipient: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Sentinel {
    #[serde(default = "sentinel_bind")]
    pub bind: String,
    pub jmap_url: String,
    pub jmap_user: String,
    pub state: PathBuf,
}

/// A whole-number percentage, 0 to 100.
#[derive(Debug, Clone, Copy, PartialEq, Deserialize)]
#[serde(try_from = "u8")]
pub struct Percent(u8);

impl Percent {
    pub fn fraction(self) -> f64 {
        f64::from(self.0) / 100.0
    }
}

impl TryFrom<u8> for Percent {
    type Error = String;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        if value <= 100 {
            Ok(Self(value))
        } else {
            Err(format!("{value} is not a percentage"))
        }
    }
}

#[derive(Debug)]
pub struct Error(String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Config {
    /// Reads the file named by `CORVID_CONFIG`, or [`DEFAULT_PATH`].
    pub fn load() -> Result<Self, Error> {
        let path = std::env::var("CORVID_CONFIG")
            .ok()
            .filter(|path| !path.is_empty())
            .unwrap_or_else(|| DEFAULT_PATH.to_owned());
        Self::read(Path::new(&path))
    }

    pub fn read(path: &Path) -> Result<Self, Error> {
        let text = std::fs::read_to_string(path)
            .map_err(|error| Error(format!("{}: {error}", path.display())))?;
        Self::parse(&text).map_err(|Error(error)| Error(format!("{}: {error}", path.display())))
    }

    pub fn parse(text: &str) -> Result<Self, Error> {
        let config: Self =
            serde_saphyr::from_str(text).map_err(|error| Error(error.to_string()))?;
        if let Some(server) = &config.server {
            server.check_host_zone()?;
            server.check_idp_hostname()?;
        }
        Ok(config)
    }
}

impl Config {
    /// The milter's proxy base, stated or derived from `server`.
    pub fn proxy_base(&self) -> Result<String, Error> {
        let stated = self.milter.as_ref().and_then(|m| m.proxy_base.clone());
        let derived = self.server.as_ref().map(Server::web_url);
        stated.or(derived).ok_or_else(|| {
            Error("milter.proxy_base is unset and there is no `server` section".to_owned())
        })
    }

    /// The `server` section as shell assignments, for the deploy scripts to `eval`.
    ///
    /// Two assignments are derived rather than configured. `PUBLISH_MTA_STS` is true only when
    /// the web tier holds 443, since senders fetch the MTA-STS policy there and nowhere else.
    /// `PUBLISH_TLSA` is true when the hostname is inside the mail domain; otherwise the
    /// `host_zone` domain publishes the TLSA records, because they live in the hostname's zone.
    pub fn shell(&self) -> Result<String, Error> {
        let server = section(self.server.as_ref(), "server")?;
        Ok(format!(
            "MAIL_DOMAIN={}\nMAIL_HOSTNAME={}\nMAIL_USER={}\nACME_CONTACT={}\nWEB_PORT={}\nHOST_ZONE={}\n\
             IDP_HOSTNAME={}\nPUBLISH_MTA_STS={}\nPUBLISH_TLSA={}\nMTA_STS_MODE={}\nMTA_STS_ID={}\n\
             CLIENT_SOURCES={}\n",
            server.domain.0,
            server.hostname.0,
            server.mail_user.0,
            server.acme_contact.0,
            server.web_port.0,
            server.host_zone.as_ref().map_or("", |zone| zone.as_str()),
            server
                .idp_hostname
                .as_ref()
                .map_or_else(|| format!("idp.{}", server.domain.0), |name| name.0.clone()),
            server.web_port.0 == 443,
            server.host_zone.is_none(),
            server.mta_sts_mode.as_str(),
            server.mta_sts_id(),
            server.client_access.sources()
        ))
    }

    /// The MTA-STS policy file the Cloudflare Worker serves, for `corvid-config mta-sts-policy`.
    pub fn mta_sts_policy(&self) -> Result<String, Error> {
        section(self.server.as_ref(), "server").map(|server| server.mta_sts_policy())
    }
}

/// Returns the section a service needs, or names the one that is missing.
pub fn section<T>(section: Option<T>, name: &str) -> Result<T, Error> {
    section.ok_or_else(|| Error(format!("config has no `{name}` section")))
}

/// Returns the non-empty value of a secret's environment variable.
pub fn secret(key: &str) -> Result<String, Error> {
    match std::env::var(key) {
        Ok(value) if !value.is_empty() => Ok(value),
        _ => Err(Error(format!("{key} is not set"))),
    }
}

/// Unwraps configuration, or prints the problem and exits with [`EX_CONFIG`].
pub fn or_exit<T>(result: Result<T, Error>) -> T {
    result.unwrap_or_else(|error| {
        eprintln!("configuration: {error}");
        std::process::exit(i32::from(EX_CONFIG));
    })
}

fn web_port() -> WebPort {
    WebPort(4443)
}
fn sixty() -> u64 {
    60
}
fn eighty() -> Percent {
    Percent(80)
}
fn twenty() -> usize {
    20
}
fn three_hundred() -> u64 {
    300
}
fn an_hour() -> u64 {
    3600
}
fn milter_bind() -> String {
    "127.0.0.1:11332".to_owned()
}
fn imgproxy_bind() -> String {
    "127.0.0.1:8081".to_owned()
}
fn sentinel_bind() -> String {
    "127.0.0.1:11333".to_owned()
}
fn five_mib() -> usize {
    5 * 1024 * 1024
}
fn five_hundred_twelve() -> NonZeroUsize {
    NonZeroUsize::new(512).unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    const EXAMPLE: &str = include_str!("../../../deploy/config.yaml");

    #[test]
    fn the_shipped_example_parses_with_every_section() {
        let config = Config::parse(EXAMPLE).unwrap();

        assert!(config.server.is_some());
        assert!(config.labeler.as_ref().unwrap().model.is_some());
        assert!(config.milter.is_some());
        assert!(config.imgproxy.is_some());
        assert!(config.mirror.is_some());
        assert!(config.sentinel.is_some());
    }

    #[test]
    fn a_missing_optional_setting_takes_its_default() {
        let config = Config::parse(
            "labeler:\n  jmap_url: http://x\n  jmap_user: u\n  state: /s\n  model:\n    url: http://m\n    name: n\n",
        )
        .unwrap();
        let labeler = config.labeler.unwrap();
        let model = labeler.model.unwrap();

        assert_eq!(labeler.interval_secs, 60);
        assert_eq!(model.confidence, Percent(80));
        assert_eq!(model.budget, 20);
        assert!(model.request_extra.is_empty());
    }

    #[test]
    fn a_sentinel_section_defaults_its_bind_address() {
        let config =
            Config::parse("sentinel:\n  jmap_url: http://x\n  jmap_user: u\n  state: /s\n")
                .unwrap();

        assert_eq!(config.sentinel.unwrap().bind, "127.0.0.1:11333");
    }

    #[test]
    fn a_misspelt_key_is_refused() {
        for text in [
            "labler: {}\n",
            "milter:\n  proxy_base: http://p\n  bnd: 0.0.0.0:1\n",
            "labeler:\n  jmap_url: x\n  jmap_user: u\n  state: /s\n  model:\n    url: u\n    name: n\n    confidnce: 90\n",
        ] {
            assert!(Config::parse(text).is_err(), "{text}");
        }
    }

    #[test]
    fn a_malformed_value_is_refused() {
        for text in [
            "imgproxy:\n  max_bytes: 5M\n",
            "imgproxy:\n  cache_entries: 0\n",
            "labeler:\n  jmap_url: x\n  jmap_user: u\n  state: /s\n  model:\n    url: u\n    name: n\n    confidence: 150\n",
            "labeler:\n  jmap_url: x\n  jmap_user: u\n  state: /s\n  model:\n    url: u\n    name: n\n    request_extra: [1, 2]\n",
        ] {
            assert!(Config::parse(text).is_err(), "{text}");
        }
    }

    #[test]
    fn a_section_a_service_needs_must_be_present() {
        let config = Config::parse("milter:\n  proxy_base: http://p\n").unwrap();

        let error = section(config.mirror, "mirror").unwrap_err();

        assert!(error.to_string().contains("`mirror`"));
    }

    #[test]
    fn request_extra_carries_nested_yaml_as_json() {
        let config = Config::parse(
            "labeler:\n  jmap_url: x\n  jmap_user: u\n  state: /s\n  model:\n    url: u\n    name: n\n    request_extra:\n      chat_template_kwargs:\n        enable_thinking: false\n",
        )
        .unwrap();
        let extra = config.labeler.unwrap().model.unwrap().request_extra;

        assert_eq!(extra["chat_template_kwargs"]["enable_thinking"], false);
    }

    #[test]
    fn an_unreadable_file_names_its_path() {
        let error = Config::read(Path::new("/nonexistent/config.yaml")).unwrap_err();

        assert!(error.to_string().contains("/nonexistent/config.yaml"));
    }
}

#[cfg(test)]
mod server_tests {
    use super::*;

    const SERVER: &str = "server:\n  domain: example.com\n  hostname: mail.example.com\n  mail_user: alice\n  acme_contact: you@elsewhere.example\n";

    #[test]
    fn the_server_section_becomes_shell_assignments() {
        let config = Config::parse(SERVER).unwrap();
        let id = config.server.as_ref().unwrap().mta_sts_id();

        assert_eq!(
            config.shell().unwrap(),
            format!(
                "MAIL_DOMAIN=example.com\nMAIL_HOSTNAME=mail.example.com\nMAIL_USER=alice\n\
                 ACME_CONTACT=you@elsewhere.example\nWEB_PORT=4443\nHOST_ZONE=\n\
                 IDP_HOSTNAME=idp.example.com\nPUBLISH_MTA_STS=false\nPUBLISH_TLSA=true\n\
                 MTA_STS_MODE=testing\nMTA_STS_ID={id}\nCLIENT_SOURCES=any\n"
            )
        );
    }

    #[test]
    fn idp_hostname_defaults_to_idp_dot_domain() {
        let shell = Config::parse(SERVER).unwrap().shell().unwrap();
        assert!(
            shell.lines().any(|l| l == "IDP_HOSTNAME=idp.example.com"),
            "{shell}"
        );
    }

    #[test]
    fn a_stated_idp_hostname_overrides_the_default() {
        let text = SERVER.to_owned() + "  idp_hostname: passkeys.example.com\n";
        let shell = Config::parse(&text).unwrap().shell().unwrap();
        assert!(
            shell
                .lines()
                .any(|l| l == "IDP_HOSTNAME=passkeys.example.com"),
            "{shell}"
        );
    }

    #[test]
    fn an_idp_hostname_with_bad_characters_is_refused() {
        let text = SERVER.to_owned() + "  idp_hostname: \"pass;keys.example.com\"\n";
        assert!(Config::parse(&text).is_err());
    }

    #[test]
    fn an_idp_hostname_equal_to_the_mail_hostname_is_refused() {
        let text = SERVER.to_owned() + "  idp_hostname: mail.example.com\n";
        let error = Config::parse(&text).unwrap_err();
        assert!(
            error
                .to_string()
                .contains("give Pocket-ID a hostname of its own"),
            "{error}"
        );
    }

    #[test]
    fn client_access_becomes_the_firewall_sources() {
        let shell = |access: &str| {
            Config::parse(&format!("{SERVER}  client_access: {access}\n"))
                .unwrap()
                .shell()
                .unwrap()
        };

        assert!(shell("anywhere").contains("\nCLIENT_SOURCES=any\n"));
        assert!(shell("host").contains("\nCLIENT_SOURCES=\n"));
        assert!(
            shell("[192.0.2.0/24, 198.51.100.7]")
                .contains("\nCLIENT_SOURCES=192.0.2.0/24 198.51.100.7\n")
        );
    }

    #[test]
    fn a_client_access_that_is_not_a_network_is_refused() {
        for access in [
            "everyone",
            "[]",
            "[192.0.2.0/33]",
            "[not-an-ip]",
            "[\"10.0.0.1; rm\"]",
        ] {
            let text = format!("{SERVER}  client_access: {access}\n");
            assert!(Config::parse(&text).is_err(), "{access} was accepted");
        }
    }

    #[test]
    fn mta_sts_is_published_only_when_the_web_tier_holds_443() {
        let off = Config::parse(SERVER).unwrap().shell().unwrap();
        assert!(off.lines().any(|l| l == "PUBLISH_MTA_STS=false"), "{off}");

        let on = Config::parse(&(SERVER.to_owned() + "  web_port: 443\n"))
            .unwrap()
            .shell()
            .unwrap();
        assert!(on.lines().any(|l| l == "PUBLISH_MTA_STS=true"), "{on}");
    }

    #[test]
    fn tlsa_moves_to_the_host_zone_when_the_hostname_is_outside_the_mail_domain() {
        let text = SERVER.replace("hostname: mail.example.com", "hostname: n1.relay.example")
            + "  host_zone: relay.example\n";
        let shell = Config::parse(&text).unwrap().shell().unwrap();

        assert!(shell.lines().any(|l| l == "PUBLISH_TLSA=false"), "{shell}");
    }

    #[test]
    fn mta_sts_mode_defaults_to_testing_and_switches_to_enforce() {
        let default = Config::parse(SERVER).unwrap().shell().unwrap();
        assert!(
            default.lines().any(|l| l == "MTA_STS_MODE=testing"),
            "{default}"
        );

        let enforced = Config::parse(&(SERVER.to_owned() + "  mta_sts_mode: enforce\n"))
            .unwrap()
            .shell()
            .unwrap();
        assert!(
            enforced.lines().any(|l| l == "MTA_STS_MODE=enforce"),
            "{enforced}"
        );
    }

    #[test]
    fn an_mta_sts_mode_that_is_not_testing_or_enforce_is_refused() {
        let text = format!("{SERVER}  mta_sts_mode: disable\n");
        assert!(Config::parse(&text).is_err());
    }

    #[test]
    fn the_mta_sts_policy_names_the_hostname_as_the_only_mx() {
        let config = Config::parse(SERVER).unwrap();

        assert_eq!(
            config.mta_sts_policy().unwrap(),
            "version: STSv1\nmode: testing\nmx: mail.example.com\nmax_age: 604800\n"
        );
    }

    #[test]
    fn the_mta_sts_id_is_stable_unless_mode_or_hostname_changes() {
        let id = |text: &str| Config::parse(text).unwrap().server.unwrap().mta_sts_id();

        let original = id(SERVER);
        assert_eq!(original, id(SERVER), "an unchanged policy keeps its id");

        let enforced = SERVER.to_owned() + "  mta_sts_mode: enforce\n";
        assert_ne!(original, id(&enforced), "a mode change must change the id");

        let renamed = SERVER.replace("mail.example.com", "mx.example.com");
        assert_ne!(
            original,
            id(&renamed),
            "a hostname change must change the id"
        );
    }

    #[test]
    fn a_value_that_could_break_out_of_the_shell_is_refused() {
        for hostname in [
            "mail.example.com; rm -rf /",
            "$(id)",
            "a b",
            "\"quoted\"",
            "",
        ] {
            let text = SERVER.replace("mail.example.com", &format!("{hostname:?}"));
            assert!(Config::parse(&text).is_err(), "{hostname}");
        }
    }

    #[test]
    fn the_proxy_base_derives_from_the_server_unless_stated() {
        let derived = Config::parse(&format!("{SERVER}  web_port: 9443\n")).unwrap();
        assert_eq!(
            derived.proxy_base().unwrap(),
            "https://mail.example.com:9443"
        );

        let stated = Config::parse(&format!(
            "{SERVER}milter:\n  proxy_base: https://img.example.com\n"
        ))
        .unwrap();
        assert_eq!(stated.proxy_base().unwrap(), "https://img.example.com");

        assert!(Config::parse("milter: {}\n").unwrap().proxy_base().is_err());
    }

    #[test]
    fn a_web_port_the_pod_already_uses_is_refused() {
        for port in POD_PORTS.iter().chain(&[0]) {
            let text = format!("{SERVER}  web_port: {port}\n");
            assert!(Config::parse(&text).is_err(), "{port}");
        }
        assert!(Config::parse(&format!("{SERVER}  web_port: 443\n")).is_ok());
    }

    #[test]
    fn every_port_the_pod_uses_is_reserved_from_the_web_tier() {
        let pod = include_str!("../../../deploy/corvid.pod");
        let example = include_str!("../../../deploy/config.yaml");
        let published = pod
            .lines()
            .filter_map(|l| l.strip_prefix("PublishPort="))
            .filter_map(|spec| {
                spec.rsplit(':')
                    .next()?
                    .split('/')
                    .next()?
                    .parse::<u16>()
                    .ok()
            });
        let bound = example
            .lines()
            .filter_map(|l| l.trim().strip_prefix("bind: "))
            .filter_map(|addr| addr.rsplit(':').next()?.parse::<u16>().ok());

        for port in published.chain(bound) {
            assert!(
                POD_PORTS.contains(&port),
                "{port} is used in the pod but not in POD_PORTS"
            );
        }
    }

    #[test]
    fn the_chart_reserves_the_same_ports() {
        let helpers = include_str!("../../../charts/corvid/templates/_helpers.tpl");
        let list = helpers
            .split("(list ")
            .nth(1)
            .unwrap()
            .split(')')
            .next()
            .unwrap();
        let chart: Vec<u16> = list
            .split_whitespace()
            .map(|p| p.parse().unwrap())
            .collect();

        assert_eq!(chart, POD_PORTS);
    }

    #[test]
    fn the_installer_reserves_the_same_ports() {
        let install = include_str!("../../../install.sh");
        let line = install
            .lines()
            .find(|l| l.starts_with("POD_PORTS=("))
            .unwrap();
        let list = &line["POD_PORTS=(".len()..line.len() - 1];
        let ports: Vec<u16> = list
            .split_whitespace()
            .map(|p| p.parse().unwrap())
            .collect();

        assert_eq!(ports, POD_PORTS);
    }

    #[test]
    fn the_chart_ships_the_deploy_caddyfile() {
        assert_eq!(
            include_str!("../../../charts/corvid/files/Caddyfile"),
            include_str!("../../../deploy/Caddyfile")
        );
    }

    #[test]
    fn every_binary_a_unit_runs_is_copied_into_the_image() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        let image = std::fs::read_to_string(root.join("deploy/Containerfile")).unwrap();
        let mut units: Vec<std::path::PathBuf> = std::fs::read_dir(root.join("deploy"))
            .unwrap()
            .map(|e| e.unwrap().path())
            .filter(|p| p.extension().is_some_and(|x| x == "container"))
            .collect();
        units.push(root.join("charts/corvid/templates/deployment.yaml"));

        for unit in units {
            let text = std::fs::read_to_string(&unit).unwrap();
            for (i, _) in text.match_indices("/usr/local/bin/") {
                let rest = &text[i..];
                let binary = &rest[..rest.find([' ', ',', ']', '\n']).unwrap()];
                let name = binary.rsplit('/').next().unwrap();
                assert!(
                    image.contains(&format!("/release/{name} /usr/local/bin/")),
                    "{} runs {binary}, which deploy/Containerfile does not copy",
                    unit.display()
                );
            }
        }
    }

    #[test]
    fn every_plan_variable_comes_from_the_server_section() {
        let shell = Config::parse(SERVER).unwrap().shell().unwrap();
        let supplied: Vec<&str> = shell.lines().filter_map(|l| l.split('=').next()).collect();
        let plans = [
            include_str!("../../../deploy/stalwart.plan.ndjson"),
            include_str!("../../../deploy/oidc.plan.ndjson"),
            include_str!("../../../deploy/stalwart.host-zone.ndjson"),
        ];

        for plan in plans {
            for (_, rest) in plan.match_indices("${").map(|(i, _)| plan.split_at(i + 2)) {
                let name = &rest[..rest.find('}').unwrap()];
                assert!(
                    supplied.contains(&name),
                    "{name} is in a plan but not in `shell`"
                );
            }
        }
    }

    #[test]
    fn a_host_zone_reaches_the_shell_and_is_empty_when_unset() {
        let without = Config::parse(SERVER).unwrap().shell().unwrap();
        assert!(without.lines().any(|l| l == "HOST_ZONE="), "{without}");

        let text = SERVER.replace("hostname: mail.example.com", "hostname: n1.relay.example")
            + "  host_zone: relay.example\n";
        let with = Config::parse(&text).unwrap().shell().unwrap();
        assert!(
            with.lines().any(|l| l == "HOST_ZONE=relay.example"),
            "{with}"
        );
    }

    #[test]
    fn a_host_zone_must_contain_the_hostname_and_differ_from_the_domain() {
        let outside = SERVER.to_owned() + "  host_zone: relay.example\n";
        let error = Config::parse(&outside).unwrap_err();
        assert!(error.to_string().contains("is not within"), "{error}");

        let same = SERVER.to_owned() + "  host_zone: example.com\n";
        let error = Config::parse(&same).unwrap_err();
        assert!(error.to_string().contains("is the mail domain"), "{error}");
    }

    #[test]
    fn a_mail_user_given_as_an_address_is_refused() {
        let text = SERVER.replace("mail_user: alice", "mail_user: alice@example.com");
        let error = Config::parse(&text).unwrap_err();

        assert!(error.to_string().contains("local part"), "{error}");
    }

    #[test]
    fn shell_output_needs_a_server_section() {
        let error = Config::parse("imgproxy: {}\n")
            .unwrap()
            .shell()
            .unwrap_err();
        assert!(error.to_string().contains("`server`"));
    }
}
