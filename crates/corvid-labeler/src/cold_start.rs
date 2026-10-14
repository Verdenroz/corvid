use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;
use std::time::{Duration, Instant};

use corvid_ingest::{Ingest, model_keywords, open, save};
use corvid_label::{Ask, MAX_LABELS, SenderKey};

use crate::mailbox::{Mailbox, Message};
use crate::model::Oracle;

/// How much of the queue to look at before choosing. Larger than any budget, so the choice is
/// made across senders rather than across whatever the query returned.
const SCAN: usize = 500;

/// Below this share of probability on the offered letters, the model was not answering the
/// question asked, and its preference among the letters says little.
const MIN_COVERAGE: f64 = 0.9;

/// What one pass may spend.
///
/// Both bounds are needed: a slow call never errors, so `messages` does not bound how long the
/// pass holds the poll loop's thread. `time` is checked between messages, so a pass can overrun
/// it by one message, which is one call per option.
pub struct Budget {
    pub messages: usize,
    pub time: Duration,
}

/// Where each attempted message went. Every attempted message is marked done in exactly one
/// of the four outcomes, and only `labelled` and `none_fit` teach the prior.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct ColdStart {
    pub attempted: usize,
    pub labelled: usize,
    /// "None of these" won with confidence.
    pub none_fit: usize,
    /// The winner fell below the confidence threshold.
    pub unconfident: usize,
    /// Too little probability landed on the offered letters. A rise means the prompt or the
    /// model changed.
    pub off_options: usize,
    pub unkeyed: usize,
    /// Set when the pass stopped on the clock rather than on the budget or the queue.
    pub out_of_time: bool,
    /// Set when the mailbox has more labels than a prompt can offer, so nothing was asked.
    pub too_many_labels: bool,
}

/// Spends a budget of model calls on historical mail, and records what came back.
///
/// The state is written whether or not the pass finished, so stopping partway keeps what the
/// earlier messages taught instead of replaying them on a mailbox already marked.
///
/// A label is written only when the winner's probability reaches `confidence`, a fraction.
pub fn cold_start(
    path: &Path,
    mailbox: &impl Mailbox,
    oracle: &impl Oracle,
    budget: &Budget,
    confidence: f64,
) -> io::Result<ColdStart> {
    let mut ingest = open(path)?;
    let outcome = spend(path, &mut ingest, mailbox, oracle, budget, confidence);
    save(path, &ingest)?;
    outcome
}

fn spend(
    path: &Path,
    ingest: &mut Ingest,
    mailbox: &impl Mailbox,
    oracle: &impl Oracle,
    budget: &Budget,
    confidence: f64,
) -> io::Result<ColdStart> {
    let vocabulary = ingest.vocabulary().clone();
    let mut summary = ColdStart::default();
    if vocabulary.len() > MAX_LABELS {
        summary.too_many_labels = true;
        return Ok(summary);
    }

    let mut waiting: BTreeMap<String, (Message, SenderKey)> = BTreeMap::new();
    for message in mailbox.awaiting_model(SCAN)? {
        match message.sender_key() {
            Some(key) => {
                waiting.insert(message.id.clone(), (message, key));
            }
            None => summary.unkeyed += 1,
        }
    }

    let candidates: Vec<(String, SenderKey)> = waiting
        .iter()
        .map(|(id, (_, key))| (id.clone(), key.clone()))
        .collect();

    let until = Instant::now() + budget.time;
    for id in ingest.worth_labelling(&candidates, budget.messages) {
        if Instant::now() >= until {
            summary.out_of_time = true;
            return Ok(summary);
        }
        let (message, key) = &waiting[&id];
        let question = corvid_label::Message {
            from: &message.from,
            subject: &message.subject,
        };
        // No labels exist, so there is nothing to ask.
        let Some(ask) = Ask::new(&vocabulary, &question) else {
            return Ok(summary);
        };
        summary.attempted += 1;

        let letters = ask.letters();
        let scores = ask
            .prompts()
            .map(|prompt| oracle.choose(prompt, &letters))
            .collect::<io::Result<Vec<_>>>()?;
        let choice = ask.read(&scores);
        let confident = choice.coverage >= MIN_COVERAGE && choice.probability >= confidence;
        let labels: BTreeSet<String> = match (&choice.label, confident) {
            (Some(label), true) => BTreeSet::from([label.clone()]),
            _ => BTreeSet::new(),
        };

        // Keyword write, then record, then save, per message: a kill loses only the message
        // in flight.
        mailbox.apply_keywords(&id, &model_keywords(&labels), &BTreeSet::new())?;
        if confident {
            let learned: Vec<String> = labels.iter().cloned().collect();
            ingest.learn(&id, key, &learned, message.received_at_days());
            save(path, ingest)?;
        }
        match (confident, &choice.label) {
            _ if choice.coverage < MIN_COVERAGE => summary.off_options += 1,
            (false, _) => summary.unconfident += 1,
            (true, Some(_)) => summary.labelled += 1,
            (true, None) => summary.none_fit += 1,
        }
    }

    Ok(summary)
}
