use std::collections::BTreeMap;

use crate::stratum::Stratum;

/// What came back from one stratum.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Answers {
    pub agreed: usize,
    pub answered: usize,
}

/// What was asked, carried on the answer so two different questions cannot be averaged.
///
/// Whether mail is predictable from its content has to be asked blind, or a person reads the
/// prediction off the sender. Whether an applied label is correct has to be asked with the
/// sender visible, because who sent something is part of what it is. Pooled, the figure answers
/// neither.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Asked {
    /// Sender and List-Id were hidden, so the answer cannot be a lookup of the sender table.
    pub blinded: bool,
    /// The prior had seen this sender when the question was drawn, so its other mail carries
    /// the prediction whatever this question showed.
    pub sender_known: bool,
}

/// One stratum's own result, which needs no reweighting to be honest.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Measured {
    pub rate: f64,
    pub answered: usize,
    /// Share of the mailbox this stratum holds, and so its pull on the pooled rate.
    ///
    /// Zero when the mailbox has no messages left in it; the rate is then history, kept because
    /// the oversampling paid for it. Shares sum to `covered`, not to one.
    pub share: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Estimate {
    pub asked: Asked,
    /// Share of the mailbox the prior gets right, over the part of it this estimate can see.
    ///
    /// The fragile figure. Equal draws make the heaviest stratum the least sampled, yet it
    /// dominates this number. Read it beside `effective`, and prefer `strata`.
    pub rate: f64,
    /// How many answers `rate` is really worth.
    ///
    /// Equals `answered` when the draws matched the weights, and collapses when they did not.
    pub effective: f64,
    /// Answers that informed `rate`. Never the number collected, which can be larger.
    pub answered: usize,
    /// Answers held for strata the mailbox no longer has any messages in.
    ///
    /// The oversampled strata are the ones that drain, as senders cross the evidence floor
    /// and stop being unseen. `covered` reads 1.0 while these are being dropped.
    pub stale: usize,
    /// Share of the population lying in strata that returned at least one answer.
    ///
    /// A stratum nobody answered drops out of the rate entirely, so a rate quoted without this
    /// describes an unstated part of the mailbox.
    pub covered: f64,
    /// Every stratum that returned an answer, including ones the mailbox has since emptied.
    ///
    /// Answer counts here sum to `answered + stale`, not to `answered`, because the emptied
    /// strata are present and contribute to neither the rate nor the coverage.
    pub strata: BTreeMap<Stratum, Measured>,
}

