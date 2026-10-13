use std::sync::Arc;

use crate::auth::{Invalid, Issuer, MasterRefusal};

/// Why an admin token was not accepted. `Invalid` is for a token that proves nothing, `NotMaster`
/// for a genuine token of someone who is not the admin.
#[derive(Debug, PartialEq, Eq)]
pub enum Refusal {
    Invalid(&'static str),
    NotMaster,
}

pub struct Master {
    admin_user: String,
    local: Option<Arc<Issuer>>,
}

impl Master {
    pub fn new(admin_user: String, local: Option<Arc<Issuer>>) -> Self {
        Self { admin_user, local }
    }

    /// Accepts an admin token this sentinel issued after a fresh assertion by a passkey that
    /// approves devices, good for one request. With passkey sign-in off there is no such token.
    pub fn verify(&self, token: &str, now: i64) -> Result<(), Refusal> {
        let local = self
            .local
            .as_deref()
            .ok_or(Refusal::Invalid("passkey sign-in is off"))?;
        local
            .verify_master(token, &self.admin_user, now)
            .map_err(|refusal| match refusal {
                MasterRefusal::Invalid(Invalid(reason)) => Refusal::Invalid(reason),
                MasterRefusal::NotMaster => Refusal::NotMaster,
            })
    }
}
