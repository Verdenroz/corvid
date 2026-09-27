use std::collections::BTreeMap;

use crate::stratum::Stratum;

/// Spreads a budget of questions across strata, equally rather than in proportion.
///
/// Proportional sampling spends almost everything on the stratum the prior already decides
/// confidently, which needs the fewest answers. Equal draws oversample the thin strata, and
/// `reweight` undoes that afterwards.
///
/// A stratum never yields more than it holds, and the draws it cannot take go to the others.
pub fn allocate(sizes: &BTreeMap<Stratum, usize>, budget: usize) -> BTreeMap<Stratum, usize> {
    let mut drawn: BTreeMap<Stratum, usize> = BTreeMap::new();
    let mut spent = 0;

    while spent < budget {
        let mut took_any = false;
        for (stratum, size) in sizes {
            if spent == budget {
                break;
            }
            let taken = drawn.entry(*stratum).or_default();
            if *taken < *size {
                *taken += 1;
                spent += 1;
                took_any = true;
            }
        }
        if !took_any {
            break;
        }
    }

    drawn.retain(|_, taken| *taken > 0);
    drawn
}

#[cfg(test)]
mod tests {
    use super::*;
    use corvid_label::Abstain;

    fn sizes(entries: &[(Stratum, usize)]) -> BTreeMap<Stratum, usize> {
        entries.iter().copied().collect()
    }

    const AMBIGUOUS: Stratum = Stratum::Undecided(Abstain::Ambiguous);

    #[test]
    fn a_thin_stratum_is_sampled_as_heavily_as_a_thick_one() {
        let drawn = allocate(&sizes(&[(Stratum::Applied, 10_000), (AMBIGUOUS, 40)]), 20);

        assert_eq!(drawn[&Stratum::Applied], 10);
        assert_eq!(drawn[&AMBIGUOUS], 10);
    }

    #[test]
    fn a_stratum_never_yields_more_than_it_holds() {
        let drawn = allocate(&sizes(&[(Stratum::Applied, 100), (AMBIGUOUS, 3)]), 20);

        assert_eq!(drawn[&AMBIGUOUS], 3);
        assert_eq!(drawn[&Stratum::Applied], 17, "the rest go where they fit");
    }

    #[test]
    fn a_budget_larger_than_the_population_takes_all_of_it() {
        let drawn = allocate(&sizes(&[(Stratum::Applied, 2), (AMBIGUOUS, 1)]), 500);

        assert_eq!(drawn.values().sum::<usize>(), 3);
    }

    #[test]
    fn an_empty_stratum_is_not_listed() {
        let drawn = allocate(&sizes(&[(Stratum::Applied, 5), (AMBIGUOUS, 0)]), 4);

        assert!(!drawn.contains_key(&AMBIGUOUS));
        assert_eq!(drawn[&Stratum::Applied], 4);
    }

    #[test]
    fn nothing_to_draw_from_yields_nothing() {
        assert!(allocate(&BTreeMap::new(), 10).is_empty());
        assert!(allocate(&sizes(&[(Stratum::Applied, 10)]), 0).is_empty());
    }
}
