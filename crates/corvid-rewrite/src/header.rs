//! The `X-Corvid-Rewrite` header, written at delivery and read back by the tracker report.
//!
//! Rendering and parsing share one type. The header is the only record of what the rewrite
//! removed, and a reader that disagrees with the writer reports a clean mailbox, not an error.

use std::collections::BTreeSet;

use crate::Report;

/// Byte budget for tracker names in the header value. Names past it are counted in `omitted`.
const MAX_NAMES: usize = 512;

const NOTHING: &str = "none";
const SKIPPED: &str = "skipped";

/// What the milter did to one message.
///
/// Every message the milter sees gets one, including those left alone, so a missing header
/// means the milter never ran.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Marked {
    Nothing,
    Skipped,
    Rewritten(Summary),
}

/// What a rewritten message's header carries.
///
/// Names the stripped trackers, since the archived body no longer contains them. Proxied hosts
/// survive inside their signed URLs, so they are counted rather than named.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Summary {
    pub stripped: usize,
    pub proxied: usize,
    pub hosts: usize,
    pub trackers: usize,
    pub named: BTreeSet<String>,
    /// Tracker names the header had no room for. Non-zero means `named` is a subset.
    pub omitted: usize,
}

impl Summary {
    pub fn of(report: &Report) -> Self {
        let names: Vec<&str> = report
            .trackers
            .iter()
            .map(String::as_str)
            .filter(|host| is_hostname(host))
            .collect();

        let mut named = BTreeSet::new();
        let mut used = 0;
        for name in &names {
            let cost = name.len() + usize::from(!named.is_empty());
            if used + cost > MAX_NAMES {
                break;
            }
            used += cost;
            named.insert((*name).to_owned());
        }

        Self {
            stripped: report.stripped,
            proxied: report.proxied,
            hosts: report.hosts.len(),
            trackers: report.trackers.len(),
            // Counted against every tracker, so an unnameable host is also declared missing.
            omitted: report.trackers.len() - named.len(),
            named,
        }
    }
}

impl Marked {
    pub fn render(&self) -> String {
        let summary = match self {
            Self::Nothing => return NOTHING.to_owned(),
            Self::Skipped => return SKIPPED.to_owned(),
            Self::Rewritten(summary) => summary,
        };

        let mut value = format!(
            "stripped={} proxied={} hosts={} trackers={}",
            summary.stripped, summary.proxied, summary.hosts, summary.trackers
        );
        if !summary.named.is_empty() {
            value.push_str(" tracker-hosts=");
            value.push_str(&summary.named.iter().cloned().collect::<Vec<_>>().join(","));
        }
        if summary.omitted > 0 {
            value.push_str(&format!(" tracker-hosts-omitted={}", summary.omitted));
        }
        value
    }

    /// Returns `None` for anything this did not write, including a header missing a field:
    /// reading a missing field as zero would count an unparsed header as a clean message.
    pub fn parse(value: &str) -> Option<Self> {
        let value = value.trim();
        match value {
            NOTHING => return Some(Self::Nothing),
            SKIPPED => return Some(Self::Skipped),
            _ => {}
        }

        let mut summary = Summary::default();
        let mut seen = 0;
        for field in value.split_whitespace() {
            let (key, raw) = field.split_once('=')?;
            match key {
                "stripped" => (summary.stripped, seen) = (raw.parse().ok()?, seen | 1),
                "proxied" => (summary.proxied, seen) = (raw.parse().ok()?, seen | 2),
                "hosts" => (summary.hosts, seen) = (raw.parse().ok()?, seen | 4),
                "trackers" => (summary.trackers, seen) = (raw.parse().ok()?, seen | 8),
                "tracker-hosts" => {
                    summary.named = raw.split(',').map(str::to_owned).collect();
                }
                "tracker-hosts-omitted" => summary.omitted = raw.parse().ok()?,
                _ => return None,
            }
        }
        (seen == 15).then_some(Self::Rewritten(summary))
    }
}

/// Accepts only characters that cannot break the header value; a line break would start a
/// forged header. Hosts from a parsed URL already pass, and this is where that is enforced.
fn is_hostname(host: &str) -> bool {
    !host.is_empty()
        && host
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | ':' | '[' | ']'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn report(trackers: &[&str], hosts: usize) -> Report {
        Report {
            stripped: trackers.len(),
            proxied: 3,
            hosts: (0..hosts).map(|n| format!("h{n}.example")).collect(),
            trackers: trackers.iter().map(|t| (*t).to_owned()).collect(),
        }
    }

    fn rendered(trackers: &[&str], hosts: usize) -> String {
        Marked::Rewritten(Summary::of(&report(trackers, hosts))).render()
    }

    #[test]
    fn the_counts_come_first() {
        assert!(rendered(&["px.tracker.test"], 4).starts_with("stripped=1 proxied=3 hosts=4"));
    }

    #[test]
    fn a_stripped_trackers_name_is_carried() {
        let value = rendered(&["px.tracker.test", "beacon.ads.test"], 4);

        assert!(value.contains("tracker-hosts=beacon.ads.test,px.tracker.test"));
    }

    #[test]
    fn a_message_with_no_trackers_names_none() {
        assert!(!rendered(&[], 2).contains("tracker-hosts"));
    }

    #[test]
    fn everything_written_reads_back_the_same() {
        let many: Vec<String> = (0..200).map(|n| format!("t{n:03}.tracker.test")).collect();
        let names: Vec<&str> = many.iter().map(String::as_str).collect();

        for marked in [
            Marked::Nothing,
            Marked::Skipped,
            Marked::Rewritten(Summary::of(&report(&[], 0))),
            Marked::Rewritten(Summary::of(&report(&["px.tracker.test"], 4))),
            Marked::Rewritten(Summary::of(&report(&names, 200))),
        ] {
            assert_eq!(
                Marked::parse(&marked.render()),
                Some(marked.clone()),
                "{}",
                marked.render()
            );
        }
    }

    #[test]
    fn a_flood_of_hosts_is_capped_and_says_how_many_it_dropped() {
        let many: Vec<String> = (0..200).map(|n| format!("t{n:03}.tracker.test")).collect();
        let names: Vec<&str> = many.iter().map(String::as_str).collect();
        let summary = Summary::of(&report(&names, 200));

        assert!(summary.omitted > 0);
        assert_eq!(summary.named.len() + summary.omitted, 200);
    }

    #[test]
    fn a_host_that_could_forge_a_header_is_not_named() {
        let mut source = report(&["good.test"], 1);
        source
            .trackers
            .insert("evil.test\r\nX-Spam-Status: No".into());

        let summary = Summary::of(&source);
        let value = Marked::Rewritten(summary.clone()).render();

        assert!(!value.contains('\r') && !value.contains('\n'));
        assert!(value.contains("good.test"));
        assert_eq!(
            summary.named.len() + summary.omitted,
            summary.trackers,
            "a name dropped for any reason is still declared missing"
        );
    }

    #[test]
    fn a_header_from_somewhere_else_is_refused() {
        for value in [
            "",
            "yes",
            "stripped=1",
            "stripped=1 proxied=1 hosts=1",
            "stripped=x proxied=1 hosts=1 trackers=1",
            "stripped=1 proxied=1 hosts=1 trackers=1 extra=2",
        ] {
            assert_eq!(Marked::parse(value), None, "{value:?} was accepted");
        }
    }
}
