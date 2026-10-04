use std::collections::BTreeSet;

use corvid_ingest::{Ingest, NAMESPACE, sender_key};
use corvid_label::{Abstain, SenderKey};

const OURS: &str = "mail.corvid.test; spf=pass; dmarc=pass header.from=news.example";
const NONE: &str = "mail.corvid.test; spf=none; dmarc=none header.from=evil.example";
const FORGED: &str = "mail.corvid.test; dkim=pass; dmarc=pass header.from=news.example";

fn acme() -> SenderKey {
    SenderKey::Domain("acme.test".into())
}

fn keywords_of(list: &[&str]) -> BTreeSet<String> {
    keywords(list)
}

fn keywords(list: &[&str]) -> BTreeSet<String> {
    list.iter().map(|l| (*l).to_owned()).collect()
}

fn owned(list: &[&str]) -> Vec<String> {
    list.iter().map(|l| (*l).to_owned()).collect()
}

fn teach(ingest: &mut Ingest, key: &SenderKey, labels: &[&str], count: usize) {
    for day in 0..count {
        ingest.learn(&format!("seed-{day}"), key, &owned(labels), day as f64);
    }
}

#[test]
fn an_authenticated_list_id_becomes_the_key() {
    assert_eq!(
        sender_key("bounce-99@mail.example", Some("<news.example>"), &[OURS]),
        Some(SenderKey::ListId("news.example".into()))
    );
}

#[test]
fn a_forged_authentication_result_does_not_buy_a_list_key() {
    assert_eq!(
        sender_key(
            "spoofer@evil.example",
            Some("<news.example>"),
            &[NONE, FORGED]
        ),
        Some(SenderKey::Domain("evil.example".into())),
        "a header the sender wrote must not grant a list's learned rates"
    );
}

#[test]
fn a_missing_authentication_header_is_not_trusted() {
    assert_eq!(
        sender_key("spoofer@evil.example", Some("<news.example>"), &[]),
        Some(SenderKey::Domain("evil.example".into()))
    );
}

#[test]
fn writes_its_labels_inside_its_own_namespace() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    assert_eq!(
        ingest.label("m1", &acme(), 12.0),
        keywords(&["corvid-receipts"])
    );
}

#[test]
fn never_writes_a_label_that_lands_in_a_reserved_namespace() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["llm-x", "rule-x", "receipts"], 12);

    assert_eq!(
        ingest.label("m1", &acme(), 12.0),
        keywords(&["corvid-receipts"])
    );
}

#[test]
fn writes_nothing_for_an_unknown_sender() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    assert!(
        ingest
            .label("m1", &SenderKey::Domain("stranger.test".into()), 12.0)
            .is_empty()
    );
}

#[test]
fn its_own_labels_never_train_it_even_with_no_record() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    for message in 0..50 {
        let id = format!("m{message}");
        let written = ingest.label(&id, &acme(), 12.0);
        ingest.reconcile(&id, &acme(), &written, 12.0);
    }

    assert_eq!(
        ingest.corrections(),
        0,
        "everything written was in the namespace"
    );
}

#[test]
fn learns_from_a_keyword_the_user_set() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);
    let mut present = ingest.label("m1", &acme(), 12.0);
    present.extend(keywords(&["work"]));

    ingest.reconcile("m1", &acme(), &present, 12.0);

    assert_eq!(ingest.corrections(), 1);
}

#[test]
fn an_untouched_message_carries_no_signal() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);
    let written = ingest.label("m1", &acme(), 12.0);

    ingest.reconcile("m1", &acme(), &written, 12.0);

    assert_eq!(
        ingest.corrections(),
        0,
        "not looking is not the same as answering none"
    );
}

#[test]
fn a_correction_is_learned_once_however_often_it_is_seen() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    for _ in 0..5 {
        ingest.reconcile("m1", &acme(), &keywords(&["work"]), 12.0);
    }

    assert_eq!(
        ingest.corrections(),
        1,
        "re-reading a mailbox must not multiply one signal"
    );
}

#[test]
fn removing_a_label_the_user_had_set_is_itself_a_correction() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);
    ingest.reconcile("m1", &acme(), &keywords(&["work"]), 12.0);

    ingest.reconcile("m1", &acme(), &BTreeSet::new(), 12.0);

    assert_eq!(ingest.corrections(), 2, "taking a label back is an answer");
}

