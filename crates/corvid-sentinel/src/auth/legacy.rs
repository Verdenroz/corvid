//! Schema 1 of the auth state, which kept a mail user and an admin user apart. Public so a test
//! can write a file in this shape.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::state::{self, Family};

pub const SCHEMA: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    Daily,
    Master,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Credential {
    pub user: String,
    pub public_key: String,
    pub sign_count: u32,
    pub backup_eligible: bool,
    pub created_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Code {
    pub hash: String,
    pub expires_at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Auth {
    pub users: BTreeMap<String, Role>,
    pub credentials: BTreeMap<String, Credential>,
    pub codes: BTreeMap<Role, Code>,
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
corvid_store::pin_shape!(Auth {
    users,
    credentials,
    codes,
    families
});

/// Master credentials and codes become approvers, daily ones plain passkeys. The master's user
/// handle is kept when there is one, since the authenticators that hold a master key know it.
pub fn migrate(old: Auth) -> state::Auth {
    let user = old
        .users
        .iter()
        .find(|(_, role)| **role == Role::Master)
        .or_else(|| old.users.iter().next())
        .map(|(handle, _)| handle.clone());
    let approves = |handle: &str| old.users.get(handle) == Some(&Role::Master);
    state::Auth {
        user,
        credentials: old
            .credentials
            .iter()
            .map(|(id, credential)| {
                let migrated = state::Credential {
                    public_key: credential.public_key.clone(),
                    sign_count: credential.sign_count,
                    backup_eligible: credential.backup_eligible,
                    approves: approves(&credential.user),
                    created_at: credential.created_at,
                };
                (id.clone(), migrated)
            })
            .collect(),
        codes: old
            .codes
            .into_iter()
            .map(|(role, code)| state::Code {
                hash: code.hash,
                expires_at: code.expires_at,
                approves: role == Role::Master,
            })
            .collect(),
        families: old.families,
    }
}
