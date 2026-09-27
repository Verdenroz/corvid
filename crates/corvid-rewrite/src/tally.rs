use std::collections::BTreeMap;

use crate::header::Marked;

/// What the mailbox says about the rewrite, one message at a time.
///
/// Every message lands in exactly one of the first five counts, so they sum to the number
/// offered. A message with no header is `unmarked`, never `clean`: only a header says the milter
/// examined it.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Tally {
    /// No header at all. The milter was down, bypassed, or not yet configured for this message.
    pub unmarked: usize,
    /// A header nothing here can read. Counted, so a reader behind the writer does not report
    /// a mailbox getting cleaner.
    pub unreadable: usize,
    pub clean: usize,
    pub skipped: usize,
    pub rewritten: usize,
    pub stripped: usize,
    pub proxied: usize,
    /// Messages that named each tracker host, not appearances. A host with three beacons in one
    /// message reached the reader once.
    pub by_host: BTreeMap<String, usize>,
    /// Tracker names the headers had no room to carry. `by_host` is short by this much.
    pub unnamed: usize,
}

impl Tally {
    /// Adds one message, whose header is `None` when it carries none.
    pub fn add(&mut self, header: Option<&str>) {
        let Some(value) = header else {
            self.unmarked += 1;
            return;
        };
        match Marked::parse(value) {
            None => self.unreadable += 1,
            Some(Marked::Nothing) => self.clean += 1,
            Some(Marked::Skipped) => self.skipped += 1,
            Some(Marked::Rewritten(summary)) => {
                self.rewritten += 1;
                self.stripped += summary.stripped;
                self.proxied += summary.proxied;
                self.unnamed += summary.omitted;
                for host in &summary.named {
                    *self.by_host.entry(host.clone()).or_default() += 1;
                }
            }
        }
    }

    pub fn messages(&self) -> usize {
        self.unmarked + self.unreadable + self.clean + self.skipped + self.rewritten
    }

    /// Hosts by how many messages reached for them, most first.
    pub fn ranked(&self) -> Vec<(&str, usize)> {
        let mut ranked: Vec<(&str, usize)> = self
            .by_host
            .iter()
            .map(|(host, count)| (host.as_str(), *count))
            .collect();
        ranked.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(b.0)));
        ranked
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Report;
    use crate::header::Summary;

    fn rewritten(trackers: &[&str]) -> String {
        Marked::Rewritten(Summary::of(&Report {
            stripped: trackers.len(),
            proxied: 2,
            hosts: trackers.iter().map(|t| (*t).to_owned()).collect(),
            trackers: trackers.iter().map(|t| (*t).to_owned()).collect(),
        }))
        .render()
    }

    fn tallied(headers: &[Option<String>]) -> Tally {
        let mut tally = Tally::default();
        for header in headers {
            tally.add(header.as_deref());
        }
        tally
    }

    #[test]
    fn a_message_with_no_header_is_not_a_clean_message() {
        let tally = tallied(&[None, Some(Marked::Nothing.render())]);

        assert_eq!((tally.unmarked, tally.clean), (1, 1));
    }

    #[test]
    fn a_header_this_cannot_read_is_counted_apart() {
        let tally = tallied(&[Some("stripped=1 from-a-later-version=2".into())]);

        assert_eq!(tally.unreadable, 1);
        assert_eq!(tally.clean, 0);
        assert_eq!(tally.messages(), 1);
    }

    #[test]
    fn every_message_lands_in_exactly_one_count() {
        let tally = tallied(&[
            None,
            Some("nonsense".into()),
            Some(Marked::Nothing.render()),
            Some(Marked::Skipped.render()),
            Some(rewritten(&["px.tracker.test"])),
        ]);

        assert_eq!(tally.messages(), 5);
        assert_eq!(
            (tally.unmarked, tally.unreadable, tally.clean, tally.skipped),
            (1, 1, 1, 1)
        );
    }

    #[test]
    fn a_host_is_ranked_by_messages_not_appearances() {
        let tally = tallied(&[
            Some(rewritten(&["a.tracker.test", "b.tracker.test"])),
            Some(rewritten(&["a.tracker.test"])),
            Some(rewritten(&["a.tracker.test"])),
        ]);

        assert_eq!(
            tally.ranked(),
            [("a.tracker.test", 3), ("b.tracker.test", 1)]
        );
    }

    #[test]
    fn the_totals_add_up_across_messages() {
        let tally = tallied(&[
            Some(rewritten(&["a.tracker.test"])),
            Some(rewritten(&["b.tracker.test"])),
        ]);

        assert_eq!((tally.rewritten, tally.stripped, tally.proxied), (2, 2, 4));
    }

    #[test]
    fn names_the_headers_could_not_carry_are_counted() {
        let many: Vec<String> = (0..200).map(|n| format!("t{n:03}.tracker.test")).collect();
        let names: Vec<&str> = many.iter().map(String::as_str).collect();

        let tally = tallied(&[Some(rewritten(&names))]);

        assert!(tally.unnamed > 0);
        assert_eq!(tally.by_host.len() + tally.unnamed, 200);
    }
}
