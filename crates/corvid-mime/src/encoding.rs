use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use mail_parser::Encoding;

/// Encodes rewritten text for a part, in the charset and transfer encoding it arrived in.
///
/// The root `Content-Type` and `Content-Transfer-Encoding` sit outside the body and cannot be
/// changed from here, so plaintext emitted under a base64 header decodes to binary garbage.
pub fn encode(text: &str, charset: Option<&str>, encoding: Encoding) -> Vec<u8> {
    let bytes = to_charset(text, charset);
    match encoding {
        Encoding::Base64 => wrap(&STANDARD.encode(&bytes)).into_bytes(),
        Encoding::QuotedPrintable => quoted_printable::encode_to_str(&bytes).into_bytes(),
        Encoding::None => bytes,
    }
}

fn to_charset(text: &str, charset: Option<&str>) -> Vec<u8> {
    match charset.and_then(|label| encoding_rs::Encoding::for_label(label.as_bytes())) {
        Some(encoding) if encoding != encoding_rs::UTF_8 => encoding.encode(text).0.into_owned(),
        _ => text.as_bytes().to_vec(),
    }
}

fn wrap(encoded: &str) -> String {
    encoded
        .as_bytes()
        .chunks(76)
        .map(|line| String::from_utf8_lossy(line).into_owned())
        .collect::<Vec<_>>()
        .join("\r\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    const HTML: &str = "<html><body>caf\u{e9}</body></html>";

    #[test]
    fn base64_output_decodes_back_to_the_original() {
        let encoded = encode(HTML, Some("utf-8"), Encoding::Base64);
        let joined: Vec<u8> = encoded
            .iter()
            .copied()
            .filter(|b| !b"\r\n".contains(b))
            .collect();

        assert_eq!(
            String::from_utf8(STANDARD.decode(joined).unwrap()).unwrap(),
            HTML
        );
    }

    #[test]
    fn base64_output_is_wrapped_for_transport() {
        let long = "x".repeat(500);
        let encoded = String::from_utf8(encode(&long, None, Encoding::Base64)).unwrap();

        assert!(encoded.lines().all(|line| line.len() <= 76));
    }

    #[test]
    fn quoted_printable_output_decodes_back_to_the_original() {
        let encoded = encode(HTML, Some("utf-8"), Encoding::QuotedPrintable);
        let decoded =
            quoted_printable::decode(&encoded, quoted_printable::ParseMode::Robust).unwrap();

        assert_eq!(String::from_utf8(decoded).unwrap(), HTML);
    }

    #[test]
    fn an_unencoded_part_stays_unencoded() {
        assert_eq!(encode(HTML, Some("utf-8"), Encoding::None), HTML.as_bytes());
    }

    #[test]
    fn a_legacy_charset_is_written_back_in_that_charset() {
        let encoded = encode(HTML, Some("iso-8859-1"), Encoding::None);

        assert_eq!(encoded.last(), Some(&b'>'));
        assert!(
            encoded.contains(&0xe9),
            "e acute should be one latin-1 byte"
        );
        assert!(
            !encoded.windows(2).any(|w| w == [0xc3, 0xa9]),
            "not utf-8 bytes"
        );
    }
}
