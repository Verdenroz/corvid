use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

use super::state::Role;
use crate::webpush::random_bytes;

const CHALLENGE_LIFETIME_SECONDS: i64 = 300;
const CHALLENGES_PER_ADDRESS: usize = 4;
const MAX_CHALLENGES: usize = 1024;
const FAILURE_WINDOW_SECONDS: i64 = 600;
const FAILURES_PER_ADDRESS: usize = 5;
const FAILURES_IN_ALL: usize = 20;

/// What a challenge was issued for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Pending {
    Register {
        role: Role,
        code: String,
        handle: String,
    },
    Login {
        role: Role,
    },
}

/// Challenges in flight. They live in memory only: a restart voids them, which costs a retry.
#[derive(Default)]
pub struct Challenges {
    live: Mutex<HashMap<String, Issued>>,
}

struct Issued {
    pending: Pending,
    address: String,
    at: i64,
    order: u64,
}

impl Challenges {
    /// A fresh challenge for `address`, whose oldest live one is dropped past its share. `None`
    /// only when the table is full, which the per-address share keeps one client from causing.
    pub fn issue(&self, pending: Pending, address: &str, now: i64) -> Option<[u8; 32]> {
        let mut live = self.live.lock().unwrap_or_else(PoisonError::into_inner);
        live.retain(|_, issued| now - issued.at < CHALLENGE_LIFETIME_SECONDS);
        let mut own: Vec<_> = live
            .iter()
            .filter(|(_, issued)| issued.address == address)
            .map(|(key, issued)| (issued.order, key.clone()))
            .collect();
        if own.len() >= CHALLENGES_PER_ADDRESS {
            own.sort();
            let excess = own.len() + 1 - CHALLENGES_PER_ADDRESS;
            for (_, key) in &own[..excess] {
                live.remove(key);
            }
        }
        if live.len() >= MAX_CHALLENGES {
            return None;
        }
        let order = live
            .values()
            .map(|issued| issued.order + 1)
            .max()
            .unwrap_or(0);
        let challenge = random_bytes::<32>();
        live.insert(
            URL_SAFE_NO_PAD.encode(challenge),
            Issued {
                pending,
                address: address.to_owned(),
                at: now,
                order,
            },
        );
        Some(challenge)
    }

    /// Spends the challenge: it answers once, whatever the outcome.
    pub fn take(&self, challenge: &[u8], now: i64) -> Option<Pending> {
        let mut live = self.live.lock().unwrap_or_else(PoisonError::into_inner);
        let issued = live.remove(&URL_SAFE_NO_PAD.encode(challenge))?;
        (now - issued.at < CHALLENGE_LIFETIME_SECONDS).then_some(issued.pending)
    }
}

/// Failed code redemptions, per client address and across all of them.
#[derive(Default)]
pub struct Failures {
    recent: Mutex<Vec<(String, i64)>>,
}

impl Failures {
    pub fn allows(&self, address: &str, now: i64) -> bool {
        let mut recent = self.recent.lock().unwrap_or_else(PoisonError::into_inner);
        recent.retain(|(_, at)| now - *at < FAILURE_WINDOW_SECONDS);
        recent.len() < FAILURES_IN_ALL
            && recent.iter().filter(|(from, _)| from == address).count() < FAILURES_PER_ADDRESS
    }

    pub fn record(&self, address: &str, now: i64) {
        self.recent
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push((address.to_owned(), now));
    }
}
