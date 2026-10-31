use corvid_rewrite::Rewriter;
use corvid_sign::Signer;
use soothfast::{bench, fixture, keep};

soothfast::bench_main!();

struct Case {
    rewriter: Rewriter,
    html: String,
}

fn case(html: String) -> Case {
    Case {
        rewriter: Rewriter::new(
            Signer::new(b"bench secret".to_vec()),
            "https://img.example.com",
        ),
        html,
    }
}

fn section(i: usize) -> String {
    format!(
        r#"<table width="600"><tr><td background="https://cdn.example.com/bg/{i}.png">
<a href="https://news.example.com/story/{i}?utm_source=mail&utm_medium=email&id={i}"><img src="https://cdn.example.com/hero/{i}.jpg" srcset="https://cdn.example.com/hero/{i}.jpg 1x, https://cdn.example.com/hero/{i}@2x.jpg 2x" width="600" alt="Story {i}"></a>
<p style="background:url('https://cdn.example.com/tile/{i}.png')">Section {i} of the weekly digest, with enough prose to look like a real message body.</p>
<a href="https://news.example.com/unsubscribe?token={i}&utm_campaign=weekly">Unsubscribe</a>
</td></tr></table>
<img src="https://t.example.com/open/{i}.gif" width="1" height="1">
"#
    )
}

#[fixture]
fn newsletter() -> Case {
    case(format!(
        "<html><head><style>.hero {{ background: url(https://cdn.example.com/hero.png) }}</style></head><body>{}</body></html>",
        (0..12).map(section).collect::<String>()
    ))
}

#[fixture]
fn newsletter_n(n: usize) -> Case {
    case(format!(
        "<html><body>{}</body></html>",
        (0..n).map(section).collect::<String>()
    ))
}

#[fixture]
fn tracking_pixels() -> Case {
    case(
        (0..64)
            .map(|i| format!(r#"<img src="https://t{i}.example.com/o.gif" width="1" height="1">"#))
            .collect(),
    )
}

#[fixture]
fn local_only() -> Case {
    case(
        r#"<html><body><h1>Receipt</h1><p>Your order shipped.</p><img src="cid:logo"><a href="/orders/1">Order</a></body></html>"#
            .repeat(40),
    )
}

#[bench(group = "rewrite", setup = newsletter, covers = "corvid_rewrite::Rewriter::rewrite")]
fn bench_rewrite_newsletter(case: &Case) {
    keep(
        case.rewriter
            .rewrite(keep(&case.html))
            .expect("fixture is well-formed"),
    );
}

#[bench(group = "rewrite", setup = tracking_pixels, covers = "corvid_rewrite::Rewriter::rewrite")]
fn bench_rewrite_tracking_pixels(case: &Case) {
    keep(
        case.rewriter
            .rewrite(keep(&case.html))
            .expect("fixture is well-formed"),
    );
}

#[bench(group = "rewrite", setup = local_only, covers = "corvid_rewrite::Rewriter::rewrite")]
fn bench_rewrite_local_references(case: &Case) {
    keep(
        case.rewriter
            .rewrite(keep(&case.html))
            .expect("fixture is well-formed"),
    );
}

#[bench(
    group = "rewrite",
    setup_sized = newsletter_n,
    sizes(8, 32, 128),
    complexity = "n",
    covers = "corvid_rewrite::Rewriter::rewrite"
)]
fn bench_rewrite_scaling(case: &Case) {
    keep(
        case.rewriter
            .rewrite(keep(&case.html))
            .expect("fixture is well-formed"),
    );
}