#[test]
fn a_correction_alongside_our_labels_learns_only_the_users() {
    let mut ingest = Ingest::default();
    let stranger = SenderKey::Domain("stranger.test".into());
    let present = keywords(&["corvid-receipts", "work"]);

    ingest.reconcile("m1", &stranger, &present, 0.0);
    for day in 1..12 {
        ingest.reconcile(&format!("m{day}"), &stranger, &present, day as f64);
    }

    let applied = ingest.label("mx", &stranger, 12.0);
    assert_eq!(
        applied,
        keywords(&["corvid-work"]),
        "only the user's label was learned"
    );
    assert!(!applied.contains(&format!("{NAMESPACE}receipts")));
}

// Rates survive a replay, since numerator and denominator scale together, but the census does
// not: spuriously crossed evidence floors would overstate what the prior decides.
#[test]
fn a_replayed_message_does_not_count_twice() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    for _ in 0..40 {
        ingest.label("m1", &acme(), 12.0);
    }

    assert_eq!(ingest.census().messages(), 1, "one message is one message");
    assert_eq!(ingest.repeats(), 39, "and the replay is visible");
}

#[test]
fn a_quiet_mailbox_reports_no_repeats() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    for message in 0..40 {
        ingest.label(&format!("m{message}"), &acme(), 12.0);
    }

    assert_eq!(ingest.census().messages(), 40);
    assert_eq!(ingest.repeats(), 0);
}

// The labeler's own Email/set comes back as an update. That settles only because the second
// pass decides the same and writes nothing.
#[test]
fn a_second_pass_over_the_same_message_decides_the_same_thing() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    let first = ingest.label("m1", &acme(), 12.0);
    ingest.reconcile("m1", &acme(), &first, 12.0);
    let second = ingest.label("m1", &acme(), 12.0);
    ingest.reconcile("m1", &acme(), &second, 12.0);

    assert_eq!(
        first, second,
        "the write must be a no-op the second time round"
    );
    assert_eq!(ingest.corrections(), 0);
}

#[test]
fn relabelling_one_message_does_not_manufacture_evidence() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("fiddle.test".into());
    let edits: [&[&str]; 5] = [
        &["work"],
        &["work", "urgent"],
        &["work", "urgent", "todo"],
        &["work", "todo"],
        &["work"],
    ];
    for (day, set) in edits.iter().enumerate() {
        ingest.reconcile("m1", &key, &keywords(set), day as f64);
    }

    ingest.label("m2", &key, 5.0);

    assert_eq!(
        ingest.census().abstentions(Abstain::TooFewMessages),
        1,
        "five edits of one message is one message"
    );
}

#[test]
fn separate_messages_do_pass_the_evidence_floor() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("busy.test".into());
    for day in 0..6 {
        ingest.reconcile(&format!("m{day}"), &key, &keywords(&["work"]), day as f64);
    }

    ingest.label("later", &key, 6.0);

    assert_eq!(ingest.census().abstentions(Abstain::TooFewMessages), 0);
}

#[test]
fn a_repeated_cold_start_pass_does_not_double_the_evidence() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("acme.test".into());
    for _ in 0..2 {
        for day in 0..4 {
            ingest.learn(&format!("seed{day}"), &key, &owned(&["work"]), day as f64);
        }
    }

    ingest.label("m1", &key, 4.0);

    assert_eq!(
        ingest.census().abstentions(Abstain::TooFewMessages),
        1,
        "four messages twice is still four messages"
    );
}

#[test]
fn correcting_a_learned_message_replaces_rather_than_adds() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("acme.test".into());
    for day in 0..4 {
        ingest.learn(&format!("seed{day}"), &key, &owned(&["work"]), day as f64);
    }

    ingest.reconcile("seed0", &key, &keywords(&["personal"]), 4.0);
    ingest.label("m1", &key, 4.0);

    assert_eq!(
        ingest.census().abstentions(Abstain::TooFewMessages),
        1,
        "a correction restates a message, it does not add one"
    );
}

// A message arrives carrying $junk before the user has touched it.
#[test]
fn system_keywords_are_not_treated_as_labels() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("acme.test".into());

    ingest.reconcile("m1", &key, &keywords(&["$seen", "$junk", "$flagged"]), 0.0);

    assert_eq!(
        ingest.corrections(),
        0,
        "an untouched message carries no signal"
    );
}

