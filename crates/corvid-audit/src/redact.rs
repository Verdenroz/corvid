/// Everything known about who sent a message, so it can be taken back out of the text.
///
/// The body is what leaks a sender: a signature block, a quoted reply chain, a list footer. The
/// prior keys on domain and `List-Id`, and the display name lets a person recognise the sender.
pub struct Identity<'a> {
    pub address: &'a str,
    pub display_name: Option<&'a str>,
    pub list_id: Option<&'a str>,
}

const HIDDEN: &str = "[hidden]";

/// Removes the sender from text, so an answer about it is about the content.
///
/// Best effort: this scrubs what it is told about plus anything shaped like an address. A body
/// naming the sender in prose defeats it, and the audit's blinded numbers are only as good as
/// that.
pub fn redact(text: &str, identity: &Identity) -> String {
    let mut out = replace_addresses(text);
    for token in known_tokens(identity) {
        out = replace_ignoring_case(&out, &token);
    }
    out
}

/// The sender's own strings, longest first so a domain inside an address does not blank half of
/// it and leave the rest looking like text.
fn known_tokens(identity: &Identity) -> Vec<String> {
    let mut tokens = vec![identity.address.to_owned()];
    if let Some(domain) = identity.address.rsplit('@').next() {
        tokens.push(domain.to_owned());
    }
    tokens.extend(identity.display_name.map(str::to_owned));
    tokens.extend(identity.list_id.map(str::to_owned));
    tokens.retain(|token| !token.is_empty());
    tokens.sort_by_key(|token| std::cmp::Reverse(token.len()));
    tokens
}

/// Blanks anything shaped like an address, which catches senders the identity does not name,
/// such as an earlier participant in a quoted thread.
fn replace_addresses(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;

    while let Some(at) = rest.find('@') {
        let local_start = rest[..at]
            .rfind(|c: char| !is_address_char(c))
            .map_or(0, |index| {
                index + rest[index..].chars().next().unwrap().len_utf8()
            });
        let domain_end = at
            + 1
            + rest[at + 1..]
                .find(|c: char| !is_address_char(c))
                .unwrap_or(rest.len() - at - 1);
        let domain = &rest[at + 1..domain_end];

        out.push_str(&rest[..local_start]);
        if at > local_start && domain.contains('.') {
            out.push_str(HIDDEN);
        } else {
            out.push_str(&rest[local_start..domain_end]);
        }
        rest = &rest[domain_end..];
    }
    out.push_str(rest);
    out
}

fn is_address_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+' | '%')
}

fn replace_ignoring_case(text: &str, token: &str) -> String {
    let lowered = text.to_lowercase();
    let needle = token.to_lowercase();
    let mut out = String::with_capacity(text.len());
    let mut cursor = 0;

    while let Some(found) = lowered[cursor..].find(&needle) {
        let start = cursor + found;
        out.push_str(&text[cursor..start]);
        out.push_str(HIDDEN);
        cursor = start + needle.len();
    }
    out.push_str(&text[cursor..]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn acme() -> Identity<'static> {
        Identity {
            address: "billing@acme.test",
            display_name: Some("Acme Billing"),
            list_id: Some("announce.acme.test"),
        }
    }

    #[test]
    fn the_senders_own_address_goes() {
        let text = redact("Reply to billing@acme.test for help", &acme());

        assert!(!text.contains("billing@acme.test"));
        assert!(text.contains("Reply to"));
    }

    // The prior keys on the domain, so a bare domain in a footer gives the answer away.
    #[test]
    fn a_bare_domain_goes_too() {
        let text = redact("Visit acme.test/support", &acme());

        assert!(!text.contains("acme.test"));
    }

    #[test]
    fn the_display_name_and_list_id_go() {
        let text = redact("Acme Billing wrote:\nList-Id: announce.acme.test", &acme());

        assert!(!text.contains("Acme Billing"));
        assert!(!text.contains("announce.acme.test"));
    }

    #[test]
    fn an_address_from_a_quoted_thread_goes() {
        let text = redact(
            "> On Tuesday, someone.else@other.example wrote:\n> thanks",
            &acme(),
        );

        assert!(!text.contains("someone.else@other.example"));
        assert!(text.contains("thanks"));
    }

    #[test]
    fn case_does_not_carry_a_sender_through() {
        let text = redact("Billing@ACME.TEST and ACME.test", &acme());

        assert!(!text.to_lowercase().contains("acme.test"));
    }

    #[test]
    fn a_domain_inside_an_address_does_not_split_it() {
        let text = redact("billing@acme.test", &acme());

        assert_eq!(text, HIDDEN);
    }

    #[test]
    fn ordinary_text_is_left_alone() {
        let text = "Your order ships Tuesday. Total 40.00, no VAT.";

        assert_eq!(redact(text, &acme()), text);
    }

    // A known limit, not a gap in the patterns.
    #[test]
    fn a_sender_named_in_prose_survives_redaction() {
        let text = redact("Thanks for shopping with us here at Acme.", &acme());

        assert!(text.contains("Acme."), "blinding is not anonymity");
    }
}
