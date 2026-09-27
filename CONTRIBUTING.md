# Contributing

## Before opening a pull request

CI runs these, and a pull request needs all of them green:

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
shellcheck -S warning deploy/*.sh deploy/tests/*.sh scripts/*.sh
bash deploy/tests/bootstrap-server.test.sh
bash deploy/tests/apply-plan.test.sh
bash deploy/tests/install.test.sh
bash deploy/tests/set-password.test.sh
bash deploy/tests/device.test.sh
bash deploy/tests/totp.test.sh
bash deploy/tests/lib.test.sh
./deploy/check-plan.py
./deploy/check-env.sh
./deploy/check-sentinel-events.sh
helm lint charts/corvid ...   # and helm template | kubeconform; see the chart job in ci.yml
```

Changes to `deploy/` also need a real run. `check-plan.py` checks field shapes and references,
but it cannot tell whether Stalwart accepts a plan. Say in the pull request what host you applied it
to, or that you did not.

Read `docs/design.md` before changing architecture, and `CLAUDE.md` for how the workspace fits
together and the Stalwart behaviour that has already caused outages.

## Commits

```
<type>: <subject>
```

Types: `fix`, `feat`, `refactor`, `perf`, `test`, `docs`, `chore`, `ci`. Subject in the
imperative, 50 characters or fewer, no trailing period. A body is optional; when present it is
`- ` bullets explaining why, since the diff already shows what. One logical change per commit.

## Code

- No comment unless it explains a choice a maintainer would otherwise undo: a workaround, a
  constraint, a trade-off. One or two lines. Doc comments on public items are exempt from the
  length limit.
- Comments state what holds now. History belongs in the commit message.
- Test names state the behaviour under test; tests carry no doc comments.
- Settings go in `config.yaml`: a field in `crates/corvid-config` and a line in
  `deploy/config.yaml`, which a test parses. Only secrets come from environment variables, and
  each needs a line in `deploy/env/*.example`, which `check-env.sh` enforces. Anything missing or
  malformed exits 78 naming it.
- Match the error handling already in the file. On the delivery path (the milter and what it
  calls), a failure delivers the message unchanged; new code there must fail the same way.
