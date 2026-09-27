use corvid_rewrite::Rewriter;
use corvid_sign::Signer;

const SECRET: &[u8] = b"rewrite test secret";
const BASE: &str = "https://img.corvid.test";

fn rewriter() -> Rewriter {
    Rewriter::new(Signer::new(SECRET.to_vec()), BASE)
}

fn rewrite(html: &str) -> (String, corvid_rewrite::Report) {
    rewriter().rewrite(html).unwrap()
}

#[test]
fn strips_a_one_by_one_tracking_pixel() {
    let (html, report) =
        rewrite(r#"<p>hi</p><img src="https://t.example/open.gif" width="1" height="1">"#);

    assert!(!html.contains("t.example"));
    assert!(html.contains("<p>hi</p>"));
    assert_eq!(report.stripped, 1);
    assert_eq!(report.proxied, 0);
}

#[test]
fn strips_a_pixel_hidden_by_zero_dimensions_in_a_style() {
    let (_, report) =
        rewrite(r#"<img src="https://t.example/o.gif" style="width:0px;height:0px">"#);

    assert_eq!(report.stripped, 1);
}

#[test]
fn proxies_a_display_none_image_rather_than_stripping_it() {
    let (html, report) =
        rewrite(r#"<img src="https://cdn.example/dark.png" style="display:none">"#);

    assert_eq!(
        report.stripped, 0,
        "a hidden image is often the dark-mode variant"
    );
    assert_eq!(report.proxied, 1);
    assert!(html.contains("img.corvid.test/i/"));
}

#[test]
fn proxies_an_ordinary_image() {
    let (html, report) = rewrite(r#"<img src="https://cdn.example/hero.png" width="600">"#);

    assert!(html.contains("img.corvid.test/i/"));
    assert!(!html.contains("cdn.example"));
    assert_eq!(report.proxied, 1);
    assert_eq!(report.hosts.iter().collect::<Vec<_>>(), ["cdn.example"]);
}

#[test]
fn the_original_url_is_recoverable_from_the_minted_one() {
    let original = "https://cdn.example/hero.png";
    let (html, _) = rewrite(&format!(r#"<img src="{original}">"#));

    let minted = html.split("/i/").nth(1).unwrap().trim_end_matches(r#"">"#);
    let (signature, payload) = minted.split_once('/').unwrap();

    assert_eq!(
        Signer::new(SECRET.to_vec())
            .open(signature, payload)
            .as_deref(),
        Some(original)
    );
}

#[test]
fn leaves_inline_attachments_and_data_uris_alone() {
    let html = r#"<img src="cid:logo@corvid"><img src="data:image/gif;base64,R0lGOD">"#;
    let (rewritten, report) = rewrite(html);

    assert_eq!(rewritten, html);
    assert_eq!(report.proxied, 0);
}

#[test]
fn leaves_a_relative_reference_alone() {
    let html = r#"<img src="/local/banner.png">"#;
    assert_eq!(rewrite(html).0, html);
}

#[test]
fn proxies_every_candidate_in_a_srcset() {
    let (html, report) =
        rewrite(r#"<img srcset="https://cdn.example/a.png 1x, https://cdn.example/b.png 2x">"#);

    assert!(!html.contains("cdn.example"));
    assert!(html.contains("1x"));
    assert!(html.contains("2x"));
    assert_eq!(report.proxied, 2);
}

#[test]
fn proxies_background_attributes_and_style_urls() {
    let (html, report) = rewrite(
        r#"<td background="https://a.example/bg.png"></td><div style="background:url('https://b.example/x.png')"></div>"#,
    );

    assert!(!html.contains("a.example"));
    assert!(!html.contains("b.example"));
    assert_eq!(report.proxied, 2);
}

#[test]
fn strips_tracking_parameters_from_links_without_proxying_them() {
    let (html, report) =
        rewrite(r#"<a href="https://shop.example/p?id=9&utm_source=news">buy</a>"#);

    assert!(html.contains("https://shop.example/p?id=9"));
    assert!(!html.contains("utm_source"));
    assert!(!html.contains("img.corvid.test"));
    assert_eq!(report.proxied, 0);
}

#[test]
fn strips_tracking_parameters_from_proxied_image_urls_too() {
    let (html, _) = rewrite(r#"<img src="https://cdn.example/h.png?utm_source=news&v=2">"#);

    let minted = html.split("/i/").nth(1).unwrap().trim_end_matches(r#"">"#);
    let (signature, payload) = minted.split_once('/').unwrap();
    let recovered = Signer::new(SECRET.to_vec())
        .open(signature, payload)
        .unwrap();

    assert_eq!(recovered, "https://cdn.example/h.png?v=2");
}

#[test]
fn reports_every_host_a_message_tried_to_reach() {
    let (_, report) = rewrite(
        r#"<img src="https://a.example/1.png"><img src="https://b.example/2.png"><img src="https://a.example/3.png">"#,
    );

    assert_eq!(
        report.hosts.iter().collect::<Vec<_>>(),
        ["a.example", "b.example"]
    );
    assert_eq!(report.proxied, 3);
}

#[test]
fn proxies_urls_inside_a_style_block() {
    let (html, report) =
        rewrite(r#"<style>.hero{background-image:url(https://c.example/bg.png)}</style><p>hi</p>"#);

    assert!(!html.contains("c.example"));
    assert!(html.contains("img.corvid.test/i/"));
    assert!(html.contains(".hero{background-image:url("));
    assert_eq!(report.proxied, 1);
}

#[test]
fn leaves_a_remote_stylesheet_link_alone() {
    let html = r#"<link rel="stylesheet" href="https://c.example/mail.css">"#;
    let (rewritten, report) = rewrite(html);

    assert_eq!(
        rewritten, html,
        "proxying a stylesheet would widen the proxy past images"
    );
    assert_eq!(report.proxied, 0);
}

#[test]
fn proxies_an_image_input_and_a_video_poster() {
    let (html, report) = rewrite(
        r#"<input type="image" src="https://a.example/go.png"><video poster="https://b.example/p.jpg"></video>"#,
    );

    assert!(!html.contains("a.example"));
    assert!(!html.contains("b.example"));
    assert_eq!(report.proxied, 2);
}

#[test]
fn proxies_a_body_background_attribute() {
    let (html, report) = rewrite(r#"<body background="https://a.example/bg.png"></body>"#);

    assert!(!html.contains("a.example"));
    assert_eq!(report.proxied, 1);
}

#[test]
fn keeps_a_comma_bearing_url_in_a_srcset_intact() {
    let (html, report) =
        rewrite(r#"<img srcset="https://cdn.example/a,b.png 1x, https://cdn.example/c.png 2x">"#);

    assert_eq!(report.proxied, 2);
    assert!(html.contains("1x"));

    let minted = html.split("/i/").nth(1).unwrap().split(' ').next().unwrap();
    let (signature, payload) = minted.split_once('/').unwrap();
    assert_eq!(
        Signer::new(SECRET.to_vec())
            .open(signature, payload)
            .as_deref(),
        Some("https://cdn.example/a,b.png")
    );
}

// VML is how Outlook-targeted mail carries background images. lol_html passes these
// through as unknown elements, so they stay a remote-reference vector.
#[test]
fn documents_that_vml_references_are_not_yet_rewritten() {
    let vml = r#"<!--[if mso]><v:image src="https://t.example/px.png"/><![endif]-->"#;
    let (html, report) = rewrite(vml);

    assert_eq!(html, vml);
    assert_eq!(report.proxied, 0);
}

#[test]
fn proxies_a_protocol_relative_reference() {
    let (html, report) = rewrite(r#"<img src="//tracker.example/px.gif">"#);

    assert!(
        !html.contains("tracker.example"),
        "a scheme-less remote url is still remote"
    );
    assert_eq!(report.proxied, 1);
    assert_eq!(report.hosts.iter().collect::<Vec<_>>(), ["tracker.example"]);
}

#[test]
fn names_the_host_of_a_stripped_tracker() {
    let (_, report) = rewrite(r#"<img src="https://beacon.example/px.gif" width="1" height="1">"#);

    assert_eq!(report.hosts.iter().collect::<Vec<_>>(), ["beacon.example"]);
    assert_eq!(
        report.trackers.iter().collect::<Vec<_>>(),
        ["beacon.example"]
    );
    assert_eq!((report.stripped, report.proxied), (1, 0));
}

#[test]
fn names_every_stripped_tracker_host() {
    let (_, report) = rewrite(
        r#"<img src="https://a.example/p.gif" width="1" height="1"><img src="https://b.example/p.gif" height="0">"#,
    );

    assert_eq!(
        report.hosts.iter().collect::<Vec<_>>(),
        ["a.example", "b.example"]
    );
    assert_eq!(
        report.trackers.iter().collect::<Vec<_>>(),
        ["a.example", "b.example"]
    );
}

#[test]
fn a_proxied_host_is_reported_but_not_called_a_tracker() {
    let (_, report) =
        rewrite(r#"<img src="https://a.example/1.png"><img src="https://b.example/2.png">"#);

    assert_eq!(
        report.hosts.iter().collect::<Vec<_>>(),
        ["a.example", "b.example"]
    );
    assert!(
        report.trackers.is_empty(),
        "a served image is not evidence of tracking"
    );
}

#[test]
fn reports_stripped_and_proxied_hosts_together() {
    let (_, report) = rewrite(
        r#"<img src="https://beacon.example/px.gif" width="1" height="1"><img src="https://cdn.example/hero.png">"#,
    );

    assert_eq!(
        report.hosts.iter().collect::<Vec<_>>(),
        ["beacon.example", "cdn.example"]
    );
    assert_eq!(
        report.trackers.iter().collect::<Vec<_>>(),
        ["beacon.example"]
    );
}

#[test]
fn refuses_a_part_that_would_exhaust_the_parser() {
    let hostile = "<div>".repeat(200_000);

    assert!(
        rewriter().rewrite(&hostile).is_err(),
        "an unbounded parse must fail, not run"
    );
}

#[test]
fn an_ordinary_large_message_still_rewrites() {
    let bulk = "<p>filler</p>".repeat(20_000);
    let html = format!(r#"{bulk}<img src="https://cdn.example/hero.png">"#);

    let (_, report) = rewrite(&html);
    assert_eq!(
        report.proxied, 1,
        "the ceiling must not reject normal marketing mail"
    );
}
