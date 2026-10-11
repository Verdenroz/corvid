use std::collections::BTreeMap;
use std::io;
use std::path::Path;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use p256::ecdsa::VerifyingKey;
use serde::{Deserialize, Serialize};

use super::token::Audience;
use super::webauthn::Policy;

/// Bump whenever `Auth`'s shape changes, and keep a migration from the version it leaves.
const SCHEMA: u32 = 1;

pub const CODE_LIFETIME_SECONDS: i64 = 24 * 3600;
pub const REFRESH_IDLE_SECONDS: i64 = 24 * 3600;
pub const REFRESH_TOTAL_SECONDS: i64 = 7 * 24 * 3600;
pub const REFRESH_GRACE_SECONDS: i64 = 60;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    Daily,
    Master,
}

impl Role {
    pub fn name(self) -> &'static str {
        match self {
            Self::Daily => "daily",
            Self::Master => "master",
        }
    }

    pub fn policy(self) -> Policy {
        match self {
            Self::Daily => Policy::Synced,
            Self::Master => Policy::DeviceBound,
        }
    }

    pub fn audience(self) -> Audience {
        match self {
            Self::Daily => Audience::Daily,
            Self::Master => Audience::Master,
        }
    }
}

/// A registered passkey. `public_key` is the uncompressed SEC1 point, base64url.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Credential {
    pub user: String,
    pub public_key: String,
    pub sign_count: u32,
    pub backup_eligible: bool,
    pub created_at: i64,
}

/// SHA-256 of an enrollment code, hex. The code itself is shown once and never stored.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Code {
    pub hash: String,
    pub expires_at: i64,
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
    /// By handle, the opaque id a credential is registered under.
    pub users: BTreeMap<String, Role>,
    /// By credential id, base64url.
    pub credentials: BTreeMap<String, Credential>,
    pub codes: BTreeMap<Role, Code>,
    /// By family id.
    pub families: BTreeMap<String, Family>,
}

corvid_store::pin_shape!(Credential {
    user,
    public_key,
    sign_count,
    backup_eligible,
    created_at
});
corvid_store::pin_shape!(Code { hash, expires_at });
corvid_store::pin_shape!(Family {
    credential,
    current,
    previous,
    rotated_at,
    created_at,
    last_used
});
corvid_store::pin_shape!(Auth {
    users,
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
    pub fn handle_of(&self, role: Role) -> Option<&String> {
        self.users
            .iter()
            .find(|(_, user)| **user == role)
            .map(|(handle, _)| handle)
    }

    pub fn role_of(&self, credential: &Credential) -> Role {
        self.users[&credential.user]
    }

    pub fn credential_ids(&self, role: Role) -> Vec<&String> {
        self.credentials
            .iter()
            .filter(|(_, credential)| self.role_of(credential) == role)
            .map(|(id, _)| id)
            .collect()
    }

    pub fn live_codes(&self, now: i64) -> impl Iterator<Item = (Role, &Code)> {
        self.codes
            .iter()
            .filter(move |(_, code)| code.expires_at > now)
            .map(|(role, code)| (*role, code))
    }

    pub fn code_role(&self, hash: &str, now: i64) -> Option<Role> {
        self.live_codes(now)
            .find(|(_, code)| code.hash == hash)
            .map(|(role, _)| role)
    }
}

pub fn open(path: &Path) -> io::Result<Auth> {
    corvid_store::open(path, SCHEMA)
}

pub fn save(path: &Path, auth: &Auth) -> io::Result<()> {
    corvid_store::save(path, SCHEMA, auth)
}
