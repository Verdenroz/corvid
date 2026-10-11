use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

use super::state::Role;
use crate::webpush::random_bytes;

const CHALLENGE_LIFETIME_SECONDS: i64 = 300;
const MAX_CHALLENGES: usize = 64;
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
    live: Mutex<HashMap<String, (Pending, i64)>>,
}

impl Challenges {
    /// A fresh challenge, or `None` while the limit of live ones is reached.
    pub fn issue(&self, pending: Pending, now: i64) -> Option<[u8; 32]> {
        let mut live = self.live.lock().unwrap_or_else(PoisonError::into_inner);
        live.retain(|_, (_, issued)| now - *issued < CHALLENGE_LIFETIME_SECONDS);
        if live.len() >= MAX_CHALLENGES {
            return None;
        }
        let challenge = random_bytes::<32>();
        live.insert(URL_SAFE_NO_PAD.encode(challenge), (pending, now));
        Some(challenge)
    }

    /// Spends the challenge: it answers once, whatever the outcome.
    pub fn take(&self, challenge: &[u8], now: i64) -> Option<Pending> {
        let mut live = self.live.lock().unwrap_or_else(PoisonError::into_inner);
        let (pending, issued) = live.remove(&URL_SAFE_NO_PAD.encode(challenge))?;
        (now - issued < CHALLENGE_LIFETIME_SECONDS).then_some(pending)
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
