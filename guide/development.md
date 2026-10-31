# Development

Architecture, conventions and the traps this project has already hit are in
[`CLAUDE.md`](https://github.com/Verdenroz/corvid/blob/main/CLAUDE.md), written for contributors
whether or not they use Claude Code.

## Checks

```sh
cargo test
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
./deploy/check-plan.py    # the Stalwart plan's field shapes, no server needed
./deploy/check-env.sh     # every secret read appears in deploy/env/*.example or a unit
cd client && ./gradlew check --max-workers=2
```

## This guide

The guide is built by [soothfast](https://github.com/Verdenroz/soothfast) from `guide/` and
published to GitHub Pages from `main`. Paragraphs marked with a bind chip are tied to the code
they describe, and CI fails when that code changes until someone rereads the paragraph and
records it again:

```sh
cargo install cargo-soothfast --version 0.5.0 --locked
export SOOTHFAST_RUSTDOC_TOOLCHAIN=nightly-2026-08-07
cargo soothfast docs check -p corvid-config -p corvid-mirror -p corvid-rewrite -p corvid-sentinel --skip-claims
cargo soothfast docs accept -p corvid-config -p corvid-mirror -p corvid-rewrite -p corvid-sentinel   # after rereading
cargo soothfast docs serve
```

## The device approval API

Every route sits behind the same credential check as `/corvid/notices`, and needs
`sentinel.devices` in `config.yaml` and `CORVID_STALWART_TOKEN` in
`/opt/corvid/sentinel-devices.env`, which `deploy.sh` creates as a second admin API key. A server
without `devices` serves no `/corvid/devices` routes.

1. The new device, signed in as the mail user, posts `{"name": ...}` to
   `/corvid/devices/requests` and shows the `id` and six-digit `code` it gets back. A name matches
   `[a-z0-9-]{1,32}`, and `requests` is refused.
2. An approver signs in with an approver passkey for audience `corvid-admin`, which gives a
   five-minute token with no refresh, and posts `{"code": ...}` to
   `/corvid/devices/requests/<id>/approve` with that token in `X-Corvid-Master`. The sentinel
   checks its signature, issuer, audience and expiry, that it names `<mail_user>-admin`, and that
   it has not been used before.
3. The new device polls `/corvid/devices/requests/<id>` for `approved`, then reads
   `/corvid/devices/<name>`, which names the key's `accountId`, `nodeId` and `blobId`, and
   downloads that blob from the keys account. It never looks a key up by name.

`GET /corvid/devices/requests` lists open requests as `{id, name, expiresAt}`, newest first,
without their codes. The approver also sends its own mail bearer, so it must be signed in as the
mail user. The QR code reads `corvid-approve:<id>?code=<code>` and names no server.

An approve answers 401 for a token that proves nothing, 403 for a wrong code or a token that is
not the admin's, 410 for a request that is gone, spent or expired, and 502 when Stalwart could not
be reached or the key could not be written. After a 502 the request is spent and the device must
ask again.

The keys live as files in the `corvid-keys` account, in its own `keys.<domain>` domain, which
takes no mail and cannot sign in. The mailbox account holds a read share on the folder, so it can
read a key but not replace or remove one.

## Building the image

Every service on the mail server runs from one image, and nothing on the server builds it: a
release build of this workspace does not fit on a small mail host. CI publishes it to
`ghcr.io/verdenroz/corvid`. To build one by hand and load it onto a server of the same
architecture:

```sh
podman build -f deploy/Containerfile -t localhost/corvid:latest .
podman save localhost/corvid:latest | ssh <host> podman load
```

`deploy.sh` refuses with exit 69 while the image is missing. The image does not carry
`corvid-mirror`, which runs on another machine.

## Cutting a release

Bump `version` in `Cargo.toml`, merge, and push a tag:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The `image` workflow publishes `0.2.0` and moves `stable`, and the `release` workflow attaches
`corvid.apk`, `corvid-linux-x64.tar.gz` and `install-desktop.sh` to a GitHub release. A
prerelease tag such as `v0.2.0-rc1` publishes its own image tag, leaves `stable` alone and marks
the GitHub release as a prerelease. The GHCR package must be public, or servers cannot pull it.

## Android release signing

CI signs release APKs with a key from four repository secrets: `CORVID_KEYSTORE` (the keystore,
base64), `CORVID_KEYSTORE_PASSWORD`, `CORVID_KEY_ALIAS` and `CORVID_KEY_PASSWORD`. The build
leaves the APK unsigned when any is missing.

Keep the keystore and its password backed up offline. Android accepts an update only from the
same signing key, so losing it means every install must be removed and installed again.

## CI on a self-hosted runner

`ci.yml` and `image.yml` run on a self-hosted runner labelled `[self-hosted, linux, x64, corvid]`.
`scripts/ci-runner.sh up` builds the runner image (GitHub's runner on Ubuntu, plus the compiler
and shellcheck), registers one runner to this repository with a fresh token from `gh`, and starts
it. `status` shows the unit, the container and what GitHub reports; `down` removes the container,
and `down --purge` also deregisters the runner and deletes its state. Jobs queue behind the one
runner.

It needs docker, a `gh` login with admin on the repository, and the Android SDK at
`~/Android/Sdk` (or `ANDROID_HOME`). The container gets the docker socket, so image builds use the
host daemon, and a job can mount any path under `~/.local/share/corvid-ci/work`, which is mounted
at the same path on both sides. The SDK is mounted read-only. The registration token is used once;
the runner's credentials live in the `corvid-ci-home` volume.

The container shares the host's network, because `caddy.test.sh` and `auth.test.sh` start
containers with `--network host` and reach them on `127.0.0.1`. They bind 8080, 11333 and 18443
plus Stalwart's listeners, so the deploy job fails with a bind error if the machine already holds
one of them. A main push publishes its image only when no other job failed, so after that error
the registry keeps the previous build: free the port and rerun the failed jobs.

A user unit, `corvid-ci-runner.service`, starts the container at boot. Docker's own restart policy
is not enough where the daemon is socket-activated, since it stays down after a reboot until
something connects.

!!! warning
    Jobs run as the host user with the docker socket, which is root on the machine. Run workflows
    only from branches you trust, and never enable this on a repository that takes pull requests
    from forks.