#[test]
fn arriving_mail_does_not_dilute_a_label_the_user_set() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("lab.test".into());
    for day in 0..8 {
        ingest.reconcile(
            &format!("m{day}"),
            &key,
            &keywords(&["$junk", "work"]),
            day as f64,
        );
    }

    ingest.reconcile("fresh", &key, &keywords(&["$junk"]), 8.0);
    let applied = ingest.label("later", &key, 8.0);

    assert_eq!(
        applied,
        keywords(&["corvid-work"]),
        "a confirmed label must survive mail arriving beside it"
    );
}

#[test]
fn a_label_that_merely_contains_a_dollar_is_still_a_label() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("acme.test".into());
    for day in 0..6 {
        ingest.reconcile(&format!("m{day}"), &key, &keywords(&["cost$"]), day as f64);
    }

    assert_eq!(
        ingest.label("later", &key, 6.0),
        keywords(&["corvid-cost$"])
    );
}

// A fresh install knows no labels, so counting per label would read zero for the whole cold
// start.
#[test]
fn the_first_message_of_all_is_counted_as_an_unseen_sender() {
    let mut ingest = Ingest::default();

    ingest.label("m1", &acme(), 0.0);

    assert_eq!(ingest.census().unseen(), 1);
    assert_eq!(ingest.census().messages(), 1);
}

#[test]
fn a_known_sender_is_not_counted_as_unseen() {
    let mut ingest = Ingest::default();
    teach(&mut ingest, &acme(), &["receipts"], 12);

    ingest.label("m1", &acme(), 12.0);

    assert_eq!(ingest.census().unseen(), 0);
}

#[test]
fn a_message_re_reported_unchanged_is_the_replay_signature() {
    let mut ingest = Ingest::default();
    let present = keywords(&["$seen", "work"]);

    ingest.reconcile("m1", &acme(), &present, 0.0);
    assert_eq!(
        ingest.unchanged_sightings(),
        0,
        "the first sighting changed everything"
    );

    ingest.reconcile("m1", &acme(), &present, 0.0);
    ingest.reconcile("m1", &acme(), &present, 0.0);

    assert_eq!(ingest.unchanged_sightings(), 2);
}

#[test]
fn a_message_re_reported_after_a_change_is_ordinary() {
    let mut ingest = Ingest::default();

    ingest.reconcile("m1", &acme(), &keywords(&["work"]), 0.0);
    ingest.reconcile("m1", &acme(), &keywords(&["work", "$seen"]), 0.0);
    ingest.reconcile(
        "m1",
        &acme(),
        &keywords(&["work", "$seen", "corvid-work"]),
        0.0,
    );

    assert_eq!(
        ingest.unchanged_sightings(),
        0,
        "reading a message and our own write are both ordinary causes"
    );
}

fn candidates(pairs: &[(&str, &str)]) -> Vec<(String, SenderKey)> {
    pairs
        .iter()
        .map(|(id, sender)| ((*id).to_owned(), SenderKey::Domain((*sender).to_owned())))
        .collect()
}

#[test]
fn cold_start_takes_breadth_before_depth() {
    let ingest = Ingest::default();
    let pool = candidates(&[
        ("a1", "a.test"),
        ("a2", "a.test"),
        ("a3", "a.test"),
        ("b1", "b.test"),
        ("c1", "c.test"),
    ]);

    let chosen = ingest.worth_labelling(&pool, 3);

    assert_eq!(
        chosen,
        ["a1", "b1", "c1"],
        "one from each before a second from any"
    );
}

#[test]
fn cold_start_fills_the_budget_when_senders_run_out() {
    let ingest = Ingest::default();
    let pool = candidates(&[("a1", "a.test"), ("a2", "a.test"), ("b1", "b.test")]);

    assert_eq!(ingest.worth_labelling(&pool, 3), ["a1", "b1", "a2"]);
}

#[test]
fn cold_start_respects_the_budget() {
    let ingest = Ingest::default();
    let pool = candidates(&[("a1", "a.test"), ("b1", "b.test"), ("c1", "c.test")]);

    assert_eq!(ingest.worth_labelling(&pool, 2).len(), 2);
}

