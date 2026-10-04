mod authentication;
mod store;

use std::collections::{BTreeMap, BTreeSet};

use corvid_label::{Census, Prior, SenderKey};
use sha2::{Digest, Sha256};

pub use authentication::dmarc_passed;
pub use store::{load, open, save};

/// Everything the labeler writes carries this prefix. Nothing in JMAP or IMAP records who set a
/// keyword, so without it the prior could train on its own output whenever the record of what
/// it wrote was lost.
pub const NAMESPACE: &str = "corvid-";

/// Where a model's own judgments are written, inside the labeler's namespace so they are never
/// read back as the user's.
pub const MODEL_NAMESPACE: &str = "corvid-llm-";

/// Where a model's labels go, one level below its markers.
///
/// Markers sit directly under `MODEL_NAMESPACE` and labels under this, so no marker can be read
/// back as a label without the reader knowing the full list of markers.
pub const MODEL_LABEL_NAMESPACE: &str = "corvid-llm-label-";

/// Written whether or not the model chose anything.
///
/// The queue is "no marker", so without it a message the model examined and assigned nothing
/// would cost a call on every pass forever, possibly getting different labels each time.
pub const MODEL_DONE: &str = "corvid-llm-done";

/// Set beside the marker when the model's answer arrived cut off.
///
/// A partial answer is not written. As a keyword, the count and ids come from the same query
/// that drives the queue, which is the retry set if the prompt or the limit changes.
///
/// Marking is permanent because the failure is: the label set only grows, so the prompt only
/// gets longer.
pub const MODEL_OVERSIZED: &str = "corvid-llm-oversized";

/// Where a client's filters write labels at delivery, inside the labeler's namespace so they are
/// never read back as the user's.
pub const RULE_NAMESPACE: &str = "corvid-rule-";

/// Whether the prior wrote this keyword, which is the only kind the poll may add or remove.
///
/// Model and rule keywords share the prefix but belong to other writers.
pub fn is_prior_keyword(keyword: &str) -> bool {
    keyword.starts_with(NAMESPACE)
        && !keyword.starts_with(MODEL_NAMESPACE)
        && !keyword.starts_with(RULE_NAMESPACE)
}

/// The keywords to write for a model's verdict, which always include the marker.
///
/// Returning the marker from the same call makes "always marked" hold at every call site.
pub fn model_keywords(accepted: &BTreeSet<String>) -> BTreeSet<String> {
    std::iter::once(MODEL_DONE.to_owned())
        .chain(
            accepted
                .iter()
                .map(|label| format!("{MODEL_LABEL_NAMESPACE}{label}")),
        )
        .collect()
}

/// The keywords to write for an answer that arrived cut off, which also include the marker.
pub fn model_oversized() -> BTreeSet<String> {
    [MODEL_DONE.to_owned(), MODEL_OVERSIZED.to_owned()]
        .into_iter()
        .collect()
}

/// The labels a model assigned, read back from a message's keywords.
///
/// Ground truth for the prior: the prompt carries no `corvid-` keyword, so the judgment is
/// independent of the prior's own output.
pub fn model_labels(keywords: &BTreeSet<String>) -> BTreeSet<String> {
    keywords
        .iter()
        .filter_map(|keyword| keyword.strip_prefix(MODEL_LABEL_NAMESPACE))
        .map(str::to_owned)
        .collect()
}

pub fn sender_key(
    from: &str,
    list_id: Option<&str>,
    authentication_results: &[&str],
) -> Option<SenderKey> {
    let list_id = list_id.filter(|_| dmarc_passed(authentication_results));
    SenderKey::derive(from, list_id)
}

/// A stable digest of the whole keyword set, including system and namespaced keywords, so a
/// re-report with nothing changed is distinguishable from one caused by a `$seen`.
///
/// Persisted, so not the standard library hasher, whose output may change between releases.
fn fingerprint(keywords: &BTreeSet<String>) -> u64 {
    let mut digest = Sha256::new();
    for keyword in keywords {
        digest.update(keyword.as_bytes());
        digest.update([0]);
    }
    u64::from_be_bytes(
        digest.finalize()[..8]
            .try_into()
            .expect("sha256 is longer than 8 bytes"),
    )
}

/// The user's labels: everything that is neither the labeler's nor the protocol's.
///
/// `$`-keywords are message state set by clients and the spam filter. Every arriving message
/// carries `$junk` before the user touches it, so learning them would dilute real labels below
/// the apply threshold, and `$seen` on a read message would make agreement argue against every
/// label not added by hand.
fn own_labels(keywords: &BTreeSet<String>) -> BTreeSet<String> {
    keywords
        .iter()
        .filter(|keyword| !keyword.starts_with(NAMESPACE) && !keyword.starts_with('$'))
        .cloned()
        .collect()
}

/// Labels arriving mail and learns from what the user does to those labels afterwards.
///
/// The prior only sees keywords outside the namespace, so it cannot train on its own output even
/// if the record below is lost. Losing the record only costs idempotence: a message is learned
/// from twice, which overstates evidence but does not compound.
#[derive(Default, serde::Serialize, serde::Deserialize)]
pub struct Ingest {
    prior: Prior,
    census: Census,
    learned: BTreeMap<String, (BTreeSet<String>, f64)>,
    counted: BTreeSet<String>,
    sightings: BTreeMap<String, u64>,
    cursor: Option<String>,
    corrections: u64,
    repeats: u64,
    writes: u64,
    unchanged: u64,
}

