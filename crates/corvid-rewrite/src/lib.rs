mod dimensions;
pub mod header;
mod params;
mod srcset;
mod style;
pub mod tally;

use std::cell::RefCell;
use std::collections::BTreeSet;

use corvid_sign::Signer;
use lol_html::html_content::ContentType;
use lol_html::{HtmlRewriter, MemorySettings, Settings, element, text};
use url::Url;

use dimensions::{declared, is_zero_area};
use params::strip_tracking;

// lol_html buffers without limit by default, and an unclosed tag in hostile HTML would stall
// delivery. Exceeding this fails the rewrite, so the message is delivered unchanged.
const MAX_PARSE_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Report {
    pub stripped: usize,
    pub proxied: usize,
    /// Every remote host the message tried to reach, stripped and proxied alike.
    pub hosts: BTreeSet<String>,
    /// The subset stripped as zero-area images: positively identified trackers.
    pub trackers: BTreeSet<String>,
}

#[derive(Debug)]
pub struct RewriteFailed;

pub struct Rewriter {
    signer: Signer,
    proxy_base: String,
}

impl Rewriter {
    pub fn new(signer: Signer, proxy_base: impl Into<String>) -> Self {
        Self {
            signer,
            proxy_base: proxy_base.into(),
        }
    }

    pub fn rewrite(&self, html: &str) -> Result<(String, Report), RewriteFailed> {
        let report = RefCell::new(Report::default());
        let css = RefCell::new(String::new());
        let mut output = Vec::new();

        let settings = Settings::new()
            .with_memory_settings(
                MemorySettings::new().with_max_allowed_memory_usage(MAX_PARSE_BYTES),
            )
            .append_element_content_handler(element!("img", |el| {
                let style = el.get_attribute("style");
                let width = declared(el.get_attribute("width"), style.as_deref(), "width");
                let height = declared(el.get_attribute("height"), style.as_deref(), "height");
                if is_zero_area(width, height) {
                    let mut report = report.borrow_mut();
                    if let Some(host) = el.get_attribute("src").as_deref().and_then(host_of) {
                        report.hosts.insert(host.clone());
                        report.trackers.insert(host);
                    }
                    el.remove();
                    report.stripped += 1;
                    return Ok(());
                }
                if let Some(src) = el.get_attribute("src")
                    && let Some(proxied) = self.proxy(&src, &mut report.borrow_mut())
                {
                    el.set_attribute("src", &proxied)?;
                }
                if let Some(srcset) = el.get_attribute("srcset") {
                    let rewritten = srcset::rewrite_urls(&srcset, |raw| {
                        self.proxy(raw, &mut report.borrow_mut())
                    });
                    el.set_attribute("srcset", &rewritten)?;
                }
                Ok(())
            }))
            .append_element_content_handler(element!("*[background]", |el| {
                if let Some(background) = el.get_attribute("background")
                    && let Some(proxied) = self.proxy(&background, &mut report.borrow_mut())
                {
                    el.set_attribute("background", &proxied)?;
                }
                Ok(())
            }))
            .append_element_content_handler(element!("*[style]", |el| {
                if let Some(style) = el.get_attribute("style") {
                    let rewritten = style::rewrite_urls(&style, |raw| {
                        self.proxy(raw, &mut report.borrow_mut())
                    });
                    el.set_attribute("style", &rewritten)?;
                }
                Ok(())
            }))
            .append_element_content_handler(element!("input[type=image][src]", |el| {
                if let Some(src) = el.get_attribute("src")
                    && let Some(proxied) = self.proxy(&src, &mut report.borrow_mut())
                {
                    el.set_attribute("src", &proxied)?;
                }
                Ok(())
            }))
            .append_element_content_handler(element!("video[poster]", |el| {
                if let Some(poster) = el.get_attribute("poster")
                    && let Some(proxied) = self.proxy(&poster, &mut report.borrow_mut())
                {
                    el.set_attribute("poster", &proxied)?;
                }
                Ok(())
            }))
            // A style block arrives in chunks, so it is buffered and rewritten whole.
            // Re-emitted as Html because escaping it would break the CSS it came from.
            .append_element_content_handler(text!("style", |chunk| {
                css.borrow_mut().push_str(chunk.as_str());
                if chunk.last_in_text_node() {
                    let rewritten = style::rewrite_urls(&css.borrow(), |raw| {
                        self.proxy(raw, &mut report.borrow_mut())
                    });
                    chunk.replace(&rewritten, ContentType::Html);
                    css.borrow_mut().clear();
                } else {
                    chunk.remove();
                }
                Ok(())
            }))
            .append_element_content_handler(element!("a[href]", |el| {
                if let Some(href) = el.get_attribute("href")
                    && let Ok(mut url) = Url::parse(&href)
                {
                    strip_tracking(&mut url);
                    el.set_attribute("href", url.as_str())?;
                }
                Ok(())
            }));

        let mut rewriter =
            HtmlRewriter::new(settings, |chunk: &[u8]| output.extend_from_slice(chunk));
        rewriter.write(html.as_bytes()).map_err(|_| RewriteFailed)?;
        rewriter.end().map_err(|_| RewriteFailed)?;

        let html = String::from_utf8(output).map_err(|_| RewriteFailed)?;
        Ok((html, report.into_inner()))
    }

    // Relative references, `cid:` inline attachments and `data:` URIs all fail this and
    // are left as they are. Proxying a `cid:` would break the inline attachment it names.
    fn proxy(&self, raw: &str, report: &mut Report) -> Option<String> {
        let mut url = Url::parse(&absolute(raw)).ok()?;
        if !matches!(url.scheme(), "http" | "https") {
            return None;
        }
        strip_tracking(&mut url);
        if let Some(host) = url.host_str() {
            report.hosts.insert(host.to_owned());
        }
        report.proxied += 1;
        Some(format!(
            "{}/i/{}",
            self.proxy_base,
            self.signer.mint(url.as_str())
        ))
    }
}

fn host_of(raw: &str) -> Option<String> {
    let url = Url::parse(&absolute(raw)).ok()?;
    if !matches!(url.scheme(), "http" | "https") {
        return None;
    }
    url.host_str().map(str::to_owned)
}

// A protocol-relative reference has no scheme but is not local: the client resolves it
// against the message base and fetches it, so it is a remote reference in disguise.
fn absolute(raw: &str) -> String {
    match raw.trim().strip_prefix("//") {
        Some(rest) => format!("https://{rest}"),
        None => raw.trim().to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn treats_a_protocol_relative_reference_as_https() {
        assert_eq!(absolute("//t.example/px.gif"), "https://t.example/px.gif");
    }

    #[test]
    fn leaves_other_references_as_they_are() {
        assert_eq!(absolute("cid:logo"), "cid:logo");
        assert_eq!(absolute("/local/a.png"), "/local/a.png");
        assert_eq!(
            absolute("https://a.example/x.png"),
            "https://a.example/x.png"
        );
    }
}
