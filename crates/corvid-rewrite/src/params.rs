use url::Url;

// Hand-checked. Rewriting query strings can break a signed link, so this list stays
// short and only holds parameters that carry identity rather than meaning.
const TRACKING: &[&str] = &[
    "gclid",
    "dclid",
    "fbclid",
    "msclkid",
    "twclid",
    "igshid",
    "mc_cid",
    "mc_eid",
    "_hsenc",
    "_hsmi",
    "vero_conv",
    "vero_id",
    "oly_anon_id",
    "oly_enc_id",
    "ck_subscriber_id",
    "sc_customer",
    "sc_eh",
    "ml_subscriber",
    "ml_subscriber_hash",
];

pub fn strip_tracking(url: &mut Url) {
    if url.query().is_none() {
        return;
    }
    let kept: Vec<(String, String)> = url
        .query_pairs()
        .filter(|(name, _)| !is_tracking(name))
        .map(|(name, value)| (name.into_owned(), value.into_owned()))
        .collect();
    if kept.is_empty() {
        url.set_query(None);
    } else {
        url.query_pairs_mut().clear().extend_pairs(kept);
    }
}

fn is_tracking(name: &str) -> bool {
    let name = name.to_ascii_lowercase();
    name.starts_with("utm_") || TRACKING.contains(&name.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stripped(input: &str) -> String {
        let mut url = Url::parse(input).unwrap();
        strip_tracking(&mut url);
        url.to_string()
    }

    #[test]
    fn removes_utm_parameters_and_keeps_the_rest() {
        assert_eq!(
            stripped("https://shop.example/p?id=99&utm_source=news&utm_campaign=spring"),
            "https://shop.example/p?id=99"
        );
    }

    #[test]
    fn removes_the_query_entirely_when_nothing_survives() {
        assert_eq!(
            stripped("https://shop.example/p?utm_source=news"),
            "https://shop.example/p"
        );
    }

    #[test]
    fn leaves_a_url_without_tracking_untouched() {
        let url = "https://shop.example/p?id=99&ref=friend";
        assert_eq!(stripped(url), url);
    }

    #[test]
    fn matches_parameter_names_case_insensitively() {
        assert_eq!(
            stripped("https://shop.example/p?UTM_Source=x&FBCLID=y&k=1"),
            "https://shop.example/p?k=1"
        );
    }

    #[test]
    fn keeps_a_signature_parameter() {
        let url = "https://shop.example/reset?token=abc&sig=def";
        assert_eq!(stripped(url), url);
    }
}
