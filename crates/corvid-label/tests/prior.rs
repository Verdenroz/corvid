use corvid_label::{Abstain, Outcome, Prior, SenderKey};

fn sender(name: &str) -> SenderKey {
    SenderKey::Domain(name.into())
}

fn labels(list: &[&str]) -> Vec<String> {
    list.iter().map(|l| (*l).to_owned()).collect()
}

fn seed(prior: &mut Prior, key: &SenderKey, sets: &[Vec<String>]) {
    for (day, set) in sets.iter().enumerate() {
        prior.observe(key.clone(), set, day as f64);
    }
}

fn repeat(set: &[&str], times: usize) -> Vec<Vec<String>> {
    vec![labels(set); times]
}

fn reasons(prediction: &corvid_label::Prediction) -> Vec<Abstain> {
    prediction.abstentions().collect()
}

#[test]
fn applies_a_label_a_sender_consistently_carries() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    seed(&mut prior, &acme, &repeat(&["receipts"], 12));

    assert!(prior.predict(&acme, 12.0).applied.contains("receipts"));
}

#[test]
fn abstains_on_a_sender_it_has_never_seen() {
    let mut prior = Prior::default();
    seed(&mut prior, &sender("acme.test"), &repeat(&["receipts"], 12));

    let prediction = prior.predict(&sender("stranger.test"), 12.0);

    assert!(prediction.applied.is_empty());
    assert_eq!(reasons(&prediction), [Abstain::UnseenSender]);
}

#[test]
fn abstains_while_the_evidence_is_thin() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    seed(&mut prior, &acme, &repeat(&["receipts"], 2));

    assert_eq!(
        reasons(&prior.predict(&acme, 2.0)),
        [Abstain::TooFewMessages]
    );
}

#[test]
fn a_sender_who_sends_two_kinds_of_thing_is_ambiguous_not_guessed() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    let alternating: Vec<Vec<String>> = (0..12)
        .map(|turn| {
            labels(if turn % 2 == 0 {
                &["receipts"]
            } else {
                &["marketing"]
            })
        })
        .collect();
    seed(&mut prior, &acme, &alternating);

    let prediction = prior.predict(&acme, 12.0);

    assert!(
        prediction.applied.is_empty(),
        "a tie must not be broken arbitrarily"
    );
    assert_eq!(
        reasons(&prediction),
        [Abstain::Ambiguous, Abstain::Ambiguous]
    );
}

#[test]
fn several_labels_can_apply_to_one_message() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    seed(&mut prior, &acme, &repeat(&["work", "receipts"], 12));

    let prediction = prior.predict(&acme, 12.0);

    assert!(prediction.applied.contains("work"));
    assert!(prediction.applied.contains("receipts"));
}

#[test]
fn withholds_a_label_a_sender_almost_never_carries() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    let mut sets = repeat(&["receipts"], 24);
    sets.push(labels(&["marketing"]));
    seed(&mut prior, &acme, &sets);

    let prediction = prior.predict(&acme, 25.0);

    assert_eq!(
        prediction.outcomes.get("marketing"),
        Some(&Outcome::Withhold)
    );
    assert_eq!(prediction.outcomes.get("receipts"), Some(&Outcome::Apply));
}

#[test]
fn does_not_withhold_on_thin_evidence() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    let busy = sender("busy.test");
    seed(&mut prior, &busy, &repeat(&["marketing"], 12));
    seed(&mut prior, &acme, &repeat(&["receipts"], 6));

    let prediction = prior.predict(&acme, 12.0);

    assert_eq!(
        prediction.outcomes.get("marketing"),
        Some(&Outcome::Abstain(Abstain::TooFewToWithhold))
    );
}

#[test]
fn a_sender_that_changes_character_drifts_into_ambiguity() {
    let mut prior = Prior::default();
    let colleague = sender("colleague.test");
    for day in 0..20 {
        prior.observe(colleague.clone(), &labels(&["team-a"]), day as f64);
    }
    assert!(prior.predict(&colleague, 20.0).applied.contains("team-a"));

    for day in 0..20 {
        prior.observe(colleague.clone(), &labels(&["team-b"]), 400.0 + day as f64);
    }

    assert!(
        !prior.predict(&colleague, 420.0).applied.contains("team-a"),
        "history must not hold a sender to a role it has left"
    );
}

