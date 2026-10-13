use std::collections::BTreeMap;
use std::io;
use std::path::Path;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use p256::ecdsa::VerifyingKey;
use serde::{Deserialize, Serialize};

use super::legacy;
use super::token::Audience;

/// Bump whenever `Auth`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 2;

pub const CODE_LIFETIME_SECONDS: i64 = 24 * 3600;
pub const REFRESH_IDLE_SECONDS: i64 = 24 * 3600;
pub const REFRESH_TOTAL_SECONDS: i64 = 7 * 24 * 3600;
pub const REFRESH_GRACE_SECONDS: i64 = 60;

/// What a sign-in is for: mail, or approving devices and passkey changes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Purpose {
    SignIn,
    Approve,
}

impl Purpose {
    pub fn audience(self) -> Audience {
        match self {
            Self::SignIn => Audience::Daily,
            Self::Approve => Audience::Master,
        }
    }
}

/// A registered passkey. `public_key` is the uncompressed SEC1 point, base64url.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Credential {
    pub public_key: String,
    pub sign_count: u32,
    pub backup_eligible: bool,
    /// Whether this key can also approve devices and change passkeys.
    pub approves: bool,
    pub created_at: i64,
}

/// SHA-256 of an enrollment code, hex. The code itself is shown once and never stored.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Code {
    pub hash: String,
    pub expires_at: i64,
    /// Whether the key this code enrolls approves.
    pub approves: bool,
}

/// One sign-in's chain of refresh tokens, minted by `credential`. `current` is the hash of the
/// token that works; `previous` is the one it replaced, which still works for a short grace
/// after `rotated_at` so a lost response can be retried. Any other member revokes the family.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Family {
    pub credential: String,
    pub current: String,
    pub previous: Option<String>,
    pub rotated_at: i64,
    pub created_at: i64,
    pub last_used: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Auth {
    /// The opaque id every credential is registered under, made with the first one.
    pub user: Option<String>,
    /// By credential id, base64url.
    pub credentials: BTreeMap<String, Credential>,
    pub codes: Vec<Code>,
    /// By family id.
    pub families: BTreeMap<String, Family>,
}

corvid_store::pin_shape!(Credential {
    public_key,
    sign_count,
    backup_eligible,
    approves,
    created_at
});
corvid_store::pin_shape!(Code {
    hash,
    expires_at,
    approves
});
corvid_store::pin_shape!(Family {
    credential,
    current,
    previous,
    rotated_at,
    created_at,
    last_used
});
corvid_store::pin_shape!(Auth {
    user,
    credentials,
    codes,
    families
});

impl Credential {
    pub fn key(&self) -> VerifyingKey {
        let bytes = URL_SAFE_NO_PAD
            .decode(&self.public_key)
            .expect("a stored key is base64url");
        VerifyingKey::from_sec1_bytes(&bytes).expect("a stored key is a P-256 point")
    }
}

impl Code {
    pub fn is_live(&self, now: i64) -> bool {
        self.expires_at > now
    }
}

impl Family {
    /// Whether `hash` is the token this family replaced, presented inside the grace.
    pub fn is_retry(&self, hash: &str, now: i64) -> bool {
        self.previous.as_deref() == Some(hash) && now - self.rotated_at < REFRESH_GRACE_SECONDS
    }

    pub fn is_live(&self, now: i64) -> bool {
        now - self.last_used < REFRESH_IDLE_SECONDS && now - self.created_at < REFRESH_TOTAL_SECONDS
    }
}

impl Auth {
    /// The ids a `purpose` sign-in may answer with.
    pub fn credential_ids(&self, purpose: Purpose) -> Vec<&String> {
        self.credentials
            .iter()
            .filter(|(_, credential)| purpose == Purpose::SignIn || credential.approves)
            .map(|(id, _)| id)
            .collect()
    }

    pub fn approvers(&self) -> usize {
        self.credentials.values().filter(|c| c.approves).count()
    }

    /// While nobody can approve, every new key approves, so the first key on a server is one.
    pub fn grants_approval(&self, requested: bool) -> bool {
        requested || self.approvers() == 0
    }

    pub fn live_codes(&self, now: i64) -> impl Iterator<Item = &Code> {
        self.codes.iter().filter(move |code| code.is_live(now))
    }

    pub fn live_code(&self, hash: &str, now: i64) -> Option<&Code> {
        self.live_codes(now).find(|code| code.hash == hash)
    }
}

pub fn open(path: &Path) -> io::Result<Auth> {
    corvid_store::open_migrating(path, SCHEMA, legacy::SCHEMA, legacy::migrate)
}

pub fn save(path: &Path, auth: &Auth) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, auth)
}
