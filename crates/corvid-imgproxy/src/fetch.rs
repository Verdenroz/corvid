use std::net::IpAddr;
use std::sync::Arc;
use std::time::Duration;

use bytes::{Bytes, BytesMut};
use reqwest::header::{ACCEPT, CONTENT_TYPE, USER_AGENT};
use reqwest::redirect::Policy;
use url::{Host, Url};

use crate::cache::Image;
use crate::error::ProxyError;
use crate::resolver::{PublicOnlyResolver, is_public};

// An ordinary browser string: a corvid-specific UA would identify the reader to the sender.
const AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/128.0";

pub fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .dns_resolver(Arc::new(PublicOnlyResolver))
        .redirect(Policy::custom(|attempt| {
            if attempt.previous().len() >= 3 {
                attempt.stop()
            } else if is_fetchable(attempt.url()) {
                attempt.follow()
            } else {
                attempt.stop()
            }
        }))
        .timeout(Duration::from_secs(10))
        .referer(false)
        .build()
        .expect("client config is static")
}

// reqwest never consults the resolver for a literal address, so PublicOnlyResolver
// does not see these at all. Hostnames are left to it.
pub fn is_fetchable(url: &Url) -> bool {
    if !matches!(url.scheme(), "http" | "https") {
        return false;
    }
    match url.host() {
        Some(Host::Ipv4(ip)) => is_public(IpAddr::V4(ip)),
        Some(Host::Ipv6(ip)) => is_public(IpAddr::V6(ip)),
        Some(Host::Domain(_)) => true,
        None => false,
    }
}

// SVG carries script and its own external references, so it would be an active document
// served from this origin that can still reach trackers.
fn is_inert_image(content_type: &str) -> bool {
    content_type.starts_with("image/") && content_type != "image/svg+xml"
}

pub async fn fetch(
    client: &reqwest::Client,
    url: &str,
    max_bytes: usize,
) -> Result<Image, ProxyError> {
    let url = Url::parse(url).map_err(|_| ProxyError::BadTarget)?;
    if !is_fetchable(&url) {
        return Err(ProxyError::BadTarget);
    }

    let response = client
        .get(url)
        .header(USER_AGENT, AGENT)
        .header(ACCEPT, "image/*")
        .send()
        .await
        .map_err(|_| ProxyError::Upstream)?
        .error_for_status()
        .map_err(|_| ProxyError::Upstream)?;

    // A halted redirect arrives here as the 3xx itself rather than an error.
    if response.status().is_redirection() {
        return Err(ProxyError::BadTarget);
    }

    let content_type = response
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .map(|value| {
            value
                .split(';')
                .next()
                .unwrap_or_default()
                .trim()
                .to_owned()
        })
        .ok_or(ProxyError::NotAnImage)?;
    if !is_inert_image(&content_type) {
        return Err(ProxyError::NotAnImage);
    }
    if response
        .content_length()
        .is_some_and(|len| len > max_bytes as u64)
    {
        return Err(ProxyError::TooLarge);
    }

    Ok(Image {
        content_type,
        body: read_capped(response, max_bytes).await?,
    })
}

async fn read_capped(
    mut response: reqwest::Response,
    max_bytes: usize,
) -> Result<Bytes, ProxyError> {
    let mut body = BytesMut::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| ProxyError::Upstream)? {
        if body.len() + chunk.len() > max_bytes {
            return Err(ProxyError::TooLarge);
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body.freeze())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fetchable(url: &str) -> bool {
        is_fetchable(&Url::parse(url).unwrap())
    }

    #[test]
    fn refuses_literal_addresses_outside_public_space() {
        for url in [
            "http://127.0.0.1/pixel.gif",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.1/x.png",
            "https://[::1]/x.png",
            "http://[::ffff:127.0.0.1]/x.png",
        ] {
            assert!(!fetchable(url), "{url} accepted");
        }
    }

    #[test]
    fn refuses_schemes_other_than_http() {
        for url in [
            "file:///etc/passwd",
            "ftp://example.net/x.png",
            "data:image/gif;base64,AA",
        ] {
            assert!(!fetchable(url), "{url} accepted");
        }
    }

    #[test]
    fn refuses_svg_and_non_image_types() {
        for content_type in ["image/svg+xml", "text/html", "application/octet-stream"] {
            assert!(!is_inert_image(content_type), "{content_type} accepted");
        }
    }

    #[test]
    fn accepts_ordinary_raster_types() {
        for content_type in ["image/png", "image/gif", "image/jpeg", "image/webp"] {
            assert!(is_inert_image(content_type), "{content_type} rejected");
        }
    }

    #[test]
    fn accepts_hostnames_and_public_literals() {
        assert!(fetchable("https://tracker.example.net/open.gif?id=1"));
        assert!(fetchable("http://93.184.216.34/x.png"));
    }
}
