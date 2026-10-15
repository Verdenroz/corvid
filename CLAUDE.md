# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Corvid is a self-hosted, single-mailbox email deployment built around Stalwart Mail Server, plus
Rust services that sit beside it: a tracker-stripping milter and image proxy, a labeler, and an
off-host mirror. `deploy/RUNBOOK.md` covers the steps a deploy cannot automate.

Site-specific values live only on the target host under `/opt/corvid/`: settings in
`config.yaml`, secrets in env files. Nothing in this repo names a real deployment, and nothing
should; `deploy/config.yaml` is a template with `example.com` values that `deploy.sh` refuses.

## Commands

```sh
cargo test                                   # every crate
cargo test -p corvid-rewrite                 # one crate
cargo test -p corvid-rewrite --test rewrite  # one integration test file
cargo test -p corvid-rewrite -- <name>       # tests whose name contains <name>
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings   # currently clean; keep it so

./deploy/check-plan.py                       # plan field shapes vs the pinned Stalwart schema; no server needed
./deploy/check-env.sh                        # every secret read appears in deploy/env/*.example or a unit
./deploy/apply-plan.sh --dry-run             # syntax only, needs live credentials; see traps
podman build -f deploy/Containerfile -t localhost/corvid:latest .   # the image the pod runs
podman save localhost/corvid:latest | ssh <host> podman load        # a small mail host cannot build it
./scripts/vet-ip.sh <ip>                     # run on a candidate VPS, off any VPN, before choosing it
helm lint charts/corvid --set images.corvid.repository=x --set secrets.values.CORVID_IMGPROXY_SECRET=x --set secrets.values.CORVID_SENTINEL_SECRET=x --set-string 'secrets.values.cloudflare\.token=x'

cd client && ./gradlew check --max-workers=2       # ktlint, unit tests, android lint
./gradlew :androidApp:assembleDebug --max-workers=2
./gradlew :desktopApp:compileKotlin --max-workers=2
```

On a host, in order: `deploy/bootstrap.sh` (host prep, quadlets, firewall, empty env files), fill
the env files, then `deploy/deploy.sh` (start, bootstrap Stalwart, apply the plan, restart,
`verify.sh`). `stalwart-cli` v1.0.12 is the only management interface.

## Architecture

Everything on the mail host runs as one Podman pod from quadlets in `deploy/`, sharing a network
namespace, so services reach each other on `127.0.0.1`:

| Unit | What it is | Port |
|---|---|---|
| `corvid-mail` | Stalwart, pinned `v0.16.21` | 25, 465, 587, 993; 8080/8443 loopback |
| `corvid-unbound` | validating resolver, so DANE works | 5335 |
| `corvid-milter` | `corvid-milter` binary, called by Stalwart at DATA | 11332 |
| `corvid-imgproxy` | serves the images the milter rewrote | 8081 |
| `corvid-labeler` | polls JMAP, writes labels as keywords | none |
| `corvid-scribe` | summarizes long threads with the laptop model and posts them to the sentinel; masked until `config.yaml` has `scribe:` | none |
| `corvid-sentinel` | the pod's authenticated side door: the alert feed from Stalwart webhooks and update status, update requests, approver-approved device keys, and new-mail previews pushed to approved devices | 11333 loopback |
| `corvid-caddy` | the web tier: JMAP, `/i/*` to imgproxy, `/corvid/update`, `/corvid/notices` and `/corvid/devices/*` to the sentinel behind `forward_auth`, `/corvid/push/vapid` and `/corvid/push/stream` to it with no session | `server.web_port`, 4443 by default |

`corvid-mirror` is the exception: a systemd timer on a separate machine that pulls over JMAP.
`corvid-update.{service,timer,path}` are host units outside the pod: `update.sh` follows the
image tag, on the timer or when the sentinel writes `update/request`. The first passkey that approves
devices makes the sentinel write `deploy`, which runs the deploy files on the host with no pull, so
Stalwart switches to passkeys without waiting for the nightly update.

`charts/corvid` runs the same pod on Kubernetes, with `values.config` rendered verbatim as
`config.yaml`. It carries its own `unbound.conf` (loopback only, since pod addresses are
reachable cluster-wide) and a NetworkPolicy admitting only the public ports. Tests keep its
reserved-port list and Caddyfile in step with `corvid-config` and `deploy/`. It has run on kind,
including a first run and tracker-stripped delivery, but never on a production cluster.

**The main design split is between the delivery path and everything off it.** The milter is on
the delivery path with `tempFailOnError` true, so while it is down, mail is deferred at the
sender and never accepted unstripped. Everything the milter does must fail toward "deliver
unchanged and marked", never toward a crash: a requeued message that kills the daemon halts all
delivery. The labeler and mirror are off the path. When they fail, labels arrive late, but mail
still arrives.

Crates, grouped by the service that uses them:

