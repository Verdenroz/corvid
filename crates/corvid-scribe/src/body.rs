//! The text of a message body, without the earlier messages it quotes.

use std::cell::RefCell;

use lol_html::html_content::ContentType;
use lol_html::{RewriteStrSettings, doc_text, element, rewrite_str};

/// `part_type` is the MIME type of the body part `value` came from. Quoted lines, those starting
/// with `>`, and HTML `<blockquote>` elements are dropped.
pub fn plain(part_type: &str, value: &str) -> String {
    let text = if part_type.eq_ignore_ascii_case("text/html") {
        html_text(value)
    } else {
        value.to_owned()
    };
    let kept: Vec<&str> = text
        .lines()
        .filter(|line| !line.trim_start().starts_with('>'))
        .collect();
    kept.join("\n").trim().to_owned()
}

// HTML that lol_html refuses reads as empty, so one hostile message cannot stall its thread.
fn html_text(html: &str) -> String {
    let marked = rewrite_str(
        html,
        RewriteStrSettings::new()
            .append_element_content_handler(element!("blockquote, script, style, head", |el| {
                el.remove();
                Ok(())
            }))
            .append_element_content_handler(element!("br", |el| {
                el.replace("\n", ContentType::Text);
                Ok(())
            }))
            .append_element_content_handler(element!(
                "p, div, tr, li, h1, h2, h3, h4, h5, h6, pre",
                |el| {
                    el.after("\n", ContentType::Text);
                    Ok(())
                }
            )),
    );
    let Ok(marked) = marked else {
        return String::new();
    };

    let text = RefCell::new(String::new());
    let read = rewrite_str(
        &marked,
        RewriteStrSettings::new().append_document_content_handler(doc_text!(|chunk| {
            text.borrow_mut().push_str(chunk.as_str());
            Ok(())
        })),
    );
    match read {
        Ok(_) => decode(&text.into_inner()),
        Err(_) => String::new(),
    }
}

fn decode(text: &str) -> String {
    text.replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_text_keeps_its_lines_and_drops_quoted_ones() {
        let body = "Sounds good.\n\nOn Monday Ann wrote:\n> Can we meet?\n>> earlier\n  > indented\nThanks";

        assert_eq!(
            plain("text/plain", body),
            "Sounds good.\n\nOn Monday Ann wrote:\nThanks"
        );
    }

    #[test]
    fn html_becomes_text_with_line_breaks_and_decoded_entities() {
        let html = "<html><head><title>t</title></head><body><p>Fish &amp; chips</p>\
                    <p>Line one<br>line two</p></body></html>";

        assert_eq!(plain("text/html", html), "Fish & chips\nLine one\nline two");
    }

    #[test]
    fn an_html_blockquote_script_and_style_are_dropped() {
        let html = "<style>p { color: red }</style><div>Reply</div>\
                    <blockquote>Original text</blockquote><script>alert(1)</script>";

        assert_eq!(plain("text/html", html), "Reply");
    }

    #[test]
    fn the_part_type_is_matched_without_regard_to_case() {
        assert_eq!(plain("TEXT/HTML", "<b>bold</b>"), "bold");
    }

    #[test]
    fn a_body_of_only_quotes_is_empty() {
        assert_eq!(plain("text/plain", "> a\n> b\n"), "");
    }
}
