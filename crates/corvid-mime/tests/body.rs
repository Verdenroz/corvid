use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use corvid_mime::rewrite_body;
use corvid_rewrite::Rewriter;
use corvid_sign::Signer;

const SECRET: &[u8] = b"mime test secret";
const PIXEL: &str = r#"<html><body><img src="https://t.example/px.gif" width="1" height="1"><img src="https://cdn.example/hero.png"></body></html>"#;

fn rewriter() -> Rewriter {
    Rewriter::new(Signer::new(SECRET.to_vec()), "https://img.corvid.test")
}

fn run(headers: &str, body: &str) -> (Vec<u8>, corvid_rewrite::Report) {
    let out = rewrite_body(headers.as_bytes(), body.as_bytes(), &rewriter()).expect("rewritten");
    (out.body, out.report)
}

fn unwrap_base64(body: &[u8]) -> String {
    let joined: Vec<u8> = body
        .iter()
        .copied()
        .filter(|b| !b"\r\n".contains(b))
        .collect();
    String::from_utf8(STANDARD.decode(joined).unwrap()).unwrap()
}

#[test]
fn rewrites_a_plain_single_part_message() {
    let (body, report) = run("Content-Type: text/html; charset=utf-8\r\n", PIXEL);
    let body = String::from_utf8(body).unwrap();

    assert!(!body.contains("t.example"));
    assert!(!body.contains("cdn.example"));
    assert!(body.contains("img.corvid.test/i/"));
    assert_eq!((report.stripped, report.proxied), (1, 1));
}

#[test]
fn a_base64_part_is_written_back_as_base64() {
    let headers = "Content-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n";
    let (body, report) = run(headers, &STANDARD.encode(PIXEL));

    let decoded = unwrap_base64(&body);
    assert!(
        decoded.starts_with("<html>"),
        "a base64 header over plaintext decodes to garbage"
    );
    assert!(!decoded.contains("t.example"));
    assert!(decoded.contains("img.corvid.test/i/"));
    assert_eq!((report.stripped, report.proxied), (1, 1));
}

#[test]
fn a_quoted_printable_part_is_written_back_as_quoted_printable() {
    let headers =
        "Content-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n";
    let encoded = quoted_printable::encode_to_str(PIXEL);
    let (body, _) = run(headers, &encoded);

    let decoded = quoted_printable::decode(&body, quoted_printable::ParseMode::Robust).unwrap();
    let decoded = String::from_utf8(decoded).unwrap();

    assert!(decoded.starts_with("<html>"));
    assert!(!decoded.contains("t.example"));
}

#[test]
fn a_legacy_charset_survives_the_round_trip() {
    let headers = "Content-Type: text/html; charset=iso-8859-1\r\n";
    let source = r#"<html><body>caf\xe9<img src="https://cdn.example/a.png"></body></html>"#
        .replace("\\xe9", "\u{e9}");
    let latin1: Vec<u8> = source.chars().map(|c| c as u8).collect();

    let out = rewrite_body(headers.as_bytes(), &latin1, &rewriter()).unwrap();

    assert!(
        out.body.contains(&0xe9),
        "e acute should stay a single latin-1 byte"
    );
    assert!(
        !out.body.windows(2).any(|w| w == [0xc3, 0xa9]),
        "must not become utf-8"
    );
    assert_eq!(out.report.proxied, 1);
}

#[test]
fn a_multipart_keeps_its_boundary_and_its_plain_sibling() {
    let headers = "Content-Type: multipart/alternative; boundary=BOUND1\r\n";
    let body = format!(
        "--BOUND1\r\nContent-Type: text/plain\r\n\r\nplain text with https://t.example/x\r\n--BOUND1\r\nContent-Type: text/html\r\n\r\n{PIXEL}\r\n--BOUND1--\r\n"
    );
    let (out, report) = run(headers, &body);
    let out = String::from_utf8(out).unwrap();

    assert!(out.contains("--BOUND1"), "boundary must survive");
    assert!(out.contains("--BOUND1--"), "closing boundary must survive");
    assert!(
        out.contains("plain text with https://t.example/x"),
        "plain sibling untouched"
    );
    assert!(out.contains("Content-Type: text/plain"));
    assert!(!out.contains(r#"src="https://cdn.example/hero.png""#));
    assert_eq!((report.stripped, report.proxied), (1, 1));
}

#[test]
fn a_base64_html_subpart_is_written_back_as_base64() {
    let headers = "Content-Type: multipart/alternative; boundary=B2\r\n";
    let body = format!(
        "--B2\r\nContent-Type: text/plain\r\n\r\nhello\r\n--B2\r\nContent-Type: text/html\r\nContent-Transfer-Encoding: base64\r\n\r\n{}\r\n--B2--\r\n",
        STANDARD.encode(PIXEL)
    );
    let (out, _) = run(headers, &body);
    let out = String::from_utf8(out).unwrap();

    let encoded = out
        .split("\r\n\r\n")
        .nth(2)
        .unwrap()
        .split("\r\n--B2--")
        .next()
        .unwrap();
    let decoded = unwrap_base64(encoded.as_bytes());

    assert!(decoded.contains("img.corvid.test/i/"));
    assert!(out.contains("hello"));
}

#[test]
fn a_message_with_no_html_comes_back_unchanged() {
    let body = "just text, https://t.example/x stays\r\n";
    let (out, report) = run("Content-Type: text/plain\r\n", body);

    assert_eq!(String::from_utf8(out).unwrap(), body);
    assert_eq!((report.stripped, report.proxied), (0, 0));
}

#[test]
fn rewrites_html_inside_a_multipart_mixed_with_an_attachment() {
    let headers = "Content-Type: multipart/mixed; boundary=MIX1\r\n";
    let body = format!(
        "--MIX1\r\nContent-Type: text/html\r\n\r\n{PIXEL}\r\n--MIX1\r\nContent-Type: application/pdf\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0xLjQK\r\n--MIX1--\r\n"
    );
    let (out, report) = run(headers, &body);
    let out = String::from_utf8(out).unwrap();

    assert!(
        out.contains("JVBERi0xLjQK"),
        "the attachment must survive untouched"
    );
    assert!(out.contains("Content-Type: application/pdf"));
    assert!(!out.contains("t.example"));
    assert_eq!((report.stripped, report.proxied), (1, 1));
}

#[test]
fn a_plain_text_only_message_is_left_alone() {
    let (out, report) = run("Content-Type: text/plain\r\n", "regards,\r\nsam\r\n");

    assert_eq!(String::from_utf8(out).unwrap(), "regards,\r\nsam\r\n");
    assert_eq!(report, corvid_rewrite::Report::default());
}
