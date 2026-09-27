# Steps a deploy cannot do

Two credential operations are deliberately outside `apply-plan.sh`, for different reasons. Both
are one-time and both need a person. The rest of this file covers configuration, the spam loop,
passkeys, building the image and replacing an existing Stalwart container.

## The account's first password

`stalwart.plan.ndjson` creates the account and never mentions `credentials`. An `Account` upsert
leaves omitted fields alone, so repeated deploys are safe. A supplied `credentials` map, however,
**replaces** the stored one, and takes the account password and every app password with it. The
apply still reports success. `apply-plan.sh` refuses a plan that sets them.

Set it once, as admin:

    stalwart-cli update Account <id> --json '{"credentials":{"0":{"@type":"Password","secret":"..."}}}'

`credentials` is a map keyed by stringified integers. `describe` calls it `list<Credential>`;
an array is rejected.

## App passwords, one per device

`deploy/device.sh add|list|revoke` manages these; run it as `deploy/set-password.sh` runs, on
the host as root:

    ssh host /opt/corvid/deploy/device.sh add phone
    ssh host /opt/corvid/deploy/device.sh add --local corvid-sentinel
    ssh host /opt/corvid/deploy/device.sh list
    ssh host /opt/corvid/deploy/device.sh revoke phone

The secret prints once, only in that terminal: not to a file, a log, or anywhere else. `add`
refuses a name already in use; `revoke` also removes the device's storage key file (Phase 3, at
`corvid-devices/<name>.key` in the account's own JMAP file storage) if one exists, and reminds
you to revoke the device's VPN access too, if it has any.

`--local` scopes `allowedIps` to `127.0.0.1` and `::1` instead of `server.client_access`, for a
service that calls JMAP from inside the pod rather than from a mail app. It ignores
`client_access` entirely, since a service on loopback needs no exemption from it. This only
works because the labeler and the sentinel call `http://127.0.0.1:8080` directly; behind Caddy,
`Http.useXForwarded` makes Stalwart see the forwarded client address, not the pod's own loopback,
and a `--local` credential would be refused.

