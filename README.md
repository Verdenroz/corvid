<p align="center">
  <img src="client/branding/corvid-icon.svg" width="112" alt="Corvid logo">
</p>

<h1 align="center">Corvid</h1>

<p align="center">
  <a href="https://github.com/Verdenroz/corvid/actions/workflows/ci.yml"><img src="https://github.com/Verdenroz/corvid/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/Verdenroz/corvid/actions/workflows/image.yml"><img src="https://github.com/Verdenroz/corvid/actions/workflows/image.yml/badge.svg" alt="Image"></a>
  <a href="https://verdenroz.github.io/corvid/"><img src="https://img.shields.io/badge/docs-guide-6A4FC0" alt="Docs"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/Rust-2024-orange?logo=rust" alt="Rust 2024">
  <img src="https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin Multiplatform">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+">
</p>

Self-hosted email for one mailbox, built on [Stalwart](https://stalw.art). Corvid strips trackers
at delivery and sends nothing through a relay.

> [!NOTE]
> Corvid is early. Read the [guide](https://verdenroz.github.io/corvid/) before deploying.

## Features

- Tracker stripping at delivery, with remote images loaded through a signed proxy
- Android and Linux desktop app, JMAP only, with passkey sign-in
- Push notifications and per-sender aliases
- Labels learned from the ones you set, with an optional model for new senders
- SPF, DKIM, DMARC, DANE and MTA-STS published for you through Cloudflare
- Nightly updates that roll back on a failed check
- Encrypted off-host mirror with restore

## Getting started

You need a VPS with outbound port 25 open and a domain on Cloudflare. From a checkout on your
own machine:

```sh
./install.sh --ssh-key ~/.ssh/id_ed25519 root@mail.example.com
```

The [setup guide](https://verdenroz.github.io/corvid/setup/) covers what to prepare first, what
the installer asks, and signing in from the app.

## Development

```sh
cargo test
cargo clippy --workspace --all-targets -- -D warnings
./deploy/check-plan.py
cd client && ./gradlew check
```

Architecture and conventions are in `CLAUDE.md`.

## License

MIT. Stalwart is AGPL-3.0 and runs unmodified from its upstream image.
