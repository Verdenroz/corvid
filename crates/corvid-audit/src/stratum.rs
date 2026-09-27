use corvid_label::{Abstain, Prediction};

/// Which part of the mailbox a message stands for.
///
/// Coarse on purpose: messages are divided by how the prior decided, not what. Splitting by
/// label as well would leave most strata with too few answers to reweight from.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, serde::Serialize, serde::Deserialize,
)]
pub enum Stratum {
    Applied,
    Withheld,
    Undecided(Abstain),
}

/// The stratum a prediction falls in.
///
/// A message that applied something is `Applied` whatever else it abstained on, since a written
/// label is the claim the audit checks. Only a message that wrote nothing is classified by why.
pub fn of(prediction: &Prediction) -> Stratum {
    if !prediction.applied.is_empty() {
        return Stratum::Applied;
    }
    match strongest_abstention(prediction) {
        Some(reason) => Stratum::Undecided(reason),
        None => Stratum::Withheld,
    }
}

/// The reason a message is undecided, taking the first in the order `Abstain` declares.
///
/// A message can abstain for several reasons at once, one per label, but lands in one stratum.
fn strongest_abstention(prediction: &Prediction) -> Option<Abstain> {
    let mut reasons: Vec<Abstain> = prediction.abstentions().collect();
    reasons.sort();
    reasons.first().copied()
}

/// Whether the prior had ever seen this message's sender.
///
/// Reported separately: a number that wins only on known senders says the prior learned the
/// sender list, which is not what is being measured.
pub fn is_unseen(prediction: &Prediction) -> bool {
    prediction
        .abstentions()
        .any(|reason| reason == Abstain::UnseenSender)
}

#[cfg(test)]
mod tests {
    use super::*;
    use corvid_label::Outcome;
    use std::collections::BTreeMap;

    fn decided(applied: &[&str]) -> Prediction {
        Prediction {
            applied: applied.iter().map(|l| (*l).to_owned()).collect(),
            ..Prediction::default()
        }
    }

    fn undecided(reasons: &[Abstain]) -> Prediction {
        let mut outcomes = BTreeMap::new();
        for (index, reason) in reasons.iter().enumerate() {
            outcomes.insert(format!("l{index}"), Outcome::Abstain(*reason));
        }
        Prediction {
            outcomes,
            ..Prediction::default()
        }
    }

    #[test]
    fn a_message_that_got_a_label_is_judged_on_that_label() {
        assert_eq!(of(&decided(&["work"])), Stratum::Applied);
    }

    #[test]
    fn an_abstention_beside_an_applied_label_does_not_move_the_stratum() {
        let mut prediction = decided(&["work"]);
        prediction
            .outcomes
            .insert("family".into(), Outcome::Abstain(Abstain::Ambiguous));

        assert_eq!(of(&prediction), Stratum::Applied);
    }

    #[test]
    fn a_message_that_got_nothing_is_judged_on_why() {
        assert_eq!(
            of(&undecided(&[Abstain::Ambiguous])),
            Stratum::Undecided(Abstain::Ambiguous)
        );
        assert_eq!(of(&Prediction::default()), Stratum::Withheld);
    }

    // Reweighting divides the population by stratum, so one message in two would count twice.
    #[test]
    fn a_message_abstaining_for_two_reasons_lands_in_one_stratum() {
        let prediction = undecided(&[Abstain::Ambiguous, Abstain::UnseenSender]);

        assert_eq!(of(&prediction), Stratum::Undecided(Abstain::UnseenSender));
    }

    #[test]
    fn an_unseen_sender_is_reported_apart_from_its_stratum() {
        assert!(is_unseen(&undecided(&[Abstain::UnseenSender])));
        assert!(!is_unseen(&undecided(&[Abstain::Ambiguous])));
    }
}