`stalwart-cli create AppPassword` only ever acts on the account authenticated as, and an
`Account` upsert cannot touch `credentials` at all ("Secondary credentials cannot be set
directly"). `device.sh` speaks JMAP directly with an explicit `accountId`, which the admin
credential may set for any account; the same call as a non-admin is refused ("you are not an
owner of account").

### The sentinel's own app password

`corvid-sentinel` signs in over JMAP like any other client, so it needs an app password too,
never the account password: once TOTP guards the account an app password is the only kind that
still works, and every notice the sentinel imports would otherwise count as a sign-in with the
account password.

It needs one restricted to `127.0.0.1`, since the sentinel calls JMAP from inside the pod on
loopback, not from the networks `client_access` allows, which a plain `device.sh add NAME` is
scoped to.
`device.sh add --local NAME` scopes one to loopback instead:

    ssh host /opt/corvid/deploy/device.sh add --local corvid-sentinel

Put the secret in `sentinel.env`'s `CORVID_JMAP_PASSWORD`. Expect one self-notice the first time
the sentinel starts, for its own first sign-in.

## Sign-in alerts

`corvid-sentinel` and Stalwart's own `WebHook` share `CORVID_SENTINEL_SECRET`
(`sentinel-webhook.env`, read by both `corvid-mail` and `corvid-sentinel`). Two limits worth
knowing before relying on it:

- Stalwart discards a queued webhook after `discardAfter` (24h). A sentinel outage longer than
  that loses whatever signed in while it was down, with no record anywhere else. Check it with
  `systemctl status corvid-sentinel`.
- A notice delivered successfully but followed by a failed state save answers Stalwart with a
  retryable status, so the retry imports a second copy of the same notice. Duplicates are
  possible; a missing one should not happen.

## Configuration

Settings live in one file, `/opt/corvid/config.yaml`, mounted read-only into each container at
`/etc/corvid/config.yaml` (another path can be given in `CORVID_CONFIG`). `bootstrap.sh` installs
the commented template from `deploy/config.yaml` once and never overwrites it; `deploy.sh`
refuses while it still holds the template's `example.com` values. Each service reads only its
own section and refuses unknown keys, so a misspelt setting stops it with exit 78 and the line
at fault rather than silently taking a default.

The file holds no secrets, so it can be shared or committed. Secrets come from the env files
`bootstrap.sh` creates empty and mode 600, with templates in `deploy/env/`:

    rewriter.env         CORVID_IMGPROXY_SECRET (openssl rand -hex 32)
    labeler.env          CORVID_JMAP_PASSWORD, from device.sh add --local corvid-labeler
    mirror.env           CORVID_JMAP_PASSWORD, from device.sh add corvid-mirror, on the mirror machine
    sentinel.env         CORVID_JMAP_PASSWORD, from device.sh add --local corvid-sentinel
    sentinel-webhook.env CORVID_SENTINEL_SECRET (openssl rand -hex 32), shared with Stalwart

Use a separate app password per service, created as in the section above, so revoking one does
not stop the others. The labeler and the sentinel run inside the pod and reach JMAP over
loopback, so their credentials are `--local`; the mirror is off-host and reaches JMAP through the
VPN, so it needs a plain `add` scoped like any other device. The image-proxy secret signs URLs in
the milter and verifies them in the proxy, so both read the same value. Changing it invalidates
the links in mail already delivered: those images stop loading, which is a visible cost and not a
broken mailbox.

The web tier answers on `server.web_port`, 4443 by default, leaving 443 to anything else the
host runs. Everything that names it is generated from `server.hostname` and `server.web_port`:
the pod's published port, Caddy's site address, Stalwart's hostname and Pocket-ID's `APP_URL`
(in `/opt/corvid/server.env`, rewritten on every run and never edited by hand), the OIDC
issuer, and the milter's proxy base unless `milter.proxy_base` states one. To change
either, rerun `install.sh`, which also opens the new port in the firewall. Changing the hostname or
port after mail has arrived is the expensive case: the milter mints proxy URLs into the body at
delivery and the rewrite is what gets archived, so images in mail already delivered point
somewhere that no longer answers.

### A hostname outside the mail domain

A host can keep a name in another zone, such as `n1.example.net` serving mail for `example.com`,
when that name is also its PTR and the name other services know it by. Set `server.host_zone:
example.net`. The plan then gives Stalwart a second Domain for that zone, used only to keep a
certificate: DNS management points at the same Cloudflare provider so the DNS-01 challenge can
run, with every record type in `publishRecords` off, so nothing in that zone is written. With no
accounts in it, mail to that zone is refused. The Cloudflare token must reach both zones. Any
records the zone already has, such as an old MX, stay until changed by hand.

### MTA-STS off 443

When `server.web_port` is not 443, `install.sh` deploys a Cloudflare Worker on
`mta-sts.<domain>` to serve the policy, since senders fetch it only on 443 and Stalwart's own
web tier is not there. The Cloudflare token needs two more permissions beyond the DNS ones this
installer already asks for: **Workers Scripts: Edit**, scoped to the account, and **Workers
Routes: Edit**, scoped to the zone that holds `server.domain`. Add them at
https://dash.cloudflare.com/profile/api-tokens before running `install.sh` off 443; a token
missing one fails naming it, not with a bare 403. Switching `server.web_port` back to 443 on a
later run removes the Worker and its `_mta-sts` TXT record, leaving Stalwart to serve MTA-STS
itself.

`server.mta_sts_mode` starts `testing` and applies to whichever side serves the policy. Watch
mail delivery before switching it to `enforce`; senders that see enforce and cannot complete a
TLS handshake to a listed MX drop the message rather than deliver it unencrypted.

### The labeler's model

`labeler.model` is optional. Without it the labeler still labels mail from the per-sender prior
it learns from your own labels; the model only adds guesses for senders it has not seen.

Any OpenAI-compatible chat endpoint works if it returns `logprobs` and `top_logprobs`, since the
labeler reads the probability of each option letter rather than generated text. A server that
only returns text fails the first call with an error saying so. Tested on llama-server; vLLM and
SGLang implement the same fields and are untested. A model with a thinking mode needs it turned
off in `request_extra`, or the one token it is allowed goes to the thinking block and every
message is counted as `off_options`. The template shows the switch for Qwen3-family models.

The model is asked about each message once per rotation of its options (labels plus "none of
these"), so position bias averages out. A message with five labels costs six calls. `confidence`
is the percent a choice must reach before its label is written.

Model size matters more than speed here. On four obvious messages with five labels, a 2B model
cleared the 80% threshold on one and a 35B-A3B model on all four, at about 4 seconds a message
warm. `cargo test -p corvid-labeler --test live -- --ignored --nocapture` reruns that check
against any endpoint; see the test for the variables it reads. Run it before trusting a new
server or model.

A pass runs on the labeler's own thread, so live mail is not polled while one is in flight. The
deadline bounds that, not the budget: a call that answers slowly never fails, so twenty slow
messages would hold the loop for as long as they took. It is checked between messages, so a pass
can overrun it by one message.

### Pocket-ID

`idp.env` holds Pocket-ID's `ENCRYPTION_KEY`, which has no default and encrypts its stored
private keys. `APP_URL` must equal the OIDC `issuerUrl` exactly, port included, because an issuer
is compared as a string and a mismatch fails only at the first login. So it is generated into
`server.env` from the same values as the issuer, and `deploy.sh` refuses an `idp.env` that also
sets it. `TRUST_PROXY` and `PORT` are set in the unit.

**Pocket-ID cannot be served under a path.** v1.6.2 refuses to start with `APP_URL must not
contain a path`, measured on the mail host. The `/idp` route in the Caddyfile, and the `APP_URL`
that `server.env` and the chart derive with `/idp`, therefore do not work yet. Pocket-ID needs a
hostname of its own (`idp.<domain>`, with a DNS record and its own Caddy site) or a port of its
own, with the OIDC `issuerUrl` moved to match. Decide before the first passkey login, since the
issuer is baked into the client registration.

## Proving the spam loop learns

Stalwart trains its classifier from messages marked spam or ham, so Corvid configures the
`SpamClassifier` singleton and writes no trainer of its own. The mailbox does not show whether
the loop is running: an empty junk folder looks the same whether nothing is being caught or
nothing is being learned.

    mark a message as junk in any client
    stalwart-cli query SpamTrainingSample     a new row appears
    watch for the SpamTrainSampleAdded event  the marking reached the classifier

Until `minSpamSamples` and `minHamSamples` are met the classifier stays unavailable and reports
`SpamModelNotReady`, which is expected on a new mailbox and not a fault. The rules filter runs
regardless. Training runs as the `spamClassifierTraining` task and reports `SpamTrainStarted`
and `SpamTrainCompleted`.

The number to watch is the live spam-sample count against `minSpamSamples`. Samples are held for
180 days, so the floor is 100 alive at once, about four marked spam a week sustained. A new
domain may never reach it, in which case the classifier never trains and the rules filter does
all the work with nothing saying so. A count stuck at eleven for a year answers the question,
and is the argument for raising the hold or lowering the floor.

## Passkeys are a separate apply, on purpose

`Authentication.directoryId` selects the "external directory used for authentication, or null to
use the internal directory". Pointing it at the OIDC directory stops Stalwart authenticating
against its own account store and starts it asking Pocket-ID, which on a fresh instance has
nobody enrolled. On a mailbox that already works, that is a login outage waiting for the first
reconnect.

So `stalwart.plan.ndjson` does not set it. Mail deploys first and keeps the internal
directory. When passkeys are wanted:

    PLAN=deploy/oidc.plan.ndjson ./deploy/apply-plan.sh
    systemctl unmask corvid-idp.service && systemctl start corvid-idp.service

The installer keeps Pocket-ID masked while Stalwart has no OIDC directory, and leaves it alone
once the plan above has created one, so a rerun does not switch passkeys off. Enrol a passkey in Pocket-ID and confirm a web login before relying on it, and keep the recovery
credential until then: it authenticates ahead of the directory, so it is the way back in.

## `server.mail_user` is a local part, not an address

`Account.name` is "typically an email address local part", and the address is formed as
`name@domain` from `domainId`. So `mail_user: alice`, not `alice@example.com`. The full form
produces an account whose address is `alice@example.com@example.com`, which the apply accepts,
so `corvid-config` refuses a `mail_user` containing `@`.

If the mailbox already exists on the host, this must match the existing account's name exactly,
or the plan creates a second account beside it rather than adopting the first.

## Building the Corvid image

The milter, image proxy and labeler all run `localhost/corvid:latest`, and nothing in the deploy
builds it. That is deliberate: a release build of this workspace does not fit comfortably on one
core with under half a gigabyte free, which is what the mail host has.
`deploy.sh` refuses with exit 69 if the image is absent.

Build it somewhere with room, then load it:

    podman build -f deploy/Containerfile -t localhost/corvid:latest .
    podman save localhost/corvid:latest | ssh <host> podman load

Build host and mail host must be the same architecture; both are `x86_64` today. The image does
not carry `corvid-mirror`, which runs on a separate machine and is installed there directly.

## Replacing an existing Stalwart container

`bootstrap.sh` accepts mail ports held by a Podman container, reports it, and masks
`corvid-pod.service` so the pod cannot collide with it at the next boot. It never stops anything:
retiring the old container interrupts mail, so it is a deliberate step.

    podman rm -f <old-container>
    systemctl unmask corvid-pod.service
    ./deploy/deploy.sh

`rm` rather than `stop`: a container created with `--restart=always` comes back at the next boot
while `podman-restart.service` is enabled, and takes the ports from the pod.

The pod mounts `/opt/corvid/stalwart` as Stalwart's config directory and `/opt/corvid/data` as its
data directory. Two ways in:

- **Adopt the old mailbox.** Copy the old `config.json` into `/opt/corvid/stalwart/`, move the
  old data to `/opt/corvid/data`, and chown both to uid 2000. Set `STALWART_USER` and
  `STALWART_PASSWORD` in `corvid.env` to the old admin credential; `server.mail_user` must match the
  existing account name exactly, or the plan creates a second account beside it.
- **Start fresh.** Move the old directories aside as a rollback, recreate `/opt/corvid/data` with
  `install -d -o 2000 -g 2000 -m 750`, and leave `/opt/corvid/stalwart` without a `config.json`
  so the server boots into bootstrap mode. `STALWART_USER` and `STALWART_PASSWORD` must be empty,
  or `bootstrap-server.sh` takes the server as already bootstrapped and the apply fails to
  authenticate.

### What a rerun changes

Rerunning `install.sh` or `deploy.sh` brings the server back to the plan and your answers. It
restarts the pod only when its units, generated files or image changed, and restarts Stalwart
only when the applied settings differ from the ones it last started with. A run that changed
nothing restarts nothing.

A reconciled type is reset to exactly what the plan names, so anything added to it by hand is
removed on the next run. That covers listeners and log outputs: a debug tracer added for an
investigation is gone after a rerun. Change the plan instead, or remove the addition yourself
when you are done with it.

### Listeners

`stalwart.plan.ndjson` reconciles `NetworkListener`, which means the set becomes exactly what
the plan names: 25, 465, 587, 993, 8080 and 8443. An older setup may also serve 143 (IMAP with
STARTTLS) and 4190 (ManageSieve); **neither exists after the apply**. 993 covers IMAP, but
ManageSieve has no equivalent here.

## The mirror: encryption, migration, and the restore drill

`mirror.age_recipient` in `config.yaml` is the public age key the nightly mirror encrypts to.
Generate it with `age-keygen` (or `cargo run --example keygen -p corvid-mirror` if neither `age`
nor `rage` is installed) and keep the identity off the mirror host entirely: the nightly path
(`corvid-mirror` with no arguments) never reads it. It is needed only for two commands run by
hand, both taking the identity file as their first argument:

    corvid-mirror verify --deep <identity-file>
    corvid-mirror restore <identity-file> <ledger-file> <jmap-url> <jmap-user>

### Migrating an existing plaintext archive

An archive synced before encryption existed has a `ledger.json` and plaintext blobs. Running the
mirror against it refuses outright, naming `encrypt-archive`:

    corvid-mirror encrypt-archive

This connects to the mirror's own configured server to refresh mailboxes and keywords for every
still-live message (a legacy archive predates mailbox tracking), encrypts every blob into the
same `blobs` directory under its own ciphertext name, then removes `ledger.json` and the old
plaintext blobs. It is resumable: a crash partway through is safe to rerun, since nothing is
committed until the new index is written, and a crash between that commit and the cleanup that
follows is finished rather than redone. A leftover `ledger.json` after `encrypt-archive` reports
success is the one case this cannot clean up itself; delete it by hand once `verify --deep`
confirms the archive is intact.

### The restore drill

Prove the mirror actually restores by destroying the server it mirrored and rebuilding the
mailbox from the archive alone, on a throwaway Stalwart the same version as production
(`v0.16.21`), never the real host. Repeat this after any change to `sync`, `encrypt-archive`, or
`restore`.

    container name    corvid-rig-backup
    ports             loopback, offset into any free port range not already in use by another
                      rig on the same machine (25->N+25, 465->N+465, 587->N+587, 993->N+993,
                      8080->N+8080, 8443->N+8443)
    engine            podman or docker, whichever is installed

1. Start the rig, bootstrap it (`stalwart-cli update Bootstrap`, matching `bootstrap-server.sh`'s
   payload shape), and create one account.
2. Seed it past the mirror's own page size (500): more than 500 messages, spread across at least
   one nested mailbox path (a name alone is not unique across parents) and with a keyword on
   some. `Email/import` after a blob upload seeds far faster than SMTP, which is throttled and
   session-capped, and never touches the sync path being tested.
3. Point `corvid-mirror` at the rig and run it. Confirm `verify --deep` with the identity, and
   grep the whole archive directory for a plaintext marker planted in a seeded body: it must not
   appear anywhere, including the metadata segments and the index.
4. Destroy the rig (`podman rm -f corvid-rig-backup` or the docker equivalent) and start a fresh
   one with a new account.
5. Run `corvid-mirror restore` against it. Confirm on the target server directly (not just the
   command's own exit code): total message count, mailbox hierarchy and per-mailbox counts,
   `receivedAt`, keywords and `Message-Id` on a sample.
6. Run the drill again with a crash: kill the `restore` process partway through (`timeout -s
   KILL`), confirm the ledger and the target both show partial progress, then rerun with
   `--resume` and confirm the audit is clean. Expect one duplicate message on the target from
   whichever import landed between the crash and its ledger save: restore is at-least-once, not
   exactly-once.

Measured running this: a `429 Too Many Requests` from Stalwart partway through a few hundred
back-to-back `Email/import` calls (see the trap in `CLAUDE.md`). `restore` does not retry a `429`
itself; it fails loudly and stops. If that happens mid-restore, wait a minute for the rate limit
to clear and rerun with `--resume`, which picks up from the ledger rather than redoing anything.
Also measured: the fresh replacement rig assigned its account the same id (`c`) the destroyed one
had, confirming restore's ledger cannot pin on the account id alone (see `CLAUDE.md`).

## Checking it

    passkey login on the web       succeeds through the identity provider
    app password over IMAP         still authenticates
    revoke one app password        the others and the account password keep working
