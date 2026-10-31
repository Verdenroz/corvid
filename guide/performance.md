# Performance

Two code paths read every message's HTML: the milter's rewriter on the server and the reader in
the app. Both have benchmarks that CI gates on pull requests, and the numbers below are checked
against the baseline named `base`.

## The rewriter

<!-- soothfast:bind corvid_rewrite::Rewriter -->
A `Rewriter` holds the signer and the proxy base. Its `rewrite` streams one HTML document through
`lol_html`, removes remote images declared zero or 1x1 as trackers, and replaces every other remote
image, `srcset`, `background`, `input[type=image]` and `video` poster, and every `url()` in a style
attribute or `<style>` block, with a signed URL on the image proxy. Links keep their targets but
lose tracking query parameters. Parsing is capped at 8 MiB, past which the rewrite fails and the
message is delivered unchanged.
<!-- /soothfast:bind -->

<!-- soothfast:bind corvid_rewrite::Report -->
The `Report` returned with the rewritten document counts stripped and proxied references and lists
every remote host the message tried to reach, with the hosts of stripped pixels also kept apart as
trackers.
<!-- /soothfast:bind -->

<!-- soothfast:claim corvid_rewrite::bench_rewrite_newsletter.alloc.allocs <= 2200 -->
Rewriting a twelve-section newsletter costs at most 2200 allocations.
<!-- /soothfast:claim -->

<!-- soothfast:claim corvid_rewrite::bench_rewrite_tracking_pixels.walltime.median_ns < 200000 -->
Stripping sixty-four tracking pixels takes under 200us at the median.
<!-- /soothfast:claim -->

## The reader

<!-- soothfast:bind client/core/html/src/commonMain/kotlin/com/verdenroz/corvid/core/html/HtmlToBlocks.kt#Flow.image -->
The reader drops an image whose declared width or height is 1 or less, which removes the pixels
the milter did not already strip. An image it keeps carries a URL only if that URL starts with the
user's own proxy path or is a `cid:` part of the message. Any other source becomes an image with no
URL, so the app never fetches it.
<!-- /soothfast:bind -->

<!-- soothfast:bind client/core/html/src/commonMain/kotlin/com/verdenroz/corvid/core/html/LinkMismatch.kt#mismatchedHost -->
A link is marked as mismatched when its visible text reads as an address on one host and its href
points at another. Text that does not look like an address never counts, and a leading `www.` is
ignored on both sides.
<!-- /soothfast:bind -->

The reader's benchmarks live in `client/benchmarks` and run with
`./gradlew :benchmarks:mainBenchmark --max-workers=2`. Their JSON report is imported into the same
baseline as the Rust items with
`cargo soothfast import --from jmh <report.json> --save-baseline base`.

<!-- soothfast:claim jmh::com.verdenroz.corvid.benchmarks.HtmlBenchmarks.blocksNewsletter[mode=avgt].walltime.median_ns < 500000 -->
Turning a twelve-section newsletter into blocks takes under 500us on average.
<!-- /soothfast:claim -->
