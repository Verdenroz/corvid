/// Whether the receiving server authenticated the message, read from `Authentication-Results`.
///
/// Only the FIRST header counts. The receiving server prepends its own, and everything below it
/// arrived with the message and may be forged. The authserv-id does not discriminate, because a
/// forger copies it.
pub fn dmarc_passed(authentication_results: &[&str]) -> bool {
    let Some(ours) = authentication_results.first() else {
        return false;
    };
    ours.split(|c: char| c == ';' || c.is_whitespace())
        .filter_map(|token| token.split_once('='))
        .any(|(method, value)| {
            method.trim().eq_ignore_ascii_case("dmarc") && value.trim().eq_ignore_ascii_case("pass")
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    const GENUINE_PASS: &str = "mail.corvid.test; spf=pass smtp.mailfrom=news.example; dmarc=pass header.from=news.example";
    const GENUINE_NONE: &str = "mail.corvid.test; spf=none; dmarc=none header.from=evil.example";
    // Same authserv-id as the genuine one: copying it is free.
    const FORGED_PASS: &str = "mail.corvid.test; dkim=pass; dmarc=pass header.from=trusted.example";

    #[test]
    fn reads_a_pass_out_of_a_full_results_header() {
        assert!(dmarc_passed(&[GENUINE_PASS]));
    }

    #[test]
    fn a_forged_pass_below_our_own_verdict_is_ignored() {
        assert!(
            !dmarc_passed(&[GENUINE_NONE, FORGED_PASS]),
            "only the header our server prepended counts"
        );
    }

    #[test]
    fn a_forged_header_cannot_be_smuggled_by_repeating_it() {
        assert!(!dmarc_passed(&[
            GENUINE_NONE,
            FORGED_PASS,
            FORGED_PASS,
            FORGED_PASS
        ]));
    }

    #[test]
    fn our_own_pass_still_counts_with_older_ones_below_it() {
        assert!(dmarc_passed(&[GENUINE_PASS, GENUINE_NONE]));
    }

    #[test]
    fn a_failure_is_not_a_pass() {
        assert!(!dmarc_passed(&[
            "mail.corvid.test; dmarc=fail header.from=news.example"
        ]));
        assert!(!dmarc_passed(&[GENUINE_NONE]));
    }

    #[test]
    fn other_methods_passing_is_not_dmarc_passing() {
        assert!(!dmarc_passed(&["mail.corvid.test; spf=pass; dkim=pass"]));
    }

    #[test]
    fn a_missing_header_is_not_a_pass() {
        assert!(!dmarc_passed(&[]));
        assert!(!dmarc_passed(&[""]));
    }

    #[test]
    fn a_value_that_merely_starts_with_pass_is_not_a_pass() {
        assert!(!dmarc_passed(&["mail.corvid.test; dmarc=passthrough"]));
    }

    #[test]
    fn folds_case() {
        assert!(dmarc_passed(&["mx; DMARC=PASS header.from=x.test"]));
    }
}
