use std::collections::{BTreeMap, BTreeSet};

use crate::sender::SenderKey;

/// Counts decay so a sender that changes character moves into ambiguity, where the model picks
/// it up, instead of being held to its history.
const HALF_LIFE_DAYS: f64 = 180.0;
/// Messages a sender needs before the prior will decide anything.
///
/// Effectively exclusive: an observation decays below 1.0 at any age, so exactly this many
/// messages never clear the floor. It takes one more, and more again as the history ages.
const MIN_EVIDENCE: f64 = 5.0;
/// Absence is weaker evidence than presence: a label a sender happens not to have sent yet
/// says little, and a confident withhold would stop the model ever seeing that case.
const WITHHOLD_EVIDENCE: f64 = 10.0;
/// Three half-lives. Beyond this a sender's history is nearly gone, so an observation this
/// far ahead of everything seen for them is more likely a bad timestamp than a real gap.
const IMPLAUSIBLE_GAP_DAYS: f64 = 3.0 * HALF_LIFE_DAYS;
const APPLY_ABOVE: f64 = 0.8;
const WITHHOLD_BELOW: f64 = 0.2;
/// Pseudocounts behind the smoothed rate, `(seen + PSEUDO_SEEN) / (total + PSEUDO_TOTAL)`.
///
/// Laplace, so a sender with one message carrying a label reads as two thirds rather than as
/// certainty. They set every rate the census buckets on, so they are pinned with the thresholds.
const PSEUDO_SEEN: f64 = 1.0;
const PSEUDO_TOTAL: f64 = 2.0;

/// Why the prior declined to decide. These are the two regions the model has to beat, so
/// they are recorded rather than collapsed into one "no answer".
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, serde::Serialize, serde::Deserialize,
)]
pub enum Abstain {
    UnseenSender,
    TooFewMessages,
    /// The rate is low but the sender has too little history to rule the label out. Counted
    /// apart from `Ambiguous` because it resolves as more mail arrives, where real ambiguity
    /// persists.
    TooFewToWithhold,
    Ambiguous,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum Outcome {
    Apply,
    Withhold,
    Abstain(Abstain),
}

#[derive(Debug, Default, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Prediction {
    pub applied: BTreeSet<String>,
    pub outcomes: BTreeMap<String, Outcome>,
    /// Set when nothing about the message could be decided, whatever labels exist. The
    /// per-label outcomes are then empty, since the reason is the same for all of them.
    ///
    /// An unseen sender is a fact about the message, not about any label. Hung off the label
    /// set, it would vanish on a fresh install with no labels, the period the unseen curve is for.
    pub undecidable: Option<Abstain>,
}

impl Prediction {
    pub fn abstentions(&self) -> impl Iterator<Item = Abstain> {
        self.undecidable
            .into_iter()
            .chain(self.outcomes.values().filter_map(|outcome| match outcome {
                Outcome::Abstain(reason) => Some(*reason),
                _ => None,
            }))
    }
}

#[derive(Debug, Default, serde::Serialize, serde::Deserialize)]
struct Sender {
    total: f64,
    labels: BTreeMap<String, f64>,
    updated: f64,
}

impl Sender {
    fn decay_to(&mut self, at: f64) {
        let factor = 0.5f64.powf((at - self.updated).max(0.0) / HALF_LIFE_DAYS);
        self.total *= factor;
        for count in self.labels.values_mut() {
            *count *= factor;
        }
        // Never regresses, or out-of-order observations and a non-chronological cold-start
        // pass would decay nothing at all.
        self.updated = self.updated.max(at);
    }
}

/// Per-label sender priors. Multi-label by construction: each label carries its own rate for
/// a sender, matching the IMAP keywords it writes and the per-label classifier it gates.
#[derive(Debug, Default, serde::Serialize, serde::Deserialize)]
pub struct Prior {
    senders: BTreeMap<SenderKey, Sender>,
    known: BTreeSet<String>,
    capped: u64,
}

impl Prior {
    /// `at` is a day number; only differences matter.
    ///
    /// It must come from the receive time, never the sender-written `Date` header: one message
    /// dated far in the future would decay a sender's whole history to nothing in one step.
    ///
    /// Returns the time actually recorded, which is the capped one.
    pub fn observe(&mut self, key: SenderKey, labels: &[String], at: f64) -> f64 {
        let sender = self.senders.entry(key).or_default();
        let ceiling = sender.updated + IMPLAUSIBLE_GAP_DAYS;
        let known_sender = sender.total > 0.0;
        let jumped = known_sender && at > ceiling;
        // Capped against the sender's own history, since the prior has no wall clock. An
        // uncapped far-future `at` would pin `updated` ahead and stop decay for the sender.
        let at = if known_sender { at.min(ceiling) } else { at };
        sender.decay_to(at);
        sender.total += 1.0;
        for label in labels {
            *sender.labels.entry(label.clone()).or_default() += 1.0;
            self.known.insert(label.clone());
        }
        if jumped {
            self.capped += 1;
        }
        at
    }