- Delivery: `corvid-milter` (protocol, framing, sessions) → `corvid-mime` (walks the MIME tree,
  rewrites `text/html` parts in place, preserves each part's encoding) → `corvid-rewrite`
  (streaming `lol_html` rewrite with a fixed memory ceiling; mints proxy URLs).
  `corvid-sign` HMACs those URLs and `corvid-imgproxy` verifies them. The two share
  `CORVID_IMGPROXY_SECRET`, and changing it breaks every image already delivered.
- Labeling: `corvid-labeler` (poll loop, JMAP, and a cold-start pass that reads per-option
  probabilities from an OpenAI-compatible endpoint's logprobs, averaged over every rotation of
  the options) → `corvid-ingest` (turns keyword changes into training signal) →
  `corvid-label` (per-sender prior, census, verdicts).
- Summaries: `corvid-scribe` (JMAP threads in, one model call per thread, summaries posted to the
  sentinel) → `corvid-model` (the chat-completions client it shares with the labeler).
- Archive: `corvid-mirror` (sync encrypted to an age recipient, audit for missing messages, verify
  stored bodies with two levels of proof, `encrypt-archive` migration, restore to a fresh account).
- Shared: `corvid-config` (the `config.yaml` schema for every service), `corvid-jmap` (session
  and transport only; each caller owns its payload shapes), `corvid-store` (state-file envelope:
  checksum, schema line, atomic write; each caller pins its own payload).
- `corvid-audit` (sampling, reweighting, redaction for a blind audit) is a library with no
  binary yet.

`client/` is the Kotlin Multiplatform app (decision 5), Android and desktop from one shared core,
JMAP only. `core/jmap`, `core/auth`, `core/data`, `core/keyring` and `core/model` hold the
platform-independent logic; `feature/*` holds one screen and its ViewModel each; `app-common`
wires them through the lock gate (`AppLockState`).
`androidApp` and `desktopApp` are thin per-platform shells. Its gates are the three Gradle
commands above; it is not part of the Cargo workspace.

**The app never makes a device key.** A device with none asks `/corvid/devices/requests`, a device
with an approver key approves with a fresh approve sign-in (audience `corvid-admin`) whose token goes to that one request
and is never adopted, and every device reads its key only by the record `/corvid/devices/<name>`
returns. The approve route's own 401 (a bad admin token) carries no `WWW-Authenticate`, while the
forward-auth gate's 401 does, so only the second signs the session out (`JmapClient.webSend`).
qrose 1.2 and later need Kotlin 2.4 and Compose 1.12 by their published metadata, so the QR is
drawn from zxing's module matrix instead.

**Keyword provenance keeps the labeler from training on its own output.** Nothing in JMAP
records who set a keyword, so everything the labeler writes is prefixed `corvid-`, and model
output goes under `corvid-llm-`. Every other keyword is the user's, and only user keywords
train the per-sender prior in `corvid-label`. Anything that blurs that line lets the prior
confirm itself without anyone noticing.

**A corrupt or foreign-schema state file is a refusal, not a fresh start.** The labeler exits 1
and systemd is told not to restart it (`RestartPreventExitStatus=1`). Missing configuration exits
78, and a missing image or masked pod exits 69. A mail server not listening yet exits 75, which
systemd retries; a refused login stays 1, since retrying it would trip Stalwart's auto-ban.

Stalwart is configured declaratively. `deploy/stalwart.plan.ndjson` is a list of
upsert/update/reconcile operations on Stalwart's JMAP config objects, with `${VAR}` filled by
`apply-plan.sh` from `config.yaml`'s `server` section. The scripts read that section through
`corvid-config shell` in the image (`deploy/lib.sh`), so they share the services' parser and
checks. A test fails if a plan uses a variable that `shell` does not supply. `reconcile` makes
the server's set of objects exactly the plan's, so a listener missing from the plan is removed.

Everything that names the web tier derives from `server.hostname` and `server.web_port`: the
pod's published port (`corvid.pod` is rendered), Caddy's main site address, Stalwart's hostname
and the milter's default proxy base, all in the generated `server.env`. `bootstrap.sh` and
`deploy.sh` both render them. `server.idp_hostname`, `sentinel.devices.issuer` and
`sentinel.devices.idp_url` are retired and still accepted, since an update must not leave a service
exiting 78 on a config that names them. The web port must not be one the pod already
uses (`POD_PORTS`), since every container shares one network namespace.

Clients must rebase the URLs a JMAP session advertises onto the address they dialled, since the
web tier is not on 443.

The app signs in from an email address alone. It looks up SRV `_corvid._tcp.<domain>` (published
by `install.sh`; Stalwart's own `_jmap._tcp` is hard-coded to 443 and not ours to edit), falls back to
the domain on 443, and reads `/.well-known/corvid` from the web tier, a static JSON `respond` in the
Caddyfile built from the same env vars as everything else. `core/discovery` holds the dnsjava lookup.

## Conventions

- Services read settings from one `config.yaml` through `corvid-config`, which owns the whole
  schema and refuses unknown keys. Only secrets come from env vars, via `corvid_config::secret`.
  Anything missing or malformed exits 78 naming it. A new setting is a field in `corvid-config`
  plus a line in `deploy/config.yaml`, whose parse is a test; a new secret goes in
  `deploy/env/*.example`, which `check-env.sh` enforces.
- Tests are integration tests under each crate's `tests/`, against fakes of the JMAP server and
  the model endpoint (`corvid-labeler/tests/common`). Test names are sentences stating the
  behaviour.
- Pin persisted shapes at compile time in the crate that stores them, not in `corvid-store`.
- Every change to a persisted format ships a migration from the previous version, since an
  unattended update must never leave a service refusing its state.
- Bumping the Stalwart image means replacing `deploy/stalwart-schema-v<version>.json` and the
  `PINNED` name in `check-plan.py` with a schema cached from a server running the new version.

## Design decisions

| # | Decision | Why |
|---|---|---|
| 1 | Stalwart Mail Server, Community/AGPL, pinned `v0.16.21` | SMTP+IMAP+JMAP+Sieve+CalDAV in one binary |
| 2 | Server can read mail; TLS + DKIM/DMARC + LUKS + encrypted backups | Zero-access E2EE would make server-side AI and search impossible |
| 3 | Own IP, no outbound relay, ever | A relay reads the body |
| 4 | AI split: embeddings on the mail host, LLM on a separate machine over WireGuard | That machine is often off, so labeling cannot depend on it |
| 5 | Own client, Kotlin Multiplatform + Compose, JMAP only | Android first |
| 6 | Bridge networking; the web tier on a configurable port, 4443 by default | Leaves 443 to whatever else the host runs; clients rebase advertised URLs |

## Traps this project has already paid for

- **ufw versus containers.** Broke three unrelated things in one evening, never once
  looking like a firewall problem. `ufw allow <port>` creates an INPUT rule; container
  traffic is DNAT'd through FORWARD and needs `ufw route` rules in **both** directions.
  **Verify with `nft` packet counters, never a single successful test.** A rule at zero
  packets while mail appears to work means the traffic is taking another path.
- **Directory ownership.** Data dirs must be owned by uid 2000 *before* first start, or
  Stalwart fails to open its database with a bare permission error.
- **The Cloudflare token** is read by the Stalwart process, so it must be mode 0400
  owned by 2000. Root-owned 600 fails at first DNS call.
- **`dataStore` RocksDb requires `path`** in the Bootstrap payload.
- **`serverHostname` rejects reserved TLDs**, so `.example` is refused.
- **Gmail does not validate ed25519 DKIM.** Google's DMARC reports showed RSA passing and ed25519
  failing on every message, while the message's own Authentication-Results said `dkim=neutral (no
  key)` for ed25519 even though its record was published, so the DNS is not the thing to chase.
  The plan now lists only `Dkim1RsaSha256` in the Domain's `dkimManagement.algorithms` and states
  `SenderAuth.dkimSignDomain` explicitly. A key generated before that stays `active` until
  `apply-plan.sh` sets it to `retiring`, which is meant to stop it signing; `verify.sh` fails while
  one is active.
- **`smtp.dkim-fail` with `result=[]` on a submission is the pre-signing verify, not a missing
  signature.** Stalwart verifies the message as received, before it signs, and an authenticated
  submission carries no DKIM header yet. Check the signature on the delivered copy.
- **A Caddy `respond` body with JSON braces needs backticks, and `{$VAR}` still expands inside
  them.** `/.well-known/corvid` is one such line; `caddy.test.sh` compares the served document
  byte for byte against the env values it started Caddy with.
- **A Caddy `{$VAR:default}` falls back only when the variable is unset, not when it is empty.**
  `render_units` wrote `CORVID_ASSETLINKS_HOST=` empty, so `host {$CORVID_ASSETLINKS_HOST:...}`
  became a null matcher and Caddy refused to start: an update rolled back on a live host with every
  check through Caddy answering `000`. `caddy.test.sh` now provisions the Caddyfile with the
  `server.env` that `render_units` itself writes, both with app links on and off.
- **A milter with `enable: true` also runs on mail the user sends.** It rewrote nothing, but it
  stamped `X-Corvid-Rewrite` on outgoing mail, naming the software to every recipient. The plan
  gates it on `is_empty(authenticated_as)`, the same rule Stalwart's own spam filter uses.
- **Stalwart's default `publishRecords` has no `tlsa`**, so a default Domain never publishes the
  records DANE needs. The plan lists the set explicitly and `apply-plan.sh` narrows it: TLSA on the
  mail domain only when the hostname is inside it, MTA-STS only when the web tier holds 443.
- **MTA-STS is fetched from `https://mta-sts.<domain>` on 443 only**, never another port and never
  through a redirect. Off 443 Stalwart cannot serve it, so `install.sh` deploys a Cloudflare
  Worker on that hostname as a custom domain instead; Cloudflare terminates TLS on 443 for it
  regardless of `server.web_port`.
- **Turning a record type off in `publishRecords` leaves its records in DNS.** Stalwart stops
  managing them and deletes nothing, so `install.sh` removes the MTA-STS pair itself, and only
  while it still points `mta-sts` at this server. Measured: the applied set dropped `mtaSts` and
  Cloudflare still served both records.
- **Perfect authentication still lands in spam on day zero.** Reputation is time and
  engagement, not configuration.
- **A fresh v0.16.21 refuses `ReloadSettings`.** It ships three `SpamDnsblServer` objects whose
  `if` expressions use `bit_and`, which its own validator rejects, so every reload fails with
  `validationFailed` on a server nobody has touched. A restart loads applied settings anyway.
  `deploy.sh` skips the reload for that reason; `apply-plan.sh` alone says to restart.
- **Stalwart object lists are maps keyed by stringified integers, not arrays.** `servers`,
  `aliases` and `credentials` all take `{"0": {...}}`; an array is `invalidPatch`. The schema
  calls both `objectList`, so `check-plan.py` enforces the map shape by rule, learned from a
  real apply.
- **A restart mid-task stalls that task for an hour.** Stalwart locks a running task in its
  in-memory store, which defaults to the data store and so survives restarts, for one hour. A
  restart right after an apply leaves the DNS, DKIM and ACME tasks it queued locked by a dead
  process: they sit "Pending" with no error, and the server serves its self-signed placeholder
  until the lock expires. Debug tracing shows it as `task-manager.task-locked`. `deploy.sh` waits
  for the queue to drain before restarting.
- **Applied config is not live config.** `stalwart-cli apply` writes settings; the running server
  keeps serving the old ones until `stalwart-cli create Action --json
  '{"@type":"ReloadSettings"}'`. A restart also does it, but a reload is enough. Skip it and the
  change is a silent no-op that reads as a broken feature rather than an unapplied setting.
- **A DATA-stage Sieve rewrite is permanent.** Stalwart stores one blob and it is post-Sieve, so
  a body rewritten at ingest is the archived copy and the original is gone. Strip conservatively.
- **`replace :mime` on the root entity corrupts the message.** Single-part `text/html` has no
  wrapper, so the html part is the root. Replacing it drops `MIME-Version`, rewrites
  `Message-ID` to a server-generated value even with `addMessageIdHeader` false, discards every
  header added before the `replace`, and duplicates `Date`/`Message-ID` when the sender omitted
  them. Multipart is clean. `foreverypart` alone is clean; `replace` on the root is the trigger.
- **`replace :mime` takes no `:type`/`:subtype`.** `replace :mime :type "text" :subtype "html"`
  fails to compile. The replacement must be a whole MIME entity, easiest as a `text:` block.
- **`editheader` inside `foreverypart` edits the current part, not the message.** In a multipart
  message the header lands in the sub-part.
- **`Authentication.directoryId` is a cutover, not an addition.** Null means the internal
  directory; setting it to an OIDC directory means Stalwart stops consulting its own account
  store. With no passkey enrolled that is a login outage, so `deploy.sh` applies
  `auth.plan.ndjson` only once a passkey that approves devices exists. Measured: once active,
  the admin's own password gets a 401 on JMAP, while an admin API key (`x:ApiKey/set`, kept as
  `STALWART_TOKEN`) still reads, applies plans and acts on other accounts, since a Bearer key is
  tried before any directory. `stalwart-cli` errors if a user and a token are both set, even
  empty, so `prefer_token` in `lib.sh` unsets the password pair.
