mod common;

use common::{Fake, NONE, Utterance, Voice, backlog, scratch, taught};
use std::time::Duration;

use corvid_label::SenderKey;
use corvid_labeler::{Budget, ColdStart, cold_start, poll};

const CONFIDENCE: f64 = 0.8;

fn messages(messages: usize) -> Budget {
    Budget {
        messages,
        time: Duration::from_secs(60),
    }
}

fn run(path: &std::path::Path, mailbox: &Fake, voice: &Voice, budget: usize) -> ColdStart {
    cold_start(path, mailbox, voice, &messages(budget), CONFIDENCE).unwrap()
}

/// Whether the prior learned from this message, which is when it stops being worth labelling.
fn learned_from(path: &std::path::Path, id: &str, domain: &str) -> bool {
    let candidate = [(id.to_owned(), SenderKey::Domain(domain.into()))];
    corvid_ingest::open(path)
        .unwrap()
        .worth_labelling(&candidate, 1)
        .is_empty()
}

fn learned_for(path: &std::path::Path, domain: &str) -> std::collections::BTreeSet<String> {
    corvid_ingest::open(path)
        .unwrap()
        .label("later", &SenderKey::Domain(domain.into()), 30.0)
}

#[test]
fn a_mailbox_with_no_labels_yet_is_not_asked_about() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    poll(&path, &mailbox).unwrap();
    backlog(&mailbox, "billing@acme.test", "u", 3);
    let voice = Voice::always(Utterance::Choose("receipts"));

    let summary = run(&path, &mailbox, &voice, 5);

    assert_eq!(summary.attempted, 0);
    assert!(voice.prompts.borrow().is_empty(), "nothing to choose among");
}

#[test]
fn every_rotation_of_the_options_is_asked() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 3);
    let voice = Voice::always(Utterance::Choose("receipts"));

    run(&path, &mailbox, &voice, 1);

    let prompts = voice.prompts.borrow();
    assert_eq!(prompts.len(), 2, "one label and none of these");
    assert!(prompts[0].contains("A: receipts") && prompts[1].contains("B: receipts"));
}

#[test]
fn a_confident_label_is_written_and_learned() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "news@other.test", "n", 6);
    let voice = Voice::always(Utterance::Choose("receipts"));

    let summary = run(&path, &mailbox, &voice, 6);

    assert_eq!(summary.labelled, 6);
    assert_eq!(mailbox.carrying("corvid-llm-label-receipts").len(), 6);
    assert_eq!(mailbox.carrying("corvid-llm-done").len(), 6);
    assert!(learned_for(&path, "other.test").contains("corvid-receipts"));
    assert!(learned_from(&path, "n0", "other.test"));
}

#[test]
fn none_of_these_takes_the_message_out_of_the_queue_without_a_label() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 3);
    let voice = Voice::always(Utterance::Choose(NONE));

    let summary = run(&path, &mailbox, &voice, 1);

    assert_eq!(summary.none_fit, 1);
    assert_eq!(mailbox.carrying("corvid-llm-done").len(), 1);
    assert!(mailbox.carrying("corvid-llm-label-receipts").is_empty());

    run(&path, &mailbox, &voice, 1);
    let subjects = voice.subjects();
    assert_eq!(subjects.len(), 2);
    assert_ne!(
        subjects[0], subjects[1],
        "a marked message is not asked again"
    );
}

#[test]
fn an_unconfident_answer_is_marked_but_teaches_nothing() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "news@other.test", "n", 6);
    let voice = Voice::always(Utterance::Hesitate);

    let summary = run(&path, &mailbox, &voice, 6);

    assert_eq!(summary.unconfident, 6);
    assert_eq!(summary.labelled + summary.none_fit, 0);
    assert_eq!(mailbox.carrying("corvid-llm-done").len(), 6);
    assert!(mailbox.carrying("corvid-llm-label-receipts").is_empty());
    assert!((0..6).all(|n| !learned_from(&path, &format!("n{n}"), "other.test")));
}

#[test]
fn an_answer_off_the_offered_letters_is_marked_but_teaches_nothing() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 3);
    let voice = Voice::always(Utterance::Wander);

    let summary = run(&path, &mailbox, &voice, 1);

    assert_eq!(summary.off_options, 1);
    let marked = mailbox.carrying("corvid-llm-done");
    assert_eq!(marked.len(), 1);
    assert_eq!(mailbox.keywords_of(&marked[0]).len(), 1, "the marker alone");
}

