use std::collections::{BTreeMap, BTreeSet};

use sha2::{Digest, Sha256};

use super::state::{Actions, Candidate};

const MIN_OBSERVATIONS: u32 = 20;
const MIN_PRECISION: f64 = 0.95;
const MIN_LOWER_BOUND: f64 = 0.80;
const MIN_DAYS: usize = 5;
const MIN_SPAN_DAYS: i64 = 14;
const Z: f64 = 1.96;

impl Actions {
    /// Replaces the candidates with every (key, outcome) the settled history supports.
    pub fn mine(&mut self) {
        let mut found = Vec::new();
        for (key, history) in &self.keys {
            let days: BTreeSet<i64> = history.iter().map(|seen| seen.day).collect();
            let (Some(first), Some(last)) = (days.first(), days.last()) else {
                continue;
            };
            let settled = history.len() as u32;
            if settled < MIN_OBSERVATIONS || days.len() < MIN_DAYS || last - first < MIN_SPAN_DAYS {
                continue;
            }
            let mut exits: BTreeMap<&str, u32> = BTreeMap::new();
            for outcome in history.iter().filter_map(|seen| seen.exit.as_deref()) {
                *exits.entry(outcome).or_default() += 1;
            }
            for (outcome, hits) in exits {
                let precision = f64::from(hits) / f64::from(settled);
                let lower_bound = wilson_lower(hits, settled);
                if precision >= MIN_PRECISION && lower_bound >= MIN_LOWER_BOUND {
                    found.push(Candidate {
                        id: id_of(key, outcome),
                        key: key.clone(),
                        outcome: outcome.to_owned(),
                        settled,
                        hits,
                        lower_bound,
                    });
                }
            }
        }
        self.candidates = found;
    }
}

fn wilson_lower(hits: u32, total: u32) -> f64 {
    let (n, p) = (f64::from(total), f64::from(hits) / f64::from(total));
    let z2 = Z * Z;
    let centre = p + z2 / (2.0 * n);
    let spread = Z * ((p * (1.0 - p) + z2 / (4.0 * n)) / n).sqrt();
    (centre - spread) / (1.0 + z2 / n)
}

/// Persisted and shown to the user, so not the standard library hasher.
fn id_of(key: &str, outcome: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(key.as_bytes());
    digest.update([0]);
    digest.update(outcome.as_bytes());
    hex::encode(&digest.finalize()[..8])
}