- **An admin account answers `x:ApiKey/set` only once Stalwart has left bootstrap mode.** Right
  after `Bootstrap` the new admin gets a 401 until the restart, so the key is created by
  `deploy.sh` after that restart, not by `bootstrap-server.sh`. The default quota is 5 keys per
  account; creating one destroys earlier `corvid-deploy` keys.
- **A token Stalwart verifies while the issuer is unreachable stays refused until Stalwart
  restarts.** Measured in `deploy/tests/auth.test.sh`: with the sentinel stopped, a restarted
  Stalwart answers a bearer token `401 Failed to decode token` while the admin API key and app
  passwords still authenticate, and the token is still refused after the sentinel returns. So
  `deploy.sh` waits for the issuer before the restart that loads the directory, and `corvid-mail`
  runs `wait-issuer.sh 60` as an `ExecStartPre` whose exit status the unit ignores, so a boot or
  update restart delays Stalwart by at most 60 seconds and never stops it. The unit orders itself
  after `corvid-caddy` and `corvid-sentinel` without requiring them. A sentinel down longer than
  that at boot still needs one `systemctl restart corvid-mail` once it is back. The rig tests the
  script, not the unit's `ExecStartPre`.
- **Stalwart's OIDC directory demands the `email` scope by default.** `requireScopes` defaults to
  `openid` and `email`, and the sentinel's tokens carry only `openid profile`, so Stalwart refuses
  them with 401 before it asks for userinfo. `auth.plan.ndjson` sets it to `openid` and `profile`;
  `profile` is what makes userinfo return `preferred_username`. Measured by `deploy/tests/auth.test.sh`
  against the pinned Stalwart.
