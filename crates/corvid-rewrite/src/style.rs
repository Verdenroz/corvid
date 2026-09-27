pub fn rewrite_urls(style: &str, mut proxy: impl FnMut(&str) -> Option<String>) -> String {
    let mut out = String::with_capacity(style.len());
    let mut rest = style;
    while let Some(start) = rest.find("url(") {
        let (before, after) = rest.split_at(start + 4);
        out.push_str(before);
        let Some(end) = after.find(')') else {
            rest = after;
            break;
        };
        let (raw, remainder) = after.split_at(end);
        let quote = raw
            .trim()
            .chars()
            .next()
            .filter(|c| *c == '\'' || *c == '"');
        let bare = raw.trim().trim_matches(|c| c == '\'' || c == '"');
        match proxy(bare) {
            Some(proxied) => match quote {
                Some(quote) => out.push_str(&format!("{quote}{proxied}{quote}")),
                None => out.push_str(&proxied),
            },
            None => out.push_str(raw),
        }
        rest = remainder;
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn upper(style: &str) -> String {
        rewrite_urls(style, |raw| Some(raw.to_uppercase()))
    }

    #[test]
    fn rewrites_a_bare_url() {
        assert_eq!(upper("background:url(a.png)"), "background:url(A.PNG)");
    }

    #[test]
    fn keeps_the_original_quoting() {
        assert_eq!(upper("background:url('a.png')"), "background:url('A.PNG')");
        assert_eq!(
            upper(r#"background:url("a.png")"#),
            r#"background:url("A.PNG")"#
        );
    }

    #[test]
    fn rewrites_every_url_in_a_declaration() {
        assert_eq!(
            upper("background:url(a.png);border-image:url(b.png)"),
            "background:url(A.PNG);border-image:url(B.PNG)"
        );
    }

    #[test]
    fn leaves_a_declaration_without_urls_alone() {
        assert_eq!(upper("color:red;width:20px"), "color:red;width:20px");
    }

    #[test]
    fn leaves_an_unclosed_url_alone() {
        assert_eq!(upper("background:url(a.png"), "background:url(a.png");
    }

    #[test]
    fn keeps_a_url_the_proxy_declines() {
        let style = "background:url(cid:logo)";
        assert_eq!(rewrite_urls(style, |_| None), style);
    }
}
