use std::collections::{BTreeMap, BTreeSet};

use crate::prior::{Abstain, Prediction};
use crate::sender::SenderKey;

/// Sizes the region the model is allowed to address, without a model and without labels.
///
/// Counts **messages**, not label decisions. An unseen sender abstains on every known label
/// while an ambiguous one abstains on one or two, so counting decisions would scale the unseen
/// bucket by the size of the label set. A message counts once per distinct reason.
///
/// The reason counts **overlap and do not sum to `messages()`**: one message can be ambiguous
/// on one label and under-evidenced on another.
///
/// Counts are cumulative, so the state file carries a schema version and refuses a mismatch.
/// Adding an `Abstain` variant or moving one of the prior's thresholds parses cleanly against
/// old data and silently pools counts that mean different things, so the thresholds are pinned
/// by a test that names the schema constant.
#[derive(Debug, Default, serde::Serialize, serde::Deserialize)]
pub struct Census {
    messages: u64,
    unseen: u64,
    reasons: BTreeMap<Abstain, u64>,
    senders: BTreeMap<Abstain, BTreeSet<SenderKey>>,
    distinct: BTreeSet<SenderKey>,
    checkpoints: Vec<(u64, u64)>,
}

impl Census {
    pub fn record(&mut self, key: &SenderKey, prediction: &Prediction) {
        self.messages += 1;
        self.distinct.insert(key.clone());
        let reasons: BTreeSet<Abstain> = prediction.abstentions().collect();
        if reasons.contains(&Abstain::UnseenSender) {
            self.unseen += 1;
        }
        for reason in reasons {
            *self.reasons.entry(reason).or_default() += 1;
            self.senders.entry(reason).or_default().insert(key.clone());
        }
        if self.messages.is_multiple_of(CHECKPOINT_EVERY) {
            self.checkpoints.push((self.messages, self.unseen));
        }
    }

    pub fn messages(&self) -> u64 {
        self.messages
    }

    /// Messages from a sender never seen before, counted from the very first message rather
    /// than from the first message with a label attached.
    pub fn unseen(&self) -> u64 {
        self.unseen
    }

    pub fn abstentions(&self, reason: Abstain) -> u64 {
        self.reasons.get(&reason).copied().unwrap_or_default()
    }

    /// Distinct senders behind a bucket, alongside the message count for it.
    ///
    /// Message counts alone cannot separate "the same senders, decided differently" from "more
    /// senders", and inflated evidence reads as learning. No threshold is asserted here.
    pub fn senders(&self, reason: Abstain) -> usize {
        self.senders
            .get(&reason)
            .map(BTreeSet::len)
            .unwrap_or_default()
    }

    pub fn distinct_senders(&self) -> usize {
        self.distinct.len()
    }

    /// Unseen-sender rate per checkpoint interval rather than cumulatively.
    ///
    /// On a new mailbox every sender is unseen and the cumulative rate decays from 1.0, so a
    /// single figure measures the cold start. The floor this settles to is the real rate of new
    /// correspondents.
    pub fn unseen_curve(&self) -> Vec<f64> {
        let mut previous = (0, 0);
        self.checkpoints
            .iter()
            .map(|(messages, unseen)| {
                let rate = (unseen - previous.1) as f64 / (messages - previous.0) as f64;
                previous = (*messages, *unseen);
                rate
            })
            .collect()
    }
}

/// How often the unseen curve takes a sample.
///
/// Persisted: changing it interleaves two sampling rates in the stored points with nothing
/// marking where the rate moved.
const CHECKPOINT_EVERY: u64 = 100;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prior::Outcome;

    // Changing this means bumping `SCHEMA` in corvid-ingest and resetting the census together:
    // nothing refuses an old file whose points were sampled at another rate.
    #[test]
    fn the_sampling_rate_the_curve_was_built_at_is_pinned() {
        assert_eq!(CHECKPOINT_EVERY, 100);
    }

    fn abstaining(reason: Abstain) -> Prediction {
        let mut prediction = Prediction::default();
        prediction
            .outcomes
            .insert("work".into(), Outcome::Abstain(reason));
        prediction
    }

    fn key(name: &str) -> SenderKey {
        SenderKey::Domain(name.into())
    }

    fn decided() -> Prediction {
        let mut prediction = Prediction::default();
        prediction.outcomes.insert("work".into(), Outcome::Apply);
        prediction
    }

    #[test]
    fn an_unseen_sender_counts_once_however_many_labels_exist() {
        let mut wide = Prediction::default();
        for label in ["a", "b", "c", "d", "e"] {
            wide.outcomes
                .insert(label.into(), Outcome::Abstain(Abstain::UnseenSender));
        }
        let mut census = Census::default();
        census.record(&key("a"), &wide);
        census.record(&key("b"), &abstaining(Abstain::Ambiguous));

        assert_eq!(
            census.abstentions(Abstain::UnseenSender),
            census.abstentions(Abstain::Ambiguous),
            "the buckets must be on one scale to be comparable"
        );
    }

    #[test]
    fn counts_the_two_abstain_reasons_apart() {
        let mut census = Census::default();
        census.record(&key("a"), &abstaining(Abstain::UnseenSender));
        census.record(&key("b"), &abstaining(Abstain::Ambiguous));
        census.record(&key("b"), &abstaining(Abstain::Ambiguous));

        assert_eq!(census.abstentions(Abstain::UnseenSender), 1);
        assert_eq!(census.abstentions(Abstain::Ambiguous), 2);
        assert_eq!(census.messages(), 3);
    }

    #[test]
    fn the_curve_reports_each_interval_not_the_running_total() {
        let mut census = Census::default();
        for _ in 0..100 {
            census.record(&key("a"), &abstaining(Abstain::UnseenSender));
        }
        for _ in 0..100 {
            census.record(&key("c"), &decided());
        }

        assert_eq!(
            census.unseen_curve(),
            [1.0, 0.0],
            "a cold start must not colour later intervals"
        );
    }

    #[test]
    fn counts_distinct_senders_beside_messages() {
        let mut census = Census::default();
        for _ in 0..10 {
            census.record(&key("one"), &abstaining(Abstain::Ambiguous));
        }
        census.record(&key("two"), &abstaining(Abstain::Ambiguous));

        assert_eq!(census.abstentions(Abstain::Ambiguous), 11);
        assert_eq!(census.senders(Abstain::Ambiguous), 2);
        assert_eq!(census.distinct_senders(), 2);
    }

    #[test]
    fn a_sender_counts_only_toward_the_buckets_it_lands_in() {
        let mut census = Census::default();
        census.record(&key("one"), &abstaining(Abstain::UnseenSender));
        census.record(&key("two"), &abstaining(Abstain::Ambiguous));

        assert_eq!(census.senders(Abstain::UnseenSender), 1);
        assert_eq!(census.senders(Abstain::Ambiguous), 1);
        assert_eq!(census.distinct_senders(), 2);
    }

    #[test]
    fn has_no_curve_before_the_first_checkpoint() {
        let mut census = Census::default();
        census.record(&key("c"), &decided());

        assert!(
            census.unseen_curve().is_empty(),
            "too few messages to report a rate"
        );
    }
}
