//! Per-sender aliases on the mail account: minted as `label.tag`, listed, and disabled or
//! re-enabled, never deleted, since deleting an alias moves every later one to a new key.

pub mod stalwart;
pub mod state;

use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use axum::Json;
use axum::Router;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::auth::{Issuer, daily_claims, require_master};
use crate::dates;
use crate::http::{Off, off_json};
use crate::persisted::Persisted;
use crate::webpush::random_bytes;
use stalwart::{Alias, Aliases, Error, Target};
use state::{Entry, Ledger, save};

const LABEL_LENGTH: std::ops::RangeInclusive<usize> = 1..=20;
const TAG_LENGTH: usize = 8;
const BASE32: &[u8; 32] = b"abcdefghijklmnopqrstuvwxyz234567";
const REQUEST_BODY_LIMIT: usize = 256;
const MINT_ATTEMPTS: usize = 3;
/// `Identity/get` returns at most 500 identities, newest first, and each enabled alias is one,
/// so past this the main address would drop out of the app's list.
const MAX_ENABLED: usize = 400;
const MAX_MINTED: usize = 1000;
const MAX_PER_HOUR: usize = 20;
/// The plan's entries at keys 0 and 1, which nothing here may move.
const RESERVED: [&str; 2] = ["postmaster", "abuse"];

pub struct Config {
    pub state_path: PathBuf,
    pub ledger: Ledger,
    pub stalwart_url: String,
    pub stalwart_token: String,
    pub mail_address: String,
    pub admin_user: String,
    pub issuer: Arc<Issuer>,
    pub timeout: Duration,
}

struct AliasState {
    ledger: Persisted<Ledger>,
    stalwart: Aliases,
    issuer: Arc<Issuer>,
    mail_user: String,
    domain: String,
    admin_user: String,
    /// Every write reads the list, patches at a position from that read, and reads again. Stalwart
    /// offers no compare-and-swap, so two writes at once would land on the same key.
    writes: tokio::sync::Mutex<()>,
}

type Shared = Arc<AliasState>;

pub fn routes(config: Config) -> Router {
    let (mail_user, domain) = config
        .mail_address
        .split_once('@')
        .map(|(user, domain)| (user.to_owned(), domain.to_owned()))
        .unwrap_or_default();
    let state = Arc::new(AliasState {
        ledger: Persisted::new(config.state_path, config.ledger, save),
        stalwart: Aliases::new(
            config.stalwart_url,
            config.stalwart_token,
            config.mail_address,
            config.timeout,
        ),
        issuer: config.issuer,
        mail_user,
        domain,
        admin_user: config.admin_user,
        writes: tokio::sync::Mutex::new(()),
    });
    Router::new()
        .route("/corvid/aliases", get(list).post(create))
        .route("/corvid/aliases/{name}/disable", post(disable))
        .route("/corvid/aliases/{name}/enable", post(enable))
        .layer(DefaultBodyLimit::max(REQUEST_BODY_LIMIT))
        .with_state(state)
}

/// The alias routes on a host without both `sentinel.devices` and `sentinel.auth`.
pub fn off() -> Router {
    async fn off() -> (StatusCode, Json<Off>) {
        off_json("aliases_off")
    }
    Router::new()
        .route("/corvid/aliases", get(off).post(off))
        .route("/corvid/aliases/{name}/disable", post(off))
        .route("/corvid/aliases/{name}/enable", post(off))
}

/// Caddy admits any credential Stalwart accepts, app passwords included, so the routes check for
/// a passkey session of the mail user themselves. The 401 carries no `WWW-Authenticate`, which
/// would sign the app out.
fn require_daily(state: &AliasState, headers: &HeaderMap) -> Result<(), StatusCode> {
    if daily_claims(&state.issuer, headers)?.preferred_username != state.mail_user {
        return Err(StatusCode::UNAUTHORIZED);
    }
    Ok(())
}

fn gateway(error: &Error) -> StatusCode {
    tracing::warn!(%error, "an alias change did not reach Stalwart");
    match error {
        Error::Busy => StatusCode::SERVICE_UNAVAILABLE,
        Error::Refused(_) | Error::Failed(_) => StatusCode::BAD_GATEWAY,
    }
}

async fn read(state: &AliasState) -> Result<(Target, Vec<Alias>), StatusCode> {
    state.stalwart.read().await.map_err(|error| gateway(&error))
}