- **The scope refusal is logged as `auth.failed`, which the plan's `info` tracer never prints.**
  At `info` a refused bearer is a bare 401 with nothing in `journalctl -u corvid-mail`; at `debug`
  it reads `Missing required scope 'email', present scopes: ["openid", "profile"]`. Raise the
  Tracer to `debug` before concluding Stalwart ignores a token.
- **Email/changes returns imports and restores, not only arrivals.** `message-ingest.ham` fires for SMTP
  delivery and only wakes the sentinel's push watcher, which reads Email/changes since its held state. A
  message appended over JMAP to the Inbox, unread, came back from that call at the next delivery and was
  pushed as new mail (measured by `push.test.sh`). The watcher pushes only messages whose `receivedAt` is
  within 15 minutes, which an import or restore keeps from the original and a catch-up after downtime lacks.
- **`/corvid/push/vapid` and `/corvid/push/stream` sit outside `forward_auth` on purpose.** The stream's
  credential is its own bearer token and the VAPID key is public, so a locked phone with no session can read
  both. Moving them under `@corvid` would end every background connection when the daily session lapses. The
  Caddyfile names these two paths, not the whole prefix, so a route added under `/corvid/push` is not
  exposed without a session by accident.
- **Registering other keys for an own connection mints a new token and closes the open stream.** The
  sentinel keeps only the token's hash, so it cannot hand the old one back; registering the same keys again
  answers 204 and changes nothing, so a client keeps the token it holds. An event id is the clock in
  milliseconds, so a resume after a sentinel restart still sorts after what the device last saw.
