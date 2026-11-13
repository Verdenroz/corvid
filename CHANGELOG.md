# Changelog

## Unreleased (initial public surface)

<!-- soothfast:notes -->
<!-- ### Overview -->
<!-- What this release means for someone using it. One paragraph. -->

<!-- ### Upgrade notes -->
<!-- What a consumer has to do. "Nothing" is a useful answer. -->
<!-- /soothfast:notes -->

---

### 🔍 API surface

Initial release: 9 public items across 1 crates.

#### `corvid-rewrite` (9)

- `corvid_rewrite` (module)
- `corvid_rewrite::Report` (struct)
- `corvid_rewrite::RewriteFailed` (struct)
- `corvid_rewrite::Rewriter` (struct)
- `corvid_rewrite::header` (module): The `X-Corvid-Rewrite` header, written at delivery and read back by the tracker report.
- `corvid_rewrite::header::Marked` (enum): What the milter did to one message.
- `corvid_rewrite::header::Summary` (struct): What a rewritten message's header carries.
- `corvid_rewrite::tally` (module)
- `corvid_rewrite::tally::Tally` (struct): What the mailbox says about the rewrite, one message at a time.

### 📊 Gate movement

| item | instructions | median | p99 | allocs | polls |
|---|---:|---:|---:|---:|---:|
| `corvid_rewrite::bench_rewrite_local_references` | 937222 | 96.18µs | 98.56µs | 1601 | n/a |
| `corvid_rewrite::bench_rewrite_newsletter` | 2265484 | 177.95µs | 180.17µs | 2129 | n/a |
| `corvid_rewrite::bench_rewrite_scaling` | 23392233 | 1.77ms | 1.96ms | 21129 | n/a |
| `corvid_rewrite::bench_rewrite_tracking_pixels` | 814096 | 96.21µs | 97.13µs | 1217 | n/a |