#[test]
fn cold_start_skips_what_it_has_already_learned_from() {
    let mut ingest = Ingest::default();
    let acme = SenderKey::Domain("a.test".into());
    ingest.learn("a1", &acme, &owned(&["work"]), 0.0);
    let pool = candidates(&[("a1", "a.test"), ("a2", "a.test")]);

    assert_eq!(ingest.worth_labelling(&pool, 5), ["a2"]);
}

#[test]
fn cold_start_has_nothing_to_do_on_an_empty_mailbox() {
    assert!(Ingest::default().worth_labelling(&[], 10).is_empty());
}

#[test]
fn a_verdict_of_nothing_still_marks_the_message() {
    let keywords = corvid_ingest::model_keywords(&BTreeSet::new());

    assert_eq!(keywords, keywords_of(&["corvid-llm-done"]));
}

#[test]
fn a_verdict_carries_its_labels_beside_the_marker() {
    let accepted = keywords_of(&["work", "receipts"]);

    assert_eq!(
        corvid_ingest::model_keywords(&accepted),
        keywords_of(&[
            "corvid-llm-done",
            "corvid-llm-label-receipts",
            "corvid-llm-label-work",
        ])
    );
}

#[test]
fn a_marker_is_not_read_back_as_a_label() {
    let present = keywords_of(&[
        "corvid-llm-done",
        "corvid-llm-oversized",
        "corvid-llm-label-work",
        "$seen",
        "corvid-receipts",
    ]);

    assert_eq!(
        corvid_ingest::model_labels(&present),
        keywords_of(&["work"])
    );
}

// Read as the user's too, a model label would be counted twice under two names.
#[test]
fn a_model_label_is_not_also_read_as_the_users() {
    let mut ingest = Ingest::default();
    let key = SenderKey::Domain("acme.test".into());

    ingest.reconcile(
        "m1",
        &key,
        &keywords_of(&["corvid-llm-label-work", "corvid-llm-done"]),
        0.0,
    );

    assert_eq!(
        ingest.corrections(),
        0,
        "the model does not speak for the user"
    );
}

// Labels are user-authored and may contain a slash; closure says nothing about those.
#[test]
fn an_awkward_but_legitimate_label_survives_the_round_trip() {
    let accepted = keywords_of(&["work/urgent"]);
    let written = corvid_ingest::model_keywords(&accepted);

    assert!(written.contains("corvid-llm-label-work/urgent"));
    assert_eq!(corvid_ingest::model_labels(&written), accepted);
}

#[test]
fn an_answer_too_long_to_use_is_marked_without_a_label() {
    let keywords = corvid_ingest::model_oversized();

    assert!(keywords.contains("corvid-llm-done"));
    assert!(keywords.contains("corvid-llm-oversized"));
    assert!(corvid_ingest::model_labels(&keywords).is_empty());
}

fn cold_started(label: &str) -> Ingest {
    let mut ingest = Ingest::default();
    ingest.learn("m1", &acme(), &owned(&[label]), 0.0);
    ingest
}

fn corrections_after(keywords_now: &[&str]) -> u64 {
    let mut ingest = cold_started("receipts");
    ingest.reconcile("m1", &acme(), &keywords(keywords_now), 1.0);
    ingest.corrections()
}

#[test]
fn a_cold_started_message_left_alone_is_not_a_correction() {
    assert_eq!(
        corrections_after(&["corvid-llm-done", "corvid-llm-label-receipts"]),
        0
    );
}

#[test]
fn confirming_a_models_label_is_agreement() {
    assert_eq!(corrections_after(&["corvid-llm-done", "receipts"]), 0);
}

#[test]
fn rejecting_a_models_label_is_a_correction() {
    assert_eq!(corrections_after(&["corvid-llm-done"]), 1);
}

#[test]
fn replacing_a_models_label_is_a_correction() {
    assert_eq!(corrections_after(&["corvid-llm-done", "work"]), 1);
}

#[test]
fn a_user_learned_message_ignores_model_labels() {
    let mut ingest = Ingest::default();
    ingest.reconcile("m1", &acme(), &keywords(&["work"]), 0.0);

    ingest.reconcile(
        "m1",
        &acme(),
        &keywords(&["work", "corvid-llm-label-receipts"]),
        1.0,
    );

    assert_eq!(ingest.corrections(), 1, "only the first answer counted");
}