#[test]
fn an_out_of_order_backfill_does_not_disable_decay() {
    let mut chronological = Prior::default();
    let mut reversed = Prior::default();
    let key = sender("acme.test");
    for day in 0..40 {
        chronological.observe(key.clone(), &labels(&["work"]), day as f64);
    }
    for day in (0..40).rev() {
        reversed.observe(key.clone(), &labels(&["work"]), day as f64);
    }

    assert!(chronological.predict(&key, 40.0).applied.contains("work"));
    assert!(reversed.predict(&key, 40.0).applied.contains("work"));
}

#[test]
fn retiring_a_label_stops_it_being_decided() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    seed(&mut prior, &acme, &repeat(&["old", "new"], 12));

    prior.retire("old");
    let prediction = prior.predict(&acme, 12.0);

    assert_eq!(prediction.outcomes.keys().collect::<Vec<_>>(), ["new"]);
    assert!(!prediction.applied.contains("old"));
}

// Boundaries below are chosen against the smoothed rate, (seen + 1) / (total + 2), and
// observed on one day so decay leaves the totals exact.
fn over_days(prior: &mut Prior, key: &SenderKey, carrying: usize, total: usize) {
    for index in 0..total {
        let set = if index < carrying {
            labels(&["work"])
        } else {
            labels(&["other"])
        };
        prior.observe(key.clone(), &set, index as f64);
    }
}

fn on_one_day(prior: &mut Prior, key: &SenderKey, carrying: usize, total: usize) {
    for index in 0..total {
        let set = if index < carrying {
            labels(&["work"])
        } else {
            labels(&["other"])
        };
        prior.observe(key.clone(), &set, 0.0);
    }
}

#[test]
fn applies_exactly_at_the_apply_threshold() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    on_one_day(&mut prior, &acme, 7, 8);

    assert_eq!(
        prior.predict(&acme, 0.0).outcomes.get("work"),
        Some(&Outcome::Apply)
    );
}

#[test]
fn withholds_exactly_at_the_withhold_threshold() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    on_one_day(&mut prior, &acme, 2, 13);

    assert_eq!(
        prior.predict(&acme, 0.0).outcomes.get("work"),
        Some(&Outcome::Withhold)
    );
}

#[test]
fn the_evidence_floor_is_exclusive() {
    let mut prior = Prior::default();
    let five = sender("five.test");
    let four = sender("four.test");
    on_one_day(&mut prior, &five, 5, 5);
    on_one_day(&mut prior, &four, 4, 4);

    assert!(
        reasons(&prior.predict(&five, 0.0)).is_empty(),
        "five messages arriving on one day is enough"
    );
    assert_eq!(
        reasons(&prior.predict(&four, 0.0)),
        [Abstain::TooFewMessages]
    );
}

// Decay makes every observation worth less than 1.0, so exactly as many messages as the floor
// names never reach it.
#[test]
fn the_evidence_floor_takes_one_more_message_than_it_names() {
    let acme = sender("acme.test");

    let mut five = Prior::default();
    seed(&mut five, &acme, &repeat(&["receipts"], 5));
    assert_eq!(
        reasons(&five.predict(&acme, 4.0)),
        [Abstain::TooFewMessages]
    );

    let mut six = Prior::default();
    seed(&mut six, &acme, &repeat(&["receipts"], 6));
    assert!(six.predict(&acme, 5.0).applied.contains("receipts"));
}

#[test]
fn the_withhold_evidence_floor_is_inclusive() {
    let mut prior = Prior::default();
    let ten = sender("ten.test");
    let nine = sender("nine.test");
    on_one_day(&mut prior, &sender("elsewhere.test"), 5, 5);
    on_one_day(&mut prior, &ten, 0, 10);
    on_one_day(&mut prior, &nine, 0, 9);

    assert_eq!(
        prior.predict(&ten, 0.0).outcomes.get("work"),
        Some(&Outcome::Withhold),
        "ten messages arriving on one day is enough"
    );
    assert_eq!(
        prior.predict(&nine, 0.0).outcomes.get("work"),
        Some(&Outcome::Abstain(Abstain::TooFewToWithhold)),
        "one message short of the floor must stay open to the model"
    );
}

