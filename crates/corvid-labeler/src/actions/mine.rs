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
        for (key, tally) in &self.keys {
            let (Some(first), Some(last)) = (tally.days.first(), tally.days.last()) else {
                continue;
            };
            if tally.settled < MIN_OBSERVATIONS
                || tally.days.len() < MIN_DAYS
                || last - first < MIN_SPAN_DAYS
            {
                continue;
            }
            for (outcome, &hits) in &tally.exits {
                let precision = f64::from(hits) / f64::from(tally.settled);
                let lower_bound = wilson_lower(hits, tally.settled);
                if precision >= MIN_PRECISION && lower_bound >= MIN_LOWER_BOUND {
                    found.push(Candidate {
                        id: id_of(key, outcome),
                        key: key.clone(),
                        outcome: outcome.clone(),
                        settled: tally.settled,
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