- **The sentinel posts to a fixed `fcm.googleapis.com`, so the rig reaches it by proxy.** `push.test.sh`
  runs the real binary with `HTTPS_PROXY` at a TLS-terminating stand-in and `SSL_CERT_FILE` at a throwaway
  CA; reqwest's rustls honours both on Linux. A change to the endpoint policy that allows another host needs
  the rig's `CONNECT` assertion widened with it.
- **UnifiedPush's embedded FCM distributor is listed only where `com.google.android.gms` exists.** Read from
  the connector 3.3.5 and distributor 3.1.0 sources: `getDistributors` drops the
  app's own package without Play Services or microG, and a default pick prefers an installed external
  distributor over the embedded one, so the app saves its own package as the distributor explicitly.
- **A PRIVATE notification with a public version still shows its content on a stock lock screen.** Android
  shows PRIVATE content whenever "sensitive notifications" is on, which is the default; measured on an
  emulator with a PIN set, the lock screen read the sender and subject. Previews are SECRET instead. With
  every child SECRET, SystemUI hides the whole group on the lock screen, summary included.
- **The connector's key manager cannot make keys for an instance it never registered.** Its key rows
  reference its registration rows, so `DefaultKeyManager.generate("stream")` throws a foreign key
  `SQLiteConstraintException` and crashes the app. The own connection keeps its own P-256 pair and auth secret,
  the private key sealed by an Android keystore key, and decrypts with Tink's `WebPushHybridDecrypt`.
- **A disabled Play Services still counts as installed to the connector.** `getDistributors` checks only that
  `com.google.android.gms` has a package, so a phone that disabled it was sent down the Google path and
  registration failed. The app also requires the package to be enabled, and a failed Google registration lets the
  row keep an own connection instead.
- **A config key a host's running image does not know exits every service 78.** `sentinel.push: {}` is
  safe in `deploy/config.yaml` and unsafe in a host's `/opt/corvid/config.yaml` until that host runs an
  image that parses it.
- **A new NetworkListener needs a restart**, not just `ReloadSettings`; the port stays closed.
- **`systemctl disable` does not stick on a quadlet unit; `mask` does.** The generator rebuilds
  the unit on every `daemon-reload`, so a removed enable symlink comes straight back and
  `is-enabled` still reports `generated`. A mask in `/etc/systemd/system` outranks the generator
  and survives a reload. Measured on the live box: install then disable leaves it `generated`,
  mask leaves it `masked` across a reload. This matters because quadlets carry
  `WantedBy=multi-user.target`, so merely installing them arms a pod to start at the next boot.
- **Stalwart enforces password strength on account creation**, so test fixtures need real
  passphrases. IMAP `LOGIN` is refused on a clear-text listener but `AUTHENTICATE PLAIN` works.
- **Stalwart stores certificates as config objects, not files.** `Certificate` carries the PEM
  and the private key as database fields, so nothing on disk can be pointed at by another
  service. Sharing a certificate means extracting it on a renewal schedule; run a second ACME
  client instead.
- **A default inbound throttle of 25 messages/hour** keyed on sender domain plus recipient will
  stop a test loop with `452 4.4.5`. Vary the sender domain rather than editing the config.
