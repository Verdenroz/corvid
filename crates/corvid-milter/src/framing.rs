/// Splits the root header block off a body that arrived with it prepended.
///
/// Stalwart 0.16.21 sends the whole message in `SMFIC_BODY` while `SMFIR_REPLBODY` replaces
/// the body alone, and returning what arrived would duplicate the headers.
///
/// Returns `None` when the body does not start with the headers named in `header_names`, so a
/// server sending a spec-correct body keeps its first bytes.
pub fn split_echoed_headers<'a>(
    raw: &'a [u8],
    header_names: &[String],
) -> Option<(&'a [u8], &'a [u8])> {
    let separator = find(raw, b"\r\n\r\n")?;
    let candidate = &raw[..separator];
    if header_names.is_empty() || !starts_with_header(candidate, &header_names[0]) {
        return None;
    }
    if !header_names
        .iter()
        .all(|name| contains_header(candidate, name))
    {
        return None;
    }
    Some((candidate, &raw[separator + 4..]))
}

pub fn rebuild_headers(headers: &[(String, String)]) -> Vec<u8> {
    headers
        .iter()
        .flat_map(|(name, value)| format!("{name}: {value}\r\n").into_bytes())
        .collect()
}

fn starts_with_header(candidate: &[u8], name: &str) -> bool {
    let name = name.as_bytes();
    candidate.len() > name.len()
        && candidate[..name.len()].eq_ignore_ascii_case(name)
        && candidate[name.len()] == b':'
}

fn contains_header(candidate: &[u8], name: &str) -> bool {
    candidate
        .split(|byte| *byte == b'\n')
        .any(|line| starts_with_header(strip_cr(line), name))
}

fn strip_cr(line: &[u8]) -> &[u8] {
    line.strip_suffix(b"\r").unwrap_or(line)
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(list: &[&str]) -> Vec<String> {
        list.iter().map(|name| (*name).to_owned()).collect()
    }

    const HEADERS: &[u8] = b"From: a@b.test\r\nSubject: hi\r\nContent-Type: text/html\r\n";
    const BODY: &[u8] = b"<html><body>real body</body></html>\r\n";

    fn echoed() -> Vec<u8> {
        let mut echoed = HEADERS.to_vec();
        echoed.extend_from_slice(b"\r\n");
        echoed.extend_from_slice(BODY);
        echoed
    }

    #[test]
    fn splits_the_header_block_stalwart_prepends() {
        let echoed = echoed();
        let (headers, body) =
            split_echoed_headers(&echoed, &names(&["From", "Subject", "Content-Type"])).unwrap();

        assert_eq!(body, BODY);
        assert_eq!(headers, HEADERS.strip_suffix(b"\r\n").unwrap());
    }

    #[test]
    fn leaves_a_spec_correct_body_alone() {
        assert!(
            split_echoed_headers(BODY, &names(&["From", "Subject", "Content-Type"])).is_none(),
            "a fixed server must not have its body truncated"
        );
    }

    #[test]
    fn leaves_a_multipart_body_alone() {
        let body = b"--B1\r\nContent-Type: text/plain\r\n\r\nhi\r\n--B1--\r\n";

        assert!(
            split_echoed_headers(body, &names(&["From", "Subject", "Content-Type"])).is_none(),
            "a part header is not the root header block"
        );
    }

    #[test]
    fn does_not_split_when_a_header_is_missing_from_the_block() {
        let mut echoed = b"From: a@b.test\r\n".to_vec();
        echoed.extend_from_slice(b"\r\n");
        echoed.extend_from_slice(BODY);

        assert!(split_echoed_headers(&echoed, &names(&["From", "Subject"])).is_none());
    }

    #[test]
    fn matches_header_names_case_insensitively() {
        let mut echoed = b"from: a@b.test\r\nSUBJECT: hi\r\n".to_vec();
        echoed.extend_from_slice(b"\r\n");
        echoed.extend_from_slice(BODY);

        assert_eq!(
            split_echoed_headers(&echoed, &names(&["From", "Subject"]))
                .unwrap()
                .1,
            BODY
        );
    }

    #[test]
    fn leaves_a_body_with_no_blank_line_alone() {
        assert!(split_echoed_headers(b"no separator here", &names(&["From"])).is_none());
    }

    #[test]
    fn leaves_the_body_alone_when_no_headers_were_seen() {
        assert!(split_echoed_headers(&echoed(), &[]).is_none());
    }

    #[test]
    fn rebuilds_a_header_block_from_pairs() {
        let pairs = vec![
            ("From".to_owned(), "a@b.test".to_owned()),
            ("Subject".to_owned(), "hi".to_owned()),
        ];

        assert_eq!(
            rebuild_headers(&pairs),
            b"From: a@b.test\r\nSubject: hi\r\n"
        );
    }
}