impl Ingest {
    /// Returns the keywords to write, already namespaced.
    ///
    /// A message counts toward the census once. A replay scales the abstain buckets along with
    /// the message total, so repeated counting would leave nothing in the census looking wrong.
    pub fn label(&mut self, id: &str, key: &SenderKey, at: f64) -> BTreeSet<String> {
        let prediction = self.prior.predict(key, at);
        if self.counted.insert(id.to_owned()) {
            self.census.record(key, &prediction);
        } else {
            self.repeats += 1;
        }
        prediction
            .applied
            .iter()
            .map(|label| format!("{NAMESPACE}{label}"))
            .filter(|keyword| is_prior_keyword(keyword))
            .collect()
    }

    /// Learns from the keywords the user set on a message, which are the ones outside the
    /// namespace.
    ///
    /// A message the user has never labelled carries no signal: no keyword means the user did
    /// not look. Only a change from a set already seen counts, so removing every label is a real
    /// answer rather than silence.
    ///
    /// **Agreement is therefore never observed.** The prior trains only on messages the user
    /// actively labelled, plus the cold-start set, so its training data is not a sample of the
    /// mailbox and the blind audit is not optional.
    pub fn reconcile(&mut self, id: &str, key: &SenderKey, keywords: &BTreeSet<String>, at: f64) {
        let fingerprint = fingerprint(keywords);
        if self.sightings.insert(id.to_owned(), fingerprint) == Some(fingerprint) {
            self.unchanged += 1;
        }
        let labels = own_labels(keywords);
        if labels.is_empty() && !self.learned.contains_key(id) {
            return;
        }
        if self.record(id, key, labels, at) {
            self.corrections += 1;
        }
    }

    /// One message is one observation: a later edit replaces the earlier one, so relabelling
    /// cannot carry a sender over the evidence floor on its own.
    fn record(&mut self, id: &str, key: &SenderKey, labels: BTreeSet<String>, at: f64) -> bool {
        let observed: Vec<String> = labels.iter().cloned().collect();
        // Store the prior's capped time, so a later correction retracts the weight it applied.
        let recorded = match self.learned.get(id).cloned() {
            Some((previous, _)) if previous == labels => return false,
            Some((previous, previous_at)) => {
                let previous: Vec<String> = previous.into_iter().collect();
                self.prior
                    .revise(key.clone(), &previous, previous_at, &observed, at)
            }
            None => self.prior.observe(key.clone(), &observed, at),
        };
        self.learned.insert(id.to_owned(), (labels, recorded));
        true
    }

    /// Chooses which messages are worth a model's attention, breadth first.
    ///
    /// A sender prior learns far more from a first message by an unknown sender than from a
    /// fifth by a known one, so candidates are taken round-robin across senders rather than in
    /// arrival order. Messages already learned from are skipped, so a restarted pass does not
    /// re-spend.
    pub fn worth_labelling(&self, candidates: &[(String, SenderKey)], limit: usize) -> Vec<String> {
        let mut by_sender: BTreeMap<&SenderKey, Vec<&String>> = BTreeMap::new();
        for (id, key) in candidates {
            if !self.learned.contains_key(id) {
                by_sender.entry(key).or_default().push(id);
            }
        }

        let mut chosen = Vec::new();
        let mut round = 0;
        while chosen.len() < limit {
            let mut took_any = false;
            for ids in by_sender.values() {
                if let Some(id) = ids.get(round) {
                    chosen.push((*id).clone());
                    took_any = true;
                    if chosen.len() == limit {
                        return chosen;
                    }
                }
            }
            if !took_any {
                break;
            }
            round += 1;
        }
        chosen
    }

    /// Ground truth from outside the prior, such as the cold-start pass.
    ///
    /// Guarded like a correction, since a batch over historical mail is the thing most likely
    /// to run twice.
    pub fn learn(&mut self, id: &str, key: &SenderKey, labels: &[String], at: f64) {
        self.record(id, key, labels.iter().cloned().collect(), at);
    }

    /// The labels a model may choose among.
    pub fn vocabulary(&self) -> &BTreeSet<String> {
        self.prior.known()
    }

    pub fn corrections(&self) -> u64 {
        self.corrections
    }

    /// Messages labelled more than once.
    ///
    /// **Nonzero is normal and it is not the replay signal.** Anything that touches a message
    /// re-reports it through the change feed, including the labeler's own writes and every
    /// `$seen`. Use [`Ingest::unchanged_sightings`] instead.
    pub fn repeats(&self) -> u64 {
        self.repeats
    }

    /// Whether this message has already been counted, in which case a report of it as newly
    /// created is a contradiction.
    pub fn has_counted(&self, id: &str) -> bool {
        self.counted.contains(id)
    }

    /// Messages seen again with every keyword exactly as last recorded.
    ///
    /// A re-report with nothing changed is what a replayed cursor produces and ordinary traffic
    /// does not. A redelivery or a lost record can also produce it.
    ///
    /// Its sensitivity grows with how much of the mailbox has been recorded, so a replay is
    /// quietest while the state file is young.
    pub fn unchanged_sightings(&self) -> u64 {
        self.unchanged
    }

    /// Messages this has written keywords to.
    pub fn writes(&self) -> u64 {
        self.writes
    }

    pub fn note_write(&mut self) {
        self.writes += 1;
    }

    pub fn census(&self) -> &Census {
        &self.census
    }

    /// The change-feed position, held in the same value as the records that protect against
    /// replaying it.
    ///
    /// One atomic write moves both or neither, so a crash costs a repeated poll and the dedup
    /// set that catches the repeat was saved with it.
    pub fn cursor(&self) -> Option<&str> {
        self.cursor.as_deref()
    }

    pub fn set_cursor(&mut self, cursor: impl Into<String>) {
        self.cursor = Some(cursor.into());
    }
}
