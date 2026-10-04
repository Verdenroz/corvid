use corvid_rewrite::{Report, Rewriter};

use crate::framing::{rebuild_headers, split_echoed_headers};

#[derive(Debug)]
pub enum Outcome {
    Replace {
        body: Vec<u8>,
        report: Report,
    },
    Unchanged,
    /// The message could not be parsed or rewritten. Deliver it as it is and mark it, so a
    /// message nothing was found in stays distinguishable from one that was given up on.
    Skipped,
}

#[derive(Default)]
pub struct Session {
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl Session {
    pub fn header(&mut self, name: String, value: String) {
        self.headers.push((name, value));
    }

    /// 1-based positions among same-named headers, last first, so deleting one leaves the
    /// positions of those still to be deleted unchanged.
    pub fn header_indexes(&self, name: &str) -> Vec<i32> {
        let count = self
            .headers
            .iter()
            .filter(|(candidate, _)| candidate.eq_ignore_ascii_case(name))
            .count();
        (1..=count as i32).rev().collect()
    }

    pub fn body_chunk(&mut self, chunk: &[u8]) {
        self.body.extend_from_slice(chunk);
    }

    pub fn finish(&self, rewriter: &Rewriter) -> Outcome {
        let names: Vec<String> = self.headers.iter().map(|(name, _)| name.clone()).collect();
        let rebuilt;
        let (headers, body) = match split_echoed_headers(&self.body, &names) {
            Some(split) => split,
            None => {
                rebuilt = rebuild_headers(&self.headers);
                (rebuilt.as_slice(), self.body.as_slice())
            }
        };

        match corvid_mime::rewrite_body(headers, body, rewriter) {
            None => Outcome::Skipped,
            Some(rewritten) if rewritten.report == Report::default() => Outcome::Unchanged,
            Some(rewritten) => Outcome::Replace {
                body: rewritten.body,
                report: rewritten.report,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use corvid_sign::Signer;

    const HTML: &str = r#"<html><body><img src="https://t.example/px.gif" width="1" height="1"><img src="https://cdn.example/a.png"></body></html>"#;

    fn rewriter() -> Rewriter {
        Rewriter::new(Signer::new(b"session test".to_vec()), "https://img.test")
    }

    fn session(headers: &[(&str, &str)], body: &[u8]) -> Session {
        let mut session = Session::default();
        for (name, value) in headers {
            session.header((*name).to_owned(), (*value).to_owned());
        }
        session.body_chunk(body);
        session
    }

    fn replaced(outcome: Outcome) -> (String, Report) {
        match outcome {
            Outcome::Replace { body, report } => (String::from_utf8(body).unwrap(), report),
            other => panic!("expected a replacement, got {other:?}"),
        }
    }

    #[test]
    fn rewrites_a_body_that_arrived_with_the_headers_echoed() {
        let echoed = format!("From: a@b.test\r\nContent-Type: text/html\r\n\r\n{HTML}");
        let outcome = session(
            &[("From", "a@b.test"), ("Content-Type", "text/html")],
            echoed.as_bytes(),
        )
        .finish(&rewriter());
        let (body, report) = replaced(outcome);

        assert!(
            !body.contains("From: a@b.test"),
            "the header block must not reach the body"
        );
        assert!(body.starts_with("<html>"));
        assert_eq!((report.stripped, report.proxied), (1, 1));
    }

    #[test]
    fn rewrites_a_body_that_arrived_on_its_own() {
        let outcome =
            session(&[("Content-Type", "text/html")], HTML.as_bytes()).finish(&rewriter());
        let (body, report) = replaced(outcome);

        assert!(body.starts_with("<html>"));
        assert_eq!((report.stripped, report.proxied), (1, 1));
    }

    #[test]
    fn joins_body_chunks_before_looking_for_the_separator() {
        let echoed = format!("From: a@b.test\r\nContent-Type: text/html\r\n\r\n{HTML}");
        let (first, second) = echoed.as_bytes().split_at(30);
        let mut session = Session::default();
        session.header("From".into(), "a@b.test".into());
        session.header("Content-Type".into(), "text/html".into());
        session.body_chunk(first);
        session.body_chunk(second);

        let (body, _) = replaced(session.finish(&rewriter()));
        assert!(
            body.starts_with("<html>"),
            "a separator split across chunks must still be found"
        );
    }

    #[test]
    fn skips_a_message_whose_html_cannot_be_parsed() {
        let hostile = "<div>".repeat(200_000);
        let outcome =
            session(&[("Content-Type", "text/html")], hostile.as_bytes()).finish(&rewriter());

        assert!(matches!(outcome, Outcome::Skipped), "got {outcome:?}");
    }

    #[test]
    fn reports_a_message_with_nothing_to_rewrite_as_unchanged() {
        let outcome =
            session(&[("Content-Type", "text/plain")], b"nothing here\r\n").finish(&rewriter());

        assert!(matches!(outcome, Outcome::Unchanged), "got {outcome:?}");
    }
}
