/// How a message was keyed for the sender prior.
///
/// Bulk senders rotate the From local part per campaign, so keying on the full address would
/// report almost all bulk mail as unseen. `List-Id` survives that rotation. The key used is
/// recorded so the counters can be re-sliced without re-deriving them.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub enum SenderKey {
    ListId(String),
    Domain(String),
}

impl SenderKey {
    /// `authenticated_list_id` must be `None` unless the caller has established that the
    /// `List-Id` belongs to the sender. Neither `List-Id` nor the From domain is
    /// authenticated in itself, and `List-Id` takes precedence here, so any sender could
    /// otherwise claim a list's key and inherit the rates learned for it.
    pub fn derive(from: &str, authenticated_list_id: Option<&str>) -> Option<Self> {
        if let Some(list_id) = authenticated_list_id.and_then(bracketed) {
            return Some(Self::ListId(list_id));
        }
        domain_of(from).map(Self::Domain)
    }
}

impl std::fmt::Display for SenderKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::ListId(id) => write!(f, "list:{id}"),
            Self::Domain(domain) => write!(f, "domain:{domain}"),
        }
    }
}

impl std::str::FromStr for SenderKey {
    type Err = ();

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value.split_once(':') {
            Some(("list", id)) => Ok(Self::ListId(id.to_owned())),
            Some(("domain", domain)) => Ok(Self::Domain(domain.to_owned())),
            _ => Err(()),
        }
    }
}

impl serde::Serialize for SenderKey {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.collect_str(self)
    }
}

impl<'de> serde::Deserialize<'de> for SenderKey {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let text = String::deserialize(deserializer)?;
        text.parse()
            .map_err(|()| serde::de::Error::custom("not a sender key"))
    }
}

fn bracketed(value: &str) -> Option<String> {
    let value = value.trim();
    let inner = match (value.find('<'), value.rfind('>')) {
        (Some(open), Some(close)) if close > open + 1 => &value[open + 1..close],
        _ => value,
    };
    let inner = inner.trim().trim_matches('"').trim();
    (!inner.is_empty()).then(|| inner.to_ascii_lowercase())
}

fn domain_of(from: &str) -> Option<String> {
    let address = match (from.rfind('<'), from.rfind('>')) {
        (Some(open), Some(close)) if close > open + 1 => &from[open + 1..close],
        _ => from,
    };
    let domain = address.trim().rsplit_once('@')?.1.trim();
    let usable = !domain.is_empty()
        && !domain.contains(|c: char| c.is_whitespace() || matches!(c, '<' | '>' | '@'));
    usable.then(|| domain.to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(from: &str, list_id: Option<&str>) -> Option<SenderKey> {
        SenderKey::derive(from, list_id)
    }

    #[test]
    fn prefers_the_list_id_when_present() {
        assert_eq!(
            key(
                "bounce-12345-abc@mail.example.com",
                Some("<news.example.com>")
            ),
            Some(SenderKey::ListId("news.example.com".into()))
        );
    }

    #[test]
    fn a_rotating_local_part_keys_to_one_sender() {
        let first = key("bounce-1-a@mail.example.com", None);
        let second = key("bounce-2-b@mail.example.com", None);

        assert_eq!(
            first, second,
            "campaign rotation must not read as a new sender"
        );
        assert_eq!(first, Some(SenderKey::Domain("mail.example.com".into())));
    }

    #[test]
    fn reads_a_domain_out_of_a_display_name_address() {
        assert_eq!(
            key("Alice Example <alice@example.org>", None),
            Some(SenderKey::Domain("example.org".into()))
        );
    }

    #[test]
    fn round_trips_through_its_canonical_form() {
        for key in [
            SenderKey::ListId("news.example".into()),
            SenderKey::Domain("a.test".into()),
        ] {
            assert_eq!(key.to_string().parse(), Ok(key.clone()));
        }
    }

    #[test]
    fn folds_case() {
        assert_eq!(
            key("Alice@Example.ORG", None),
            key("alice@example.org", None)
        );
    }

    #[test]
    fn has_no_key_without_a_usable_sender() {
        assert_eq!(key("not-an-address", None), None);
        assert_eq!(key("", None), None);
    }

    #[test]
    fn a_malformed_header_does_not_become_a_sender() {
        for from in ["a@b.test <weird", "a@b.test extra", "a@b .test"] {
            assert_eq!(key(from, None), None, "{from} became a key");
        }
    }

    #[test]
    fn an_unbracketed_list_id_still_keys() {
        assert_eq!(
            key("a@b.test", Some("news.example.com")),
            Some(SenderKey::ListId("news.example.com".into()))
        );
    }
}
