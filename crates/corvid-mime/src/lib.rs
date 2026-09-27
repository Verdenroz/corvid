mod encoding;

use corvid_rewrite::{Report, Rewriter};
use mail_parser::{MessageParser, MimeHeaders, PartType};

pub struct Rewritten {
    pub body: Vec<u8>,
    pub report: Report,
}

/// Rewrites the `text/html` parts of a message body, returning a replacement body.
///
/// `headers` is the root header block, which is read-only: the transport replaces the body
/// alone, so the structure, boundary, charset and transfer encoding it declares all still
/// apply and each part is written back exactly as it arrived.
///
/// Returns `None` when the message cannot be parsed or rewritten, which the caller should
/// treat as "deliver unchanged".
pub fn rewrite_body(headers: &[u8], body: &[u8], rewriter: &Rewriter) -> Option<Rewritten> {
    let mut message = Vec::with_capacity(headers.len() + body.len() + 4);
    message.extend_from_slice(headers);
    if !headers.ends_with(b"\r\n") {
        message.extend_from_slice(b"\r\n");
    }
    message.extend_from_slice(b"\r\n");
    let prefix = message.len();
    message.extend_from_slice(body);

    let parsed = MessageParser::default().parse(&message)?;

    let mut report = Report::default();
    let mut replacements = Vec::new();
    for part in &parsed.parts {
        let PartType::Html(html) = &part.body else {
            continue;
        };
        let start = (part.offset_body as usize).checked_sub(prefix)?;
        let end = (part.offset_end as usize).checked_sub(prefix)?;
        if end > body.len() || start > end {
            return None;
        }

        let (rewritten, part_report) = rewriter.rewrite(html).ok()?;
        report.stripped += part_report.stripped;
        report.proxied += part_report.proxied;
        report.hosts.extend(part_report.hosts);
        report.trackers.extend(part_report.trackers);

        let charset = part.content_type().and_then(|ct| ct.attribute("charset"));
        replacements.push((
            start,
            end,
            encoding::encode(&rewritten, charset, part.encoding),
        ));
    }

    replacements.sort_by_key(|(start, _, _)| *start);
    let mut out = Vec::with_capacity(body.len());
    let mut cursor = 0;
    for (start, end, encoded) in replacements {
        out.extend_from_slice(&body[cursor..start]);
        out.extend_from_slice(&encoded);
        cursor = end;
    }
    out.extend_from_slice(&body[cursor..]);

    Some(Rewritten { body: out, report })
}