/// Scales each stratum's answers back to the share of the mailbox it stands for.
///
/// The sample over-represents the thin strata, so the raw agreement rate is not the mailbox's.
/// Weighting by stratum size undoes the oversampling.
///
/// Unanswered strata are left out of both sides of the division rather than counted as
/// disagreement, or the rate would fall whenever nobody had time to answer.
///
/// One call is one question shape. Answers from blinded and unblinded questions, or from known
/// and unknown senders, are separate populations and belong in separate calls.
///
/// `sizes` is the mailbox now and `answers` accumulates, so the two drift apart by design.
pub fn reweight(
    asked: Asked,
    sizes: &BTreeMap<Stratum, usize>,
    answers: &BTreeMap<Stratum, Answers>,
) -> Estimate {
    let population: usize = sizes.values().sum();
    let mut represented = 0.0;
    let mut weighted = 0.0;
    let mut answered = 0;
    let mut stale = 0;
    let mut inverse = 0.0;
    let mut strata = BTreeMap::new();

    for (stratum, reply) in answers {
        if reply.answered == 0 {
            continue;
        }
        // An emptied stratum's answers go to `stale`, not silently into a zero-weight rate.
        match sizes.get(stratum) {
            Some(&size) if size > 0 => {
                let rate = reply.agreed as f64 / reply.answered as f64;
                weighted += size as f64 * rate;
                represented += size as f64;
                answered += reply.answered;
                inverse += (size as f64).powi(2) / reply.answered as f64;
                strata.insert(
                    *stratum,
                    Measured {
                        rate,
                        answered: reply.answered,
                        share: if population > 0 {
                            size as f64 / population as f64
                        } else {
                            0.0
                        },
                    },
                );
            }
            // Kept at zero share: the oversampled strata are the ones that empty.
            _ => {
                stale += reply.answered;
                strata.insert(
                    *stratum,
                    Measured {
                        rate: reply.agreed as f64 / reply.answered as f64,
                        answered: reply.answered,
                        share: 0.0,
                    },
                );
            }
        }
    }

    Estimate {
        asked,
        rate: if represented > 0.0 {
            weighted / represented
        } else {
            0.0
        },
        effective: if inverse > 0.0 {
            represented.powi(2) / inverse
        } else {
            0.0
        },
        answered,
        stale,
        strata,
        covered: if population > 0 {
            represented / population as f64
        } else {
            0.0
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use corvid_label::Abstain;

    const AMBIGUOUS: Stratum = Stratum::Undecided(Abstain::Ambiguous);
    const ASKED: Asked = Asked {
        blinded: true,
        sender_known: false,
    };

    fn sizes(entries: &[(Stratum, usize)]) -> BTreeMap<Stratum, usize> {
        entries.iter().copied().collect()
    }

    fn answers(entries: &[(Stratum, usize, usize)]) -> BTreeMap<Stratum, Answers> {
        entries
            .iter()
            .map(|(stratum, agreed, answered)| {
                (
                    *stratum,
                    Answers {
                        agreed: *agreed,
                        answered: *answered,
                    },
                )
            })
            .collect()
    }

    // The raw agreement across the twenty answers is 0.55; the mailbox's is nearly the big one's.
    #[test]
    fn the_oversampled_stratum_is_scaled_back_to_its_share() {
        let estimate = reweight(
            ASKED,
            &sizes(&[(Stratum::Applied, 9_900), (AMBIGUOUS, 100)]),
            &answers(&[(Stratum::Applied, 10, 10), (AMBIGUOUS, 1, 10)]),
        );

        assert!(
            (estimate.rate - 0.991).abs() < 1e-9,
            "got {}",
            estimate.rate
        );
        assert_eq!(estimate.answered, 20);
        assert_eq!(estimate.covered, 1.0);
        assert_eq!(estimate.stale, 0);
    }

    #[test]
    fn an_unanswered_stratum_lowers_the_coverage_and_not_the_rate() {
        let estimate = reweight(
            ASKED,
            &sizes(&[(Stratum::Applied, 750), (AMBIGUOUS, 250)]),
            &answers(&[(Stratum::Applied, 10, 10)]),
        );

        assert_eq!(estimate.rate, 1.0);
        assert_eq!(estimate.covered, 0.75);
    }

    #[test]
    fn a_stratum_that_was_drawn_but_not_answered_is_not_counted() {
        let estimate = reweight(
            ASKED,
            &sizes(&[(Stratum::Applied, 100), (AMBIGUOUS, 100)]),
            &answers(&[(Stratum::Applied, 5, 10), (AMBIGUOUS, 0, 0)]),
        );

        assert_eq!(estimate.rate, 0.5);
        assert_eq!(estimate.covered, 0.5);
    }

    #[test]
    fn nothing_answered_is_no_estimate_rather_than_a_perfect_one() {
        let estimate = reweight(ASKED, &sizes(&[(Stratum::Applied, 100)]), &BTreeMap::new());

        assert_eq!(estimate.rate, 0.0);
        assert_eq!(estimate.covered, 0.0);
        assert_eq!(estimate.answered, 0);
    }

    #[test]
    fn an_empty_mailbox_yields_no_estimate() {
        let estimate = reweight(ASKED, &BTreeMap::new(), &BTreeMap::new());

        assert_eq!(estimate.covered, 0.0);
        assert!(estimate.rate.is_finite());
    }

    #[test]
    fn answers_from_a_stratum_that_has_since_emptied_are_reported_not_absorbed() {
        const TOO_FEW: Stratum = Stratum::Undecided(Abstain::TooFewMessages);

        let estimate = reweight(
            ASKED,
            &sizes(&[(Stratum::Applied, 100)]),
            &answers(&[(Stratum::Applied, 5, 10), (TOO_FEW, 12, 12)]),
        );

        assert_eq!(estimate.rate, 0.5);
        assert_eq!(estimate.answered, 10, "only the ten that were weighted");
        assert_eq!(estimate.stale, 12);
        assert_eq!(estimate.covered, 1.0, "the mailbox as it stands is covered");
        assert_eq!(
            estimate.strata[&TOO_FEW].rate, 1.0,
            "its rate is still readable"
        );
        assert_eq!(
            estimate.strata[&TOO_FEW].share, 0.0,
            "and weighs nothing now"
        );
        assert_eq!(
            estimate.strata.values().map(|m| m.answered).sum::<usize>(),
            estimate.answered + estimate.stale
        );
    }

    #[test]
    fn nothing_left_to_weigh_is_not_reported_as_disagreement() {
        const TOO_FEW: Stratum = Stratum::Undecided(Abstain::TooFewMessages);

        let estimate = reweight(
            ASKED,
            &sizes(&[(AMBIGUOUS, 100)]),
            &answers(&[(TOO_FEW, 12, 12)]),
        );

        assert_eq!(estimate.answered, 0);
        assert_eq!(estimate.stale, 12);
        assert_eq!(
            reweight(ASKED, &sizes(&[(AMBIGUOUS, 100)]), &BTreeMap::new()).stale,
            0,
            "and no answers at all is a different state"
        );
    }

    // Fully covered, nothing stale, fifty-one answers: fifty measure one percent of the mailbox.
    #[test]
    fn a_rate_resting_on_one_answer_says_so() {
        let estimate = reweight(
            ASKED,
            &sizes(&[(Stratum::Applied, 9_900), (AMBIGUOUS, 100)]),
            &answers(&[(Stratum::Applied, 1, 1), (AMBIGUOUS, 50, 50)]),
        );

        assert_eq!(estimate.answered, 51);
        assert_eq!(estimate.stale, 0);
        assert_eq!(estimate.covered, 1.0);
        assert!(
            (estimate.effective - 1.0203).abs() < 1e-4,
            "got {}",
            estimate.effective
        );
    }

    #[test]
    fn draws_matching_the_weights_lose_nothing() {
        let estimate = reweight(
            ASKED,
            &sizes(&[(Stratum::Applied, 5_000), (AMBIGUOUS, 5_000)]),
            &answers(&[(Stratum::Applied, 14, 20), (AMBIGUOUS, 14, 20)]),
        );

        assert_eq!(estimate.answered, 40);
        assert!((estimate.effective - 40.0).abs() < 1e-9);
    }

    #[test]
    fn each_stratum_reports_its_own_rate_and_its_pull() {
        let estimate = reweight(
            ASKED,
            &sizes(&[(Stratum::Applied, 9_900), (AMBIGUOUS, 100)]),
            &answers(&[(Stratum::Applied, 1, 1), (AMBIGUOUS, 25, 50)]),
        );

        assert_eq!(estimate.strata[&AMBIGUOUS].rate, 0.5);
        assert_eq!(estimate.strata[&AMBIGUOUS].answered, 50);
        assert!((estimate.strata[&AMBIGUOUS].share - 0.01).abs() < 1e-9);
        assert!(!estimate.strata.contains_key(&Stratum::Withheld));
    }
}