- **`client_access: host` works because traffic from the host itself never crosses FORWARD.** A
  VPN on the same machine connects to the server's own address, so Podman's DNAT sends it straight
  to the pod without passing ufw's route rules; only outside traffic needs them. Measured: 993's
  route rule sat at 0 packets while Thunderbird synced through the VPN. Test the closed side from
  a vantage off the VPN (check-host.net's TCP check), since this machine's own tests go through it.
- **One invalid command bans an address permanently.** An HTTPS probe of 465 sent an HTTP request
  into SMTP; Stalwart logged `security.scan-ban` and blocked the source with no expiry, on every
  listener including JMAP through Caddy. Any check that touches a mail port must speak its
  protocol and leave politely (`QUIT`, `LOGOUT`). A ban lives in memory too: deleting the
  `BlockedIp` object takes effect only after a restart. On a host whose own IP carries VPN
  traffic, banning that IP locks out every VPN user at once, so `apply-plan.sh` adds the
  server's public IP as an `AllowedIp` (from `stalwart.allowed-ip.ndjson`). Verified by sending
  the probe that caused the ban again: nothing was logged or blocked.
- **Behind a reverse proxy Stalwart sees the proxy's IP for every client**, and `Security`
  auto-bans on auth failures, so a few bad logins can ban the front end and take out all HTTP
  access. Set `Http.useXForwarded`, or `NetworkListener.overrideProxyTrustedNetworks` for
  PROXY protocol at the TCP layer.
- **Stalwart cannot reverse-proxy.** Listener protocols are smtp, lmtp, http, imap, pop3 and
  manageSieve; `Application` serves unpacked static files from a `resourceUrl` and does not
  forward upstream. A second HTTP service on the host needs its own front end.
- **Caddy `forward_auth` relays Stalwart's refusal as is.** Run against `caddy:2.10-alpine`, a
  `401` from `/jmap/session` reaches the client with its own status, body and `WWW-Authenticate`,
  and the upstream stub never sees the request. The auth call is a bodyless `GET` carrying the
  client's `Authorization` and an `X-Forwarded-For` of the client, so with `Http.useXForwarded`
  Stalwart bans the client and not the proxy. The proxied `POST` body arrives intact.
- **A credential-less `/jmap/session` is `200`, so `forward_auth` alone guards nothing.**
  v0.16.21 answers it with an empty session (no accounts), which `forward_auth` passes. The
  Caddyfile sends `/corvid/*` to the auth call only when `Authorization` is present; without
  it the request falls through to Stalwart's `404`. Measured on a live host: the matcher without the
  header check served notices and accepted update requests from anyone.
- **A single SMTP session delivers at most 10 messages** (`MtaStageData.maxMessages`), and the
  inbound throttle is 25/hour per sender domain plus recipient. Bulk-loading a test mailbox
  silently loses everything past the session cap.
- **Stalwart's default tracer logs nowhere.** It writes to `/var/log/stalwart/`, which does not
  exist in the container, so after bootstrap nothing reaches `journalctl -u corvid-mail`. The plan
  reconciles tracers to one stdout tracer at `info`. For more detail on a running server:
  `stalwart-cli update Tracer <id> --json '{"level":"debug"}'` and restart.
- **Stalwart never traces a WebHook delivery, success or failure, at any level.** Debugging one
  that silently stops reaching its target means replaying a signed body by hand (`curl` or a raw
  socket) against the target directly, since the server gives no signal either way.
- **A WebHook body carries no credential identity.** `auth.success` has no credential id, index
  or description; the only hint a secondary credential was used is the literal string
  `"details":"Authenticated with app password"`, present only for one and absent for the
  primary password. There is no way to tell which app password, ever. Match this string exactly
  and treat anything else as unrecognised rather than guessing, so a Stalwart reword surfaces
  loudly instead of silently reclassifying credentials.
- **A blocking reqwest client built on a tokio worker thread panics when dropped.** It spins up
  its own inner runtime, and shutting that runtime down from inside another one is refused.
  Build and drop it inside `spawn_blocking`, never directly in an `async fn`.
- **`indymilter` requests a `NO_*` skip for every callback not registered**, and a milter option
  the MTA does not offer fails the whole handshake. Intersect in the negotiate callback:
  `context.requested_opts &= offered`. It logs the reason at `trace!` only, so with
  `tempFailOnError` true a broken handshake surfaces as a bare `451` with null details.
- **`apply --dry-run` is a syntax check, not an apply rehearsal.** It validates object names,
  variant tags and reference syntax, and says nothing about required fields: a plan missing a
  required property reports a clean plan and then fails partway through a real apply. Verified
  with a `Directory` lacking `description`. **The schema cannot close this one:** it
  carries no `required` or `mandatory` anywhere, and absence from a `defaults` map is not a
  proxy, since most optional fields have no default either. That verdict exists only on the
  server at apply time. **`--dry-run` needs working credentials**, despite its help saying
  "without calling the server". It fetches `/api/schema`, and against a 401 it falls back to the
  cached schema and then fetches `/jmap/session` anyway, which is fatal. So it cannot be a
  pre-flight gate anywhere without credentials: CI, a fresh clone, or reviewing a plan change.
  That niche is `check-plan.py`, whose reference half needs neither server nor schema.
- **Whether a `#name` reference may name an object already on the server is unsettled.**
  `check-plan.py` warns on a reference the plan does not define rather than failing, because if
  server-side references are legal then a second apply against a configured host would be failed
  for being correct. Settle it by dry-running a plan with one bad reference against a real
  server; if references are plan-local, promote the warning to a failure. One weak piece of
  evidence points the other way: dry-run fetches `/jmap/session`, so it is a server-side
  operation and could consult existing state. That only proves it makes JMAP calls, since every
  JMAP call needs that session, but it is consistent with a reference resolving against an
  object already on the host. A third reason to keep it a warning: references resolve within a
  file and the file is not always the unit. `auth.plan.ndjson` is its own file, and
  a plan split further would warn once per cross-file reference. A failure there would make
  splitting impossible without silencing the check.
- **`stalwart-cli` caches the server's schema under `~/.cache/stalwart-cli/`**, and it can be
  read offline with no credentials. It carries every object's properties, types and defaults, so
  it answers field-shape questions that `--dry-run` cannot and that the documentation gets wrong.
  It caught `trainFrequency: "12h"` before it reached a server.
- **Durations in the schema are milliseconds as numbers, not strings.** The docs show
  `"trainFrequency": "6h"` and `"holdSamplesFor": "30d"` in a JSON request example; the schema
  types both as `number/duration`, with defaults `43200000` and `15552000000`. A string there
  fails at apply, and the dry-run does not catch it.
- **Stalwart 0.16 already learns spam from user feedback.** The `SpamClassifier` singleton
  trains continuously on messages marked spam or ham, so a hand-built "mark as spam" loop would
  feed the reservoir twice. Configure the singleton; do not write a second trainer.
- **Stalwart 0.16 removed the REST management API.** Config objects are JMAP objects; use
  `stalwart-cli`. `snapshot` is the fastest way to learn real serialized shapes. It caught four
  wrong field shapes that reading the documentation did not.
- **`AppPassword`, `ApiKey` and `FileNode` resolve to the caller's own account, unless the caller
  is admin.** Their JMAP methods (`x:AppPassword/set`, `FileNode/query`, ...) take no `accountId`
  from `stalwart-cli`'s generic verbs, which only ever act on the authenticated principal. An
  admin-authenticated request that adds an explicit `"accountId"` to the method call acts on that
  account instead; verified permission-checked, since the same call as a non-admin fails
  `forbidden: you are not an owner of account`. `deploy/device.sh` speaks raw JMAP for exactly
  this reason; `stalwart-cli update Account --json '{"credentials":{...}}'` cannot add or remove
  a `Secondary` credential at all ("Secondary credentials cannot be set directly").
- **The self-service password-change method needs the current secret; an admin patch does not.**
  `x:AccountPassword/set` (what a user changes their own password with) refuses `secret` without
  `currentSecret` when `otpAuth` is set. Patching `Account.credentials/<key>/secret` directly, as
  an admin, needs neither, and does not disturb that credential's own `otpAuth` or `allowedIps`.
- **A JSON-pointer patch into a key that does not exist creates it.** `credentials/0/secret` on an
  account with an empty `credentials` map produces a working `Password` credential with a
  server-set `@type`, proven by IMAP login. The account plan creates with no `credentials` at all,
  so `set-password.sh` cannot assume the Password entry already exists at any key.
- **Deleting a `FileNode` is immediate on Community, not archived.** `ArchivedItem` is
  `"enterprise": true`, and `DataRetention.archiveDeletedItemsFor` defaults to `null` ("deleted
  items will not be archived and will be permanently deleted immediately"). Querying `ArchivedItem`
  on Community fails outright (`forbidden: Enterprise edition`). Verified: a destroyed FileNode's
  blob 404s on the next download.
- **A read-only `FileNode` share does not stop the sharee creating children.** With
  `shareWith: {<id>: {mayRead: true}}`, the sharee reads the nodes and is refused update, destroy and
  a `shareWith` change, but `FileNode/set create` under any existing folder of that account is
  accepted, even a folder it cannot see. Only a top-level create is refused. Measured on the rig with
  a real OIDC token. So a shared folder proves what the owner wrote only if readers look up
  nodes by the id the owner recorded, never by name.
- **An account that must take no mail needs more than a permission.** Disabling `emailReceive`
  on an account, or `isEnabled` on its domain, still answers `250` to `RCPT TO`. What refuses it is
  an `MtaStageRcpt.script` naming a Sieve script that `reject`s on `rcpt_domain`, which is how
  `corvid-keys` is closed, in a `keys.<domain>` domain of its own. Disabling `authenticate` is what
  refuses its credentials (`403`).
- **An account name is not unique across domains.** `corvid-keys@<mail domain>` and the keys
  account share a name, so `x:Account/query` by `name` returns two accounts. The sentinel matches on `emailAddress`. The namesake gets a session of its own and
  `forbidden` on the keys account.
- **A default 5-app-password-per-account quota exists**, reported as `overQuota` in `notCreated`
  rather than failing the request outright. `stalwart.plan.ndjson` raises it to 20 via
  `Account.quotas/maxAppPasswords`; verified by creating a 6th after applying.
- **`Account.quotas` replaces wholesale, same as `credentials`.** Setting `quotas` to a map with
  one key wipes every other quota already on the account. `quotas/<key>` patches that one key,
  proven by applying the plan's Account upsert against a live account and finding a quota set
  independently untouched. `check-plan.py` now checks the base of a pointer-path property
  (`quotas/maxAppPasswords`) and marks the leaf unreachable rather than failing it outright.
- **A pointer-path key in a `create` is `invalidPatch`, not a patch.** An `upsert` that finds no
  match creates the object outright, and creation takes whole properties; `quotas/maxAppPasswords`
  there fails every first install. `stalwart.plan.ndjson` sets the quota in a second `upsert`
  entry, matched the same way, that runs after the account is known to exist. Verified on a
  brand-new server with the account absent: both entries apply, the account is created, and its
  quota is set in the same pass.
- **Stalwart rate-limits rapid successive JMAP calls with a bare `429`.** A restore that uploads
  and imports hundreds of messages back to back hit it on a freshly bootstrapped throwaway server;
  the run failed loudly mid-way rather than corrupting anything, and a rerun after a short wait
  picked up where the ledger left off. A bulk restore over many messages should expect this and
  back off, not assume a `429` means something is wrong with the request.
- **Stalwart account and domain ids are short and assigned in sequence** ("a", "b", "c", ...), so
  two independently bootstrapped servers routinely assign the same id to their first account.
  Measured directly: a drill's source rig and its replacement, bootstrapped fresh minutes apart,
  both gave their mailbox account id `c`. Anything that needs to tell two servers apart must pin
  on more than an id alone.
- **TOTP has no code-in-password path on IMAP, SMTP or JMAP Basic auth**, on any protocol
  version tried: appending the code to the password with a comma, colon, space or `$`, or
  sending it as a custom header, all fail exactly like a wrong password. Stalwart's own docs say
  why: 2FA is only usable through a client that speaks its OAuth flow. Once `otpAuth` is set,
  those three protocols refuse the account password outright and an app password is the only way
  in; JMAP Basic auth reports this as `402 "MFA code required"`, distinct from a plain `401`.
- **`POST /api/auth`'s `redirectUri` is required in v0.16.21 though `openapi.yml` marks it
  nullable.** A request without one gets a bare `401 Unauthorized: You have to authenticate
  first`, with no hint that the real cause was `auth.error: "A redirect URI is required."`,
  visible only in a WebHook capture. The rest of the documented shape holds: `{"type":"authCode",
  "accountName","accountSecret","clientId","redirectUri"}`, then the same body plus `"mfaToken":
  "<6-digit code>"` once the first call answers `{"type":"mfaRequired"}`.
- **Every passkey signs in; one that approves devices is chosen when its setup code is made.** There
  is no rule on the backup-eligible flag, so a synced passkey can approve. While no key approves,
  every code the sentinel mints enrolls an approver, and `register/finish` applies the same rule
  when the code is spent, since a schema-1 daily code migrates as a plain one. `register/finish`
  returns the sign-in tokens, so enrolling needs no second prompt. `auth.test.sh` rewrites its
  state file as schema 1 and checks the enrolled keys still sign in and approve.
- **A refresh token redeemed twice inside 60 seconds is a retry, not a theft.** A phone that lost
  the reply to a rotation presents the old token again. Inside the window the sentinel rotates
  again, handing out a new secret and keeping `previous` and `rotated_at`; after it, the reuse
  revokes the family and writes a notice. Without the window a dropped reply signs the phone out
  as if it had been stolen. Removing a passkey revokes every family it signed in.
- **`fido_assert_authdata_raw_*` needs libfido2 1.14, and a missing function fails only when it is
  called.** From libfido2's NEWS: the assertion `_raw` pair arrived in 1.14.0, the credential
  pair in 1.6.0. `Fido2MissingSymbolTest` measured on 1.17 that an interface naming an absent
  function loads through `Native.load` and throws `UnsatisfiedLinkError` only at the call, so
  an old library would enumerate fine and fail at the first sign-in, after the touch. The desktop
  app reads the CBOR-wrapped `fido_assert_authdata_ptr`, strips the header, and maps a missing
  symbol to `Unsupported`. `Fido2LiveTest` is the only check against a real key; it needs
  `CORVID_FIDO2_LIVE=1`, a PIN and three touches (register, sign in, approve).
- **Google Password Manager needs `handle_all_urls` in `assetlinks.json`, not only
  `get_login_creds`.** With `get_login_creds` alone, Google's Digital Asset Links check answered
  `linked: true` and the right package and certificate were served, yet creating a passkey on a
  phone failed with "RP ID cannot be validated". Listing `delegate_permission/common.handle_all_urls`
  as well fixed it on the next try. `verify.sh` compares the served file byte for byte with
  `corvid-config assetlinks`. Where the web tier serves it, a difference fails verify; where a
  Worker does, it is reported `stale` and does not roll back, since no release changes the Worker.
  Measured: a Worker fixed by hand ahead of the image failed an update and then its rollback.
- **A debug build of the Android app is `com.verdenroz.corvid.debug` and does not trust the
  rig's CA.** Credential Manager only accepts a passkey for a domain whose `assetlinks.json`
  lists the exact package and signing certificate, so a rig needs its own `android_apps`
  entry for the debug key, and a user CA in `cacerts-added` is ignored without a debug-only
  network security config naming `user` anchors. Measured on a `google_apis` emulator
  with no Google account, and an RP domain whose `assetlinks.json` Google could not fetch: the
  create sheet reads "Create passkey on another device?" and Continue fails with a DOM
  `SecurityError`, shown as the not-associated message. Either condition alone could have
  caused it.

## Working method

**When the real thing is available and not much more expensive than the proxy for it, do the
real thing.** Apply rather than dry-run. Count the protocol messages rather than trust the
pass. Compare the archive against the server rather than read the run's own summary. Grow the
mailbox past the page size rather than simulate a pager. This is a method rather than a
disposition: "distrust green results" is hard to act on and easy to claim, and most of what it
found here fell out of tests aimed at something else.

A proxy can only tell you about the part you modelled. A dry-run models structure and not
required fields. A fake models the cursor you thought of. A digest models the bytes you already
hold. A behavioural signature models the corpus you swept and not the code. Each is a fast
filter and none is evidence of the thing itself.

Look at the instruments first. Most of what review catches here is not in the thing being
measured but in what measures it: a comment describing a granularity the code did not have, a
test whose assertion was false for every real mailbox, a coverage field reporting health exactly
when answers were being dropped, a pin whose reach was a naming convention.

**The recurring failure is a summary line that outlives its qualifier:** `covered: 1.0` while
answers were discarded, a verdict on one stream and its reasons on another, `ok` from a checker
that had run half of itself. Each was found by making the qualifier's job explicit rather than
by reading the code, so when something reports health, ask what it would have to know to say
that.

That question applies to how a verdict is read, not just how it is written. `cmd | tail -2; echo
$?` reports the exit status of `tail`, so a failing check reads as green. Both the author and the
reviewer hit this in the same hour, on a checker whose bug was a wrong exit code. Check a status
without a pipe between it and the thing being checked.

An instrument that reads healthy is what you check *with* rather than what you check, so it
survives review longest. That makes it the thing to point a mutation at.

Prefer a guard whose gap is visible in a diff. A pin over named values misses anything unnamed,
which shows up as a new `const` with no array line. A golden master over swept behaviour misses
whatever the corpus never reaches, which shows up nowhere. The second sounds stronger and is
harder to audit, so the weaker-looking guard is usually the one to keep.