#[test]
fn the_withhold_floor_also_takes_one_more_message_than_it_names() {
    let mut prior = Prior::default();
    let ten = sender("ten.test");
    let eleven = sender("eleven.test");
    over_days(&mut prior, &sender("elsewhere.test"), 5, 5);
    over_days(&mut prior, &ten, 0, 10);
    over_days(&mut prior, &eleven, 0, 11);

    assert_eq!(
        prior.predict(&ten, 10.0).outcomes.get("work"),
        Some(&Outcome::Abstain(Abstain::TooFewToWithhold))
    );
    assert_eq!(
        prior.predict(&eleven, 10.0).outcomes.get("work"),
        Some(&Outcome::Withhold)
    );
}

#[test]
fn an_under_evidenced_sender_is_not_reported_as_ambiguous() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    on_one_day(&mut prior, &sender("elsewhere.test"), 6, 12);
    on_one_day(&mut prior, &acme, 6, 6);

    let prediction = prior.predict(&acme, 0.0);

    assert!(
        prediction.applied.contains("work"),
        "the sender's own label is decided"
    );
    assert_eq!(reasons(&prediction), [Abstain::TooFewToWithhold]);
}

#[test]
fn a_multi_mode_sender_stays_ambiguous_however_much_mail_arrives() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    on_one_day(&mut prior, &acme, 20, 40);

    assert_eq!(
        reasons(&prior.predict(&acme, 0.0)),
        [Abstain::Ambiguous, Abstain::Ambiguous]
    );
}

#[test]
fn counts_a_timestamp_far_beyond_a_senders_history() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    for day in 0..10 {
        prior.observe(acme.clone(), &labels(&["work"]), day as f64);
    }
    assert_eq!(prior.capped_advances(), 0);

    prior.observe(acme.clone(), &labels(&["work"]), 4000.0);

    assert_eq!(
        prior.capped_advances(),
        1,
        "a decade-ahead Date must be visible"
    );
}

#[test]
fn a_first_message_from_a_sender_is_never_implausible() {
    let mut prior = Prior::default();
    prior.observe(sender("acme.test"), &labels(&["work"]), 4000.0);

    assert_eq!(prior.capped_advances(), 0);
}

#[test]
fn a_timestamp_from_the_future_does_not_disable_decay() {
    let mut prior = Prior::default();
    let acme = sender("acme.test");
    for day in 0..40 {
        prior.observe(acme.clone(), &labels(&["old"]), day as f64);
    }
    prior.observe(acme.clone(), &labels(&["old"]), 4000.0);
    for day in 40..140 {
        prior.observe(acme.clone(), &labels(&["new"]), day as f64);
    }

    let prediction = prior.predict(&acme, 140.0);

    assert!(prediction.applied.contains("new"));
    assert!(
        !prediction.applied.contains("old"),
        "decay must survive one bad timestamp"
    );
    assert_eq!(prior.capped_advances(), 1, "and the event stays visible");
}

// A dormant sender is capped like a bad date, but at three half-lives its history has
// decayed to about the right place either way.
#[test]
fn a_long_dormancy_decays_the_old_history_away() {
    let mut prior = Prior::default();
    let dormant = sender("dormant.test");
    for day in 0..20 {
        prior.observe(dormant.clone(), &labels(&["work"]), day as f64);
    }
    prior.observe(dormant.clone(), &labels(&["work"]), 560.0);

    assert_eq!(
        reasons(&prior.predict(&dormant, 560.0)),
        [Abstain::TooFewMessages],
        "twenty messages from eighteen months ago should not still be deciding"
    );
}

#[test]
fn an_unseen_sender_is_reported_before_any_label_exists() {
    let prior = Prior::default();

    let prediction = prior.predict(&sender("first-ever.test"), 0.0);

    assert_eq!(reasons(&prediction), [Abstain::UnseenSender]);
    assert!(
        prediction.outcomes.is_empty(),
        "there are no labels to decide yet"
    );
}