#[test]
fn a_preference_that_is_mostly_off_the_letters_is_not_trusted() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 3);
    let voice = Voice::always(Utterance::Drift("receipts"));

    let summary = run(&path, &mailbox, &voice, 1);

    assert_eq!(summary.off_options, 1);
    assert!(mailbox.carrying("corvid-llm-label-receipts").is_empty());
    assert_eq!(mailbox.carrying("corvid-llm-done").len(), 1);
}

#[test]
fn more_labels_than_a_prompt_can_offer_asks_nothing_and_says_so() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    poll(&path, &mailbox).unwrap();
    let labels: Vec<String> = (0..26).map(|n| format!("l{n}")).collect();
    let refs: Vec<&str> = labels.iter().map(String::as_str).collect();
    for day in 0..12 {
        mailbox.deliver(&format!("m{day}"), "billing@acme.test", &refs, day as f64);
    }
    poll(&path, &mailbox).unwrap();
    backlog(&mailbox, "news@other.test", "n", 3);
    let voice = Voice::always(Utterance::Choose("l0"));

    let summary = run(&path, &mailbox, &voice, 3);

    assert!(summary.too_many_labels);
    assert!(voice.prompts.borrow().is_empty());
}

#[test]
fn the_budget_is_spread_across_senders() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 5);
    backlog(&mailbox, "news@other.test", "n", 5);
    let voice = Voice::always(Utterance::Choose("receipts"));

    run(&path, &mailbox, &voice, 2);

    let prompts = voice.prompts.borrow();
    assert!(prompts.iter().any(|p| p.contains("billing@acme.test")));
    assert!(prompts.iter().any(|p| p.contains("news@other.test")));
}

#[test]
fn an_unreachable_model_stops_the_pass_and_keeps_what_it_did() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 3);
    let voice = Voice::saying(&[Utterance::Choose("receipts")], Utterance::Unreachable);

    assert!(cold_start(&path, &mailbox, &voice, &messages(5), CONFIDENCE).is_err());
    assert_eq!(mailbox.carrying("corvid-llm-done").len(), 1);

    let later = Voice::always(Utterance::Choose(NONE));
    run(&path, &mailbox, &later, 1);
    assert_eq!(mailbox.carrying("corvid-llm-done").len(), 2);
}

#[test]
fn a_pass_of_slow_answers_stops_on_the_clock() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 20);
    let voice = Voice::always(Utterance::Choose(NONE)).taking(Duration::from_millis(10));

    let summary = cold_start(
        &path,
        &mailbox,
        &voice,
        &Budget {
            messages: 20,
            time: Duration::from_millis(50),
        },
        CONFIDENCE,
    )
    .unwrap();

    assert!(summary.out_of_time);
    assert!(
        summary.attempted < 20,
        "{} messages asked",
        summary.attempted
    );
}

// Marked messages leave the queue for good, so evidence not saved before a crash is never
// collected again.
#[test]
fn a_kill_mid_pass_keeps_what_the_earlier_messages_taught() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "news@other.test", "n", 8);
    let voice = Voice::saying(&[Utterance::Choose("receipts"); 6], Utterance::Killed);

    let killed = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        cold_start(&path, &mailbox, &voice, &messages(8), CONFIDENCE)
    }));

    assert!(killed.is_err(), "the pass died rather than returning");
    let mut ingest = corvid_ingest::open(&path).unwrap();
    let sender = SenderKey::Domain("other.test".into());
    assert!(
        ingest
            .label("later", &sender, 20.0)
            .contains("corvid-receipts"),
        "six messages of evidence were recorded before the kill"
    );
}

#[test]
fn a_cold_started_message_is_not_a_correction_on_the_next_poll() {
    let (_dir, path) = scratch();
    let mailbox = Fake::default();
    taught(&path, &mailbox);
    backlog(&mailbox, "billing@acme.test", "u", 3);
    let voice = Voice::always(Utterance::Choose("receipts"));
    run(&path, &mailbox, &voice, 3);
    let before = learned_for(&path, "acme.test");
    let corrections = corvid_ingest::open(&path).unwrap().corrections();
    mailbox.touch("u0");

    poll(&path, &mailbox).unwrap();

    assert_eq!(
        corvid_ingest::open(&path).unwrap().corrections(),
        corrections
    );
    assert_eq!(learned_for(&path, "acme.test"), before);
    assert!(mailbox.keywords_of("u0").contains("corvid-llm-done"));
    assert!(
        mailbox
            .keywords_of("u0")
            .contains("corvid-llm-label-receipts")
    );
}
