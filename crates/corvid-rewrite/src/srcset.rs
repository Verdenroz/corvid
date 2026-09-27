// A URL may contain commas, so candidates cannot be split on them. This follows the
// HTML parse: the URL runs to whitespace, and a URL ending in commas takes no descriptor.
pub fn rewrite_urls(srcset: &str, mut proxy: impl FnMut(&str) -> Option<String>) -> String {
    let mut candidates = Vec::new();
    let mut rest = srcset;
    loop {
        rest = rest.trim_start_matches(|c: char| c.is_whitespace() || c == ',');
        if rest.is_empty() {
            break;
        }
        let end = rest.find(char::is_whitespace).unwrap_or(rest.len());
        let (token, remainder) = rest.split_at(end);
        let url = token.trim_end_matches(',');

        let (descriptor, remainder) = if token.ends_with(',') {
            ("", remainder)
        } else {
            let end = remainder.find(',').unwrap_or(remainder.len());
            let (descriptor, remainder) = remainder.split_at(end);
            (descriptor.trim(), remainder)
        };

        let url = proxy(url).unwrap_or_else(|| url.to_owned());
        candidates.push(if descriptor.is_empty() {
            url
        } else {
            format!("{url} {descriptor}")
        });
        rest = remainder;
    }
    candidates.join(", ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn upper(srcset: &str) -> String {
        rewrite_urls(srcset, |raw| Some(raw.to_uppercase()))
    }

    #[test]
    fn rewrites_each_candidate_and_keeps_its_descriptor() {
        assert_eq!(upper("a.png 1x, b.png 2x"), "A.PNG 1x, B.PNG 2x");
    }

    #[test]
    fn keeps_a_comma_that_belongs_to_the_url() {
        assert_eq!(
            upper("https://x.test/a,b.png 1x, c.png 2x"),
            "HTTPS://X.TEST/A,B.PNG 1x, C.PNG 2x"
        );
    }

    #[test]
    fn handles_a_candidate_with_no_descriptor() {
        assert_eq!(upper("a.png"), "A.PNG");
        assert_eq!(upper("a.png, b.png"), "A.PNG, B.PNG");
    }

    #[test]
    fn treats_a_trailing_comma_on_the_url_as_the_end_of_the_candidate() {
        assert_eq!(upper("a.png, b.png 2x"), "A.PNG, B.PNG 2x");
    }

    #[test]
    fn tolerates_runs_of_whitespace_and_commas() {
        assert_eq!(upper("  a.png 1x ,,  b.png 2x  "), "A.PNG 1x, B.PNG 2x");
    }

    #[test]
    fn keeps_a_candidate_the_proxy_declines() {
        assert_eq!(rewrite_urls("cid:logo 1x", |_| None), "cid:logo 1x");
    }
}