/// Reads the list a write will patch, refusing one whose keys have a gap or whose reserved
/// entries have moved, since a position from it would no longer mean what it did.
async fn read_for_write(state: &AliasState) -> Result<(Target, Vec<Alias>), StatusCode> {
    let (target, aliases) = read(state).await?;
    let contiguous = aliases
        .iter()
        .enumerate()
        .all(|(position, alias)| alias.index == position);
    let reserved = RESERVED
        .iter()
        .enumerate()
        .all(|(index, name)| aliases.get(index).is_some_and(|alias| alias.name == *name));
    if !contiguous || !reserved {
        tracing::warn!(
            "the alias keys are not 0, 1, 2 with postmaster and abuse first; refusing to patch"
        );
        return Err(StatusCode::BAD_GATEWAY);
    }
    Ok((target, aliases))
}

/// Distinct minted names, and those with any enabled entry.
fn counts(aliases: &[Alias]) -> (usize, usize) {
    let mut names: BTreeMap<&str, bool> = BTreeMap::new();
    for alias in aliases.iter().filter(|alias| minted(&alias.name)) {
        *names.entry(&alias.name).or_default() |= alias.enabled;
    }
    (
        names.len(),
        names.values().filter(|&&enabled| enabled).count(),
    )
}

async fn commit<T: Send + 'static>(
    state: &Shared,
    change: impl FnOnce(&Ledger) -> (Option<Ledger>, T) + Send + 'static,
) -> Result<T, StatusCode> {
    Persisted::commit_blocking(state.clone(), |state| &state.ledger, "alias", change).await
}