    /// Replaces an earlier observation of one message rather than adding a second.
    ///
    /// A message relabelled five times still weighs one. Appending would let a single message
    /// cross the evidence floor on its own.
    ///
    /// The retracted weight is the original observation decayed since `previous_at`, not 1.0.
    pub fn revise(
        &mut self,
        key: SenderKey,
        previous: &[String],
        previous_at: f64,
        labels: &[String],
        at: f64,
    ) -> f64 {
        let sender = self.senders.entry(key).or_default();
        let ceiling = sender.updated + IMPLAUSIBLE_GAP_DAYS;
        let known_sender = sender.total > 0.0;
        let jumped = known_sender && at > ceiling;
        let at = if known_sender { at.min(ceiling) } else { at };
        sender.decay_to(at);

        let weight = 0.5f64.powf((at - previous_at).max(0.0) / HALF_LIFE_DAYS);
        sender.total = (sender.total - weight).max(0.0);
        for label in previous {
            if let Some(count) = sender.labels.get_mut(label) {
                *count = (*count - weight).max(0.0);
            }
        }

        sender.total += 1.0;
        for label in labels {
            *sender.labels.entry(label.clone()).or_default() += 1.0;
            self.known.insert(label.clone());
        }
        if jumped {
            self.capped += 1;
        }
        at
    }

    /// Observations whose advance was capped against the sender's own history.
    ///
    /// Nothing enforces that `at` is a receive time, so the cap bounds the damage and this
    /// counter makes it visible. It also counts real multi-year dormancy: a sustained non-zero
    /// count means `Date` is reaching the prior, an occasional one means a list woke up.
    pub fn capped_advances(&self) -> u64 {
        self.capped
    }

    pub fn predict(&self, key: &SenderKey, at: f64) -> Prediction {
        let mut prediction = Prediction::default();
        let Some(sender) = self.senders.get(key) else {
            prediction.undecidable = Some(Abstain::UnseenSender);
            return prediction;
        };

        // Uncapped, unlike `observe`: a read reflects true elapsed time, so a sender dormant
        // for years decays and abstains. Only stored state needs the cap.
        let mut sender = Sender {
            total: sender.total,
            labels: sender.labels.clone(),
            updated: sender.updated,
        };
        sender.decay_to(at);
        if sender.total < MIN_EVIDENCE {
            prediction.undecidable = Some(Abstain::TooFewMessages);
            return prediction;
        }

        for label in &self.known {
            let seen = sender.labels.get(label).copied().unwrap_or(0.0);
            let rate = (seen + PSEUDO_SEEN) / (sender.total + PSEUDO_TOTAL);
            let outcome = if rate >= APPLY_ABOVE {
                prediction.applied.insert(label.clone());
                Outcome::Apply
            } else if rate <= WITHHOLD_BELOW {
                if sender.total >= WITHHOLD_EVIDENCE {
                    Outcome::Withhold
                } else {
                    Outcome::Abstain(Abstain::TooFewToWithhold)
                }
            } else {
                Outcome::Abstain(Abstain::Ambiguous)
            };
            prediction.outcomes.insert(label.clone(), outcome);
        }
        prediction
    }
}

impl Prior {
    pub fn has_seen(&self, key: &SenderKey) -> bool {
        self.senders.contains_key(key)
    }

    /// Every label seen on real mail, which is the only place a label can come from.
    ///
    /// This is what the model is offered to choose among. It grows when a person labels
    /// something, never from the model's own answers.
    pub fn known(&self) -> &BTreeSet<String> {
        &self.known
    }

    /// Drops a label from the known set. Without this a renamed or retired label keeps
    /// drawing an abstention on every unseen sender forever.
    pub fn retire(&mut self, label: &str) {
        self.known.remove(label);
        for sender in self.senders.values_mut() {
            sender.labels.remove(label);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Every value that decides a message's bucket belongs here. Changing one means bumping
    // `SCHEMA` in corvid-ingest and resetting the census together: nothing refuses the old file.
    #[test]
    fn the_thresholds_the_census_counted_under_are_pinned() {
        assert_eq!(
            [
                HALF_LIFE_DAYS,
                MIN_EVIDENCE,
                WITHHOLD_EVIDENCE,
                APPLY_ABOVE,
                WITHHOLD_BELOW,
                IMPLAUSIBLE_GAP_DAYS,
                PSEUDO_SEEN,
                PSEUDO_TOTAL,
            ],
            [180.0, 5.0, 10.0, 0.8, 0.2, 540.0, 1.0, 2.0]
        );
    }
}
