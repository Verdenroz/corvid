use std::collections::{BTreeMap, BTreeSet};

use crate::estimate::Answers;
use crate::stratum::Stratum;

/// One question the user answered.
///
/// Stores what the user said the message is, not whether that matched: the prior moves, and a
/// stored truth can be scored again whenever the prediction changes.
///
/// `predicted` and `stratum` are the prior as it stood when the question was drawn, and neither
/// is recoverable afterwards.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Answer {
    pub message_id: String,
    pub stratum: Stratum,
    pub predicted: BTreeSet<String>,
    pub truth: BTreeSet<String>,
}

impl Answer {
    /// Whether the prior got the message exactly right.
    ///
    /// Exact: a partial overlap is a different measurement. A label missed and a label invented
    /// both count against.
    pub fn agreed(&self) -> bool {
        self.predicted == self.truth
    }
}

/// Groups answers by the stratum they were drawn from.
pub fn tally(answers: &[Answer]) -> BTreeMap<Stratum, Answers> {
    let mut tallied: BTreeMap<Stratum, Answers> = BTreeMap::new();
    for answer in answers {
        let entry = tallied.entry(answer.stratum).or_default();
        entry.answered += 1;
        entry.agreed += usize::from(answer.agreed());
    }
    tallied
}

#[cfg(test)]
mod tests {
    use super::*;
    use corvid_label::Abstain;

    fn answer(stratum: Stratum, predicted: &[&str], truth: &[&str]) -> Answer {
        Answer {
            message_id: "m1".into(),
            stratum,
            predicted: predicted.iter().map(|l| (*l).to_owned()).collect(),
            truth: truth.iter().map(|l| (*l).to_owned()).collect(),
        }
    }

    #[test]
    fn a_prediction_matching_the_truth_agrees() {
        assert!(answer(Stratum::Applied, &["work"], &["work"]).agreed());
        assert!(answer(Stratum::Withheld, &[], &[]).agreed());
    }

    #[test]
    fn a_missed_label_counts_against_as_much_as_an_invented_one() {
        assert!(!answer(Stratum::Applied, &["work"], &["work", "family"]).agreed());
        assert!(!answer(Stratum::Applied, &["work", "family"], &["work"]).agreed());
    }

    #[test]
    fn answers_are_grouped_by_where_they_were_drawn_from() {
        const AMBIGUOUS: Stratum = Stratum::Undecided(Abstain::Ambiguous);
        let tallied = tally(&[
            answer(Stratum::Applied, &["work"], &["work"]),
            answer(Stratum::Applied, &["work"], &["family"]),
            answer(AMBIGUOUS, &[], &["work"]),
        ]);

        assert_eq!(tallied[&Stratum::Applied].answered, 2);
        assert_eq!(tallied[&Stratum::Applied].agreed, 1);
        assert_eq!(tallied[&AMBIGUOUS].agreed, 0);
    }

    #[test]
    fn an_answer_keeps_the_stratum_it_was_drawn_from() {
        let held = answer(Stratum::Undecided(Abstain::TooFewMessages), &[], &["work"]);

        assert_eq!(
            tally(std::slice::from_ref(&held)).keys().next(),
            Some(&held.stratum)
        );
    }
}