async fn edit(
    state: &Shared,
    change: impl FnOnce(&mut BTreeMap<String, Entry>) + Send + 'static,
) -> Result<(), StatusCode> {
    commit(state, move |ledger| {
        let mut next = ledger.clone();
        change(&mut next.aliases);
        (Some(next), ())
    })
    .await
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct View {
    name: String,
    address: String,
    label: String,
    enabled: bool,
    created_at: Option<String>,
    missing: bool,
}

impl AliasState {
    fn view(&self, name: &str, entry: Option<&Entry>, enabled: bool, missing: bool) -> View {
        View {
            name: name.to_owned(),
            address: format!("{name}@{}", self.domain),
            label: name
                .rsplit_once('.')
                .map_or(name, |(label, _)| label)
                .to_owned(),
            enabled,
            created_at: entry.map(|entry| dates::rfc3339(entry.created_at)),
            missing,
        }
    }
}

/// Minted aliases in the order Stalwart holds them, then any the ledger has that Stalwart lacks.
async fn list(
    State(state): State<Shared>,
    headers: HeaderMap,
) -> Result<Json<Vec<View>>, StatusCode> {
    require_daily(&state, &headers)?;
    let (_, aliases) = read(&state).await?;
    let views = state.ledger.read(|ledger| {
        let mut seen = BTreeSet::new();
        let mut views: Vec<View> = aliases
            .iter()
            .filter(|alias| minted(&alias.name) && seen.insert(alias.name.as_str()))
            .map(|alias| {
                let entry = ledger.aliases.get(&alias.name);
                state.view(&alias.name, entry, holds(&aliases, &alias.name), false)
            })
            .collect();
        views.extend(
            ledger
                .aliases
                .iter()
                .filter(|(name, _)| !seen.contains(name.as_str()))
                .map(|(name, entry)| state.view(name, Some(entry), false, true)),
        );
        views
    });
    Ok(Json(views))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct NewAlias {
    label: String,
}

async fn create(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(new): Json<NewAlias>,
) -> Result<(StatusCode, Json<View>), StatusCode> {
    require_daily(&state, &headers)?;
    let label = label(&new.label).ok_or(StatusCode::BAD_REQUEST)?;
    settled(mint_alias(state, label)).await
}

/// Runs a write to the end on its own task, so a caller that disconnects cannot leave it half done.
async fn settled<T: Send + 'static>(
    write: impl Future<Output = Result<T, StatusCode>> + Send + 'static,
) -> Result<T, StatusCode> {
    tokio::spawn(write).await.expect("an alias write panicked")
}

fn holds(aliases: &[Alias], name: &str) -> bool {
    aliases
        .iter()
        .any(|alias| alias.name == name && alias.enabled)
}

async fn forget(state: &Shared, name: String) -> Result<(), StatusCode> {
    edit(state, move |aliases| {
        aliases.remove(&name);
    })
    .await
}

async fn mint_alias(state: Shared, label: String) -> Result<(StatusCode, Json<View>), StatusCode> {
    let _write = state.writes.lock().await;
    let now = dates::now_secs();
    if state.ledger.read(|ledger| ledger.created_since(now - 3600)) >= MAX_PER_HOUR {
        return Err(StatusCode::TOO_MANY_REQUESTS);
    }
    let (target, aliases) = read_for_write(&state).await?;
    let (all, enabled) = counts(&aliases);
    if all >= MAX_MINTED || enabled >= MAX_ENABLED {
        return Err(StatusCode::TOO_MANY_REQUESTS);
    }
    let mut refusal = None;
    for _ in 0..MINT_ATTEMPTS {
        let name = mint(&label);
        if aliases.iter().any(|alias| alias.name == name) {
            continue;
        }
        let entry = Entry {
            created_at: now,
            disabled_at: None,
        };
        let intent = name.clone();
        edit(&state, move |aliases| {
            aliases.insert(intent, entry);
        })
        .await?;
        let patch: Map<String, Value> = [(
            format!("aliases/{}", aliases.len()),
            json!({"name": name, "domainId": target.domain}),
        )]
        .into_iter()
        .collect();
        let created = (
            StatusCode::CREATED,
            Json(state.view(&name, Some(&entry), true, false)),
        );
        match state.stalwart.patch(&target, patch).await {
            // A failed read-back here would send the app to create a second alias, so it gets a retry.
            Ok(()) => {
                let after = match state.stalwart.read().await {
                    Ok((_, after)) => after,
                    Err(_) => read(&state).await?.1,
                };
                if !holds(&after, &name) {
                    tracing::warn!(name, "a created alias is not on the account");
                    return Err(StatusCode::BAD_GATEWAY);
                }
                return Ok(created);
            }
            Err(Error::Refused(reason)) => {
                tracing::info!(
                    name,
                    reason,
                    "Stalwart refused a minted name; minting another"
                );
                forget(&state, name).await?;
                refusal = Some(reason);
            }
            // A patch that timed out may still have landed, so one read decides which it was.
            Err(error) => {
                let (_, after) = read(&state).await?;
                if holds(&after, &name) {
                    return Ok(created);
                }
                forget(&state, name).await?;
                return Err(gateway(&error));
            }
        }
    }
    tracing::warn!(
        refusal = refusal.as_deref().unwrap_or("none"),
        "no minted name was taken after {MINT_ATTEMPTS} attempts"
    );
    Err(StatusCode::BAD_GATEWAY)
}

async fn disable(
    State(state): State<Shared>,
    Path(name): Path<String>,
    headers: HeaderMap,
) -> Result<Json<View>, StatusCode> {
    set_enabled(state, name, headers, false).await
}

async fn enable(
    State(state): State<Shared>,
    Path(name): Path<String>,
    headers: HeaderMap,
) -> Result<Json<View>, StatusCode> {
    set_enabled(state, name, headers, true).await
}

async fn set_enabled(
    state: Shared,
    name: String,
    headers: HeaderMap,
    enabled: bool,
) -> Result<Json<View>, StatusCode> {
    require_daily(&state, &headers)?;
    if !minted(&name) {
        return Err(StatusCode::NOT_FOUND);
    }
    settled(switch(state, name, headers, enabled)).await
}

/// Sets `enabled` on every entry holding `name`, since an admin may have added it twice, and reads
/// back to confirm each one took it. The admin token is spent only once the change can be made.
async fn switch(
    state: Shared,
    name: String,
    headers: HeaderMap,
    enabled: bool,
) -> Result<Json<View>, StatusCode> {
    let _write = state.writes.lock().await;
    let (target, aliases) = read_for_write(&state).await?;
    let held: Vec<&Alias> = aliases.iter().filter(|alias| alias.name == name).collect();
    if held.is_empty() {
        return Err(StatusCode::NOT_FOUND);
    }
    let was_enabled = held.iter().any(|alias| alias.enabled);
    if enabled && !was_enabled && counts(&aliases).1 >= MAX_ENABLED {
        return Err(StatusCode::TOO_MANY_REQUESTS);
    }
    require_master(&state.issuer, &headers, &state.admin_user)?;
    let patch: Map<String, Value> = held
        .iter()
        .map(|alias| (format!("aliases/{}/enabled", alias.index), json!(enabled)))
        .collect();
    let patched = state.stalwart.patch(&target, patch).await;
    let (_, after) = read(&state).await?;
    let entries: Vec<&Alias> = after.iter().filter(|alias| alias.name == name).collect();
    let took = !entries.is_empty() && entries.iter().all(|alias| alias.enabled == enabled);
    if !took {
        return Err(match patched {
            Err(error) => gateway(&error),
            Ok(()) => {
                tracing::warn!(name, enabled, "an alias entry did not take the change");
                StatusCode::BAD_GATEWAY
            }
        });
    }
    let now = dates::now_secs();
    let changed = name.clone();
    let entry = commit(&state, move |ledger| {
        let Some(entry) = ledger.aliases.get(&changed) else {
            return (None, None);
        };
        let mut next = ledger.clone();
        let entry = Entry {
            disabled_at: (!enabled).then_some(entry.disabled_at.unwrap_or(now)),
            ..*entry
        };
        next.aliases.insert(changed, entry);
        (Some(next), Some(entry))
    })
    .await?;
    Ok(Json(state.view(&name, entry.as_ref(), enabled, false)))
}

/// The label a caller asked for, lowercased, or `None` if anything in it is outside `[a-z0-9-]`.
pub fn label(raw: &str) -> Option<String> {
    let label = raw.to_ascii_lowercase();
    (LABEL_LENGTH.contains(&label.len()) && label.bytes().all(label_byte)).then_some(label)
}

/// A fresh `label.tag` name. Eight base32 characters are 40 bits, too many to find by probing RCPT.
pub fn mint(label: &str) -> String {
    let bits = random_bytes::<5>()
        .iter()
        .fold(0_u64, |bits, &byte| bits << 8 | u64::from(byte));
    let tag: String = (0..TAG_LENGTH)
        .rev()
        .map(|digit| char::from(BASE32[(bits >> (5 * digit) & 31) as usize]))
        .collect();
    format!("{label}.{tag}")
}

/// Whether `name` has the minted form. Nothing else, postmaster and abuse included, can be
/// disabled or enabled through the sentinel.
pub fn minted(name: &str) -> bool {
    let Some((label, tag)) = name.rsplit_once('.') else {
        return false;
    };
    LABEL_LENGTH.contains(&label.len())
        && label.bytes().all(label_byte)
        && tag.len() == TAG_LENGTH
        && tag.bytes().all(|byte| BASE32.contains(&byte))
}

fn label_byte(byte: u8) -> bool {
    byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-'
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_label_is_lowercased() {
        assert_eq!(label("Shop-2"), Some("shop-2".to_owned()));
    }

    #[test]
    fn a_label_with_anything_outside_letters_digits_and_hyphens_is_refused() {
        for raw in [
            "shop.x",
            "shop/0",
            "a b",
            "",
            "ünïcode",
            "x\"y",
            "aliases/2",
        ] {
            assert_eq!(label(raw), None, "{raw}");
        }
    }

    #[test]
    fn a_label_longer_than_twenty_characters_is_refused() {
        assert_eq!(label(&"a".repeat(21)), None);
        assert!(label(&"a".repeat(20)).is_some());
    }

    #[test]
    fn a_minted_name_is_the_label_and_eight_base32_characters() {
        let name = mint("shop");

        assert!(minted(&name), "{name}");
        assert_eq!(name.len(), "shop.".len() + 8);
    }

    #[test]
    fn two_mints_of_one_label_differ() {
        assert_ne!(mint("shop"), mint("shop"));
    }

    #[test]
    fn names_outside_the_minted_form_are_not_minted() {
        for name in [
            "postmaster",
            "abuse",
            "mailuser",
            "shop.abcdefg",
            "shop.abcdefghi",
            "shop.abcdefg1",
            "shop.ABCDEFGH",
            ".abcdefgh",
            "a.b.abcdefgh",
            &format!("{}.abcdefgh", "a".repeat(21)),
        ] {
            assert!(!minted(name), "{name}");
        }
    }
}
