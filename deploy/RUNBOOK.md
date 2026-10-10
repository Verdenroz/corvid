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
    ssh host /opt/corvid/deploy/device.sh add --local corvid-labeler
    ssh host /opt/corvid/deploy/device.sh list
    ssh host /opt/corvid/deploy/device.sh revoke phone

The secret prints once, only in that terminal: not to a file, a log, or anywhere else. `add`
refuses a name already in use; `revoke` also removes the device's storage key file (the Corvid app's, at
`corvid-devices/<name>.key` in the account's own JMAP file storage) if one exists, and the key
the sentinel approved for it (below), and reminds you to revoke the device's VPN access too, if it
has any.

`--local` scopes `allowedIps` to `127.0.0.1` and `::1` instead of `server.client_access`, for a
service that calls JMAP from inside the pod rather than from a mail app. It ignores
`client_access` entirely, since a service on loopback needs no exemption from it. This only
works because the labeler calls `http://127.0.0.1:8080` directly; behind Caddy,
`Http.useXForwarded` makes Stalwart see the forwarded client address, not the pod's own loopback,
and a `--local` credential would be refused.

`stalwart-cli create AppPassword` only ever acts on the account authenticated as, and an
`Account` upsert cannot touch `credentials` at all ("Secondary credentials cannot be set
directly"). `device.sh` speaks JMAP directly with an explicit `accountId`, which the admin
credential may set for any account; the same call as a non-admin is refused ("you are not an
owner of account").

### A service's own app password

A service signs in over JMAP like any other client, so it needs an app password, never the
account password: once TOTP guards the account an app password is the only kind that still works.
Give each service its own, so revoking one does not stop the others.

The labeler calls JMAP from inside the pod on loopback, not from the networks `client_access`
allows, which a plain `device.sh add NAME` is scoped to. `device.sh add --local NAME` scopes one
to loopback instead:

    ssh host /opt/corvid/deploy/device.sh add --local corvid-labeler

Put the secret in `labeler.env`'s `CORVID_JMAP_PASSWORD`. The mirror is off-host and reaches JMAP
through the VPN, so it takes a plain `add`, scoped like any other device.

## Two-factor sign-in (TOTP)

`deploy/totp.sh enable|disable|status` manages TOTP on `server.mail_user`'s account password, run
the same way as the other credential scripts:

    ssh host /opt/corvid/deploy/totp.sh enable
    ssh host /opt/corvid/deploy/totp.sh status
    ssh host /opt/corvid/deploy/totp.sh disable

`enable` refuses if an app password does not already exist for the account (loopback-only ones,
from `device.sh add --local`, do not count, since they serve in-pod services rather than mail
apps): once TOTP is on, IMAP, SMTP and JMAP Basic auth refuse the account password outright, on
every attempt, with no way to supply a code, so every mail app must already be on an app
password before TOTP goes on, or it locks out the moment it does. `--force` skips this guard.

It generates a random secret locally, prints the `otpauth://` URI once (plus a terminal QR code
if `qrencode` is installed) and never writes anything to the server until a code entered back at
the terminal verifies against that secret locally: a wrong code changes nothing and exits
non-zero, so there is no way to half-enable it. `disable` clears `otpAuth` by the same admin
pointer patch `set-password.sh` uses for the password itself, and is the recovery path when a
phone is lost: it needs only shell access to the host, not the authenticator.

**What this does and does not guard.** IMAP, SMTP and JMAP Basic auth cannot complete a
TOTP-gated sign-in at all; they simply lose access to the account password and must move to an
app password (`device.sh add`), same as they would for passkeys. The only protocol that can
complete one is Stalwart's own OAuth login (`POST /api/auth`), used by its web UI and any OAuth
client; the Corvid client itself signs in through Pocket-ID once OIDC lands, so this TOTP does
not guard it either. What it buys today: a leaked account password alone opens nothing, on any
protocol, without the code.

## Sign-in alerts

`corvid-sentinel` and Stalwart's own `WebHook` share `CORVID_SENTINEL_SECRET`
(`sentinel-webhook.env`, read by both `corvid-mail` and `corvid-sentinel`). Two limits worth
knowing before relying on it:

- Stalwart discards a queued webhook after `discardAfter` (24h). A sentinel outage longer than
  that loses whatever signed in while it was down, with no record anywhere else. Check it with
  `systemctl status corvid-sentinel`.
- A notice is recorded and its state saved together. A failed save answers Stalwart with a
  retryable status and records nothing, so the retry cannot duplicate it.

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
    sentinel-webhook.env CORVID_SENTINEL_SECRET (openssl rand -hex 32), shared with Stalwart

The app passwords are created as in "A service's own app password". The image-proxy secret signs
URLs in the milter and verifies them in the proxy, so both read the same value. Changing it
invalidates the links in mail already delivered: those images stop loading, which is a visible cost
and not a broken mailbox.

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

### Signing in from an email address

The app takes an address such as `mailuser@example.com` and finds the server and identity provider
from its domain. `install.sh` publishes what that needs, in the mail domain's zone:

- an SRV record, `_corvid._tcp.<domain>` at `<server.hostname>` port `<server.web_port>`. Stalwart
  publishes its own `_jmap._tcp` on 443 and manages it; this is a different name, and nothing
  edits Stalwart's.
- `/.well-known/corvid` on the web tier, served by Caddy with no credential:
  `{"version":1,"server":"https://<hostname>:<web_port>","issuer":"https://<idp_hostname>:<web_port>"}`.

A changed `server.web_port` updates the record's port on the next install. Without the SRV record the app tries `https://<domain>/.well-known/corvid` on 443, which only
works when the web tier is there. A record that already points elsewhere stops `install.sh` before
anything changes; fix it and rerun. When Cloudflare refuses to create the record, `install.sh` warns
with it and carries on, since mail works without discovery. Add it by hand:

    _corvid._tcp.<domain>.  SRV  0 5 <web_port> <server.hostname>.

Check it from anywhere with `dig +short SRV _corvid._tcp.<domain>` and
`curl https://<server.hostname>:<web_port>/.well-known/corvid`. The app shows the two hostnames it
found before it signs in; "Advanced" takes both addresses by hand.

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

**Pocket-ID serves on its own hostname, not a path.** v1.6.2 refuses to start with `APP_URL must
not contain a path`, measured on the mail host, so it cannot sit behind `/idp` on Stalwart's own
web tier hostname. It gets `server.idp_hostname`, defaulting to `idp.<domain>`, on the same
`server.web_port`: its own Caddy site, its own DNS record (`install.sh` publishes it), and
`APP_URL` and the OIDC `issuerUrl` both `https://<idp_hostname>:<web_port>`, no path. Decide the
hostname before the first passkey login, since the issuer is baked into the client registration.

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

## Passkeys come with the deploy

`Authentication.directoryId` selects the "external directory used for authentication, or null to
use the internal directory". Pointing it at the OIDC directory stops Stalwart authenticating
against its own account store and starts it asking Pocket-ID. So `stalwart.plan.ndjson` does not
set it, and `deploy.sh` applies `oidc.plan.ndjson` only after provisioning has created the users.

`deploy.sh` brings Pocket-ID up and configures it with no web UI, through its REST API and the
`STATIC_API_KEY` in `idp.env` (`install.sh` generates it). `deploy/idp.sh provision` creates:

- `<mail_user>-admin`, a Pocket-ID admin, and `<mail_user>`, the mailbox owner. Both get an
  address at the mail domain; the admin's has no mailbox and nothing mails it.
- a `mail` group holding only the mail user, and the `corvid` client (public, PKCE) restricted to
  that group, so the admin and any other Pocket-ID user get no sign-in code for the client.
- an `admins` group holding only the admin user, and the `corvid-admin` client restricted to it,
  which demands a fresh passkey on every authorization. It approves new devices (below).

It checks what exists before creating anything, so a rerun changes nothing and prints no links. A
`corvid` client registered by hand is restricted in place. Pocket-ID's setup routes
(`/setup`, `/api/signup/setup`) answer 404 at Caddy, so nobody can claim the first admin account.
`idp.sh` reaches the API from inside the `corvid-idp` container's network namespace, so it needs
neither a certificate nor a published port.

The deploy prints one enrollment link per user it created, valid 24 hours. Open each, register a
passkey, and confirm a sign-in; the Corvid app signs in as
the mail user. The deploy scripts authenticate with an admin API key (`STALWART_TOKEN` in
`corvid.env`, created on the first deploy that finds a working password), because Stalwart
refuses the admin password once the OIDC directory is active. App passwords for IMAP and SMTP are
unaffected.

New devices sign in with the phone's passkey through the browser's own cross-device prompt;
Pocket-ID's own device login is closed at Caddy.

A lost or broken key, or an expired link, needs a new link, minted on the host:

    /opt/corvid/deploy/idp.sh link <mail_user>

If `idp.sh` itself is unusable, Pocket-ID's own CLI mints one inside the container (the binary
path and `--help` are checked against the pinned image; the command itself was not run):

    podman exec corvid-idp /app/pocket-id one-time-access-token <username>

and the link is `https://<idp_hostname>:<web_port>/lc/<token>`. A host that already has Pocket-ID
data from the setup page keeps its users: `provision` finds them by username and prints no link
for them, so use `idp.sh link` for each. A half-finished setup that left a stray user is cleared
by stopping `corvid-idp` and removing `/opt/corvid/idp/pocket-id.db*` before the deploy.

## Approving a new device

A Corvid device's storage key is made only by a request the admin approves with a passkey, so a
stolen daily session cannot enroll a device. The key is a file in the `corvid-keys` account, which
has its own disabled `keys.<domain>` domain, takes no mail and cannot sign in. The mailbox account
holds a read share on the folder, so it can read a key and not replace or remove one.

The flow, over the web tier and all behind the same credential check as `/corvid/notices`:

1. The new device, signed in as the mail user, posts `{"name": ...}` to
   `/corvid/devices/requests` and shows the `id` and six digit `code` it gets back. A name is
   `[a-z0-9-]{1,32}`, and `requests` is refused.
2. A master device signs in to the `corvid-admin` client with the admin's passkey and posts
   `{"code": ...}` to `/corvid/devices/requests/<id>/approve` with its access token in
   `X-Corvid-Master`. The sentinel checks the token's signature, issuer, audience, expiry and that
   it was issued in the last five minutes, then asks Pocket-ID's userinfo that it names
   `<mail_user>-admin`. A request lives ten minutes, is spent by the first correct code, and dies
   after five wrong ones.
3. The new device polls `/corvid/devices/requests/<id>` for `approved`, then reads
   `/corvid/devices/<name>`, which names the key's `accountId`, `nodeId` and `blobId`. It downloads
   that blob from the keys account and never looks a key up by name.

A master device finds a waiting request without the id: `GET /corvid/devices/requests` lists the
open ones as `{id, name, expiresAt}`, newest first, and never the code. The app's "Approve a
device" screen shows that list; the approver picks the device by name and types the code from its
screen. Typing the id or scanning the QR code remains as a fallback.

The master device may be the new device itself. The app's enrollment screen has an "Approve with
the admin passkey" action that runs step 2 on its own request, so the first device of an account
unlocks without another one. The server never checks that the approver is unlocked.

Pocket-ID holds one signed-in account per browser and refuses `corvid-admin` to a browser still
signed in as the mail user. Ending that session would revoke the tokens it issued, including the
app's own. So step 2 opens the admin sign-in in a private browser context with its own cookies: an
ephemeral Custom Tab on Android (Chrome 136 or later; other browsers open a normal tab, which
shares the daily session) and the default browser's private window on Linux desktop (a browser
with no private-window action in its `.desktop` file opens a normal window, with the same
limit).

An approve answers 401 for a token that proves nothing, 403 for a wrong code or a token that is not
the admin's, 410 for a request that is gone, spent or expired, and 502 when Pocket-ID or Stalwart
could not be reached or the key could not be written. After a 502 the request is spent and the
device must ask again.

Needs `sentinel.devices` in `config.yaml` and `CORVID_STALWART_TOKEN` in
`/opt/corvid/sentinel-devices.env`, which `deploy.sh` creates as a second admin API key. A host
whose `config.yaml` lacks `devices` serves no `/corvid/devices` routes. `device.sh revoke NAME`
also destroys the approved key file and drops its record, through a route the web tier never
serves.

Keys the Corvid app made for itself under `corvid-devices` in the mailbox account are not
migrated: the app no longer reads them, so each device asks once through this flow, and
`device.sh revoke` still removes the old file.

In the app, a device with no key shows a QR code (`corvid-approve:<id>?code=<code>`, which names no
server), the request id and the code, and polls every three seconds while it is on screen. A
master device opens Settings, Approve a device, scans the code (Android with a camera) or types the
id and code, and signs in to `corvid-admin` with the admin passkey. That sign-in happens for every
approval and its token is spent on the one request. The master device sends its own daily bearer
too, so it must be signed in as the mail user. `corvid-admin` needs the same callbacks as
`corvid`, `http://127.0.0.1/callback` for desktop and `com.verdenroz.corvid:/oauth2redirect` for
Android.

## `server.mail_user` is a local part, not an address

`Account.name` is "typically an email address local part", and the address is formed as
`name@domain` from `domainId`. So `mail_user: alice`, not `alice@example.com`. The full form
produces an account whose address is `alice@example.com@example.com`, which the apply accepts,
so `corvid-config` refuses a `mail_user` containing `@`.

If the mailbox already exists on the host, this must match the existing account's name exactly,
or the plan creates a second account beside it rather than adopting the first.

## Updates

A host that installed from a registry image (`install.sh` defaults to
`ghcr.io/verdenroz/corvid:stable`) updates itself. `corvid-update.timer` runs `update.sh` every
night at 04:00 with up to an hour of random delay. It pulls the tag in `/opt/corvid/image.ref`
and does nothing if the image is unchanged. Otherwise it keeps the previous image as
`localhost/corvid:rollback` and the previous deploy files in `/opt/corvid/deploy.prev`, copies the
new image's deploy files into `/opt/corvid/deploy`, and runs `bootstrap.sh` then `deploy.sh`,
which ends in `verify.sh`. If any step fails it puts the old image and files back and deploys them
again. A release that failed is not retried until a newer one is tagged. The outcome is in
`status.json`, which the Corvid app surfaces, and the log is `journalctl -u corvid-update.service`.

Every run writes `/opt/corvid/update/status.json`, first marking it `busy: true` while the run
works. A busy report repeats the previous outcome; on a host's first run, with nothing to repeat, it
reads `skipped` with detail "the first run is in progress". Watchers ignore a busy report. Writing `check` or `apply` to
`/opt/corvid/update/request` starts a run on demand: `check` only records the latest version, and
`apply` updates even with `update.auto: false`. `deploy.sh` queues a `check` when it finishes, so
a fresh or redeployed host has a status within seconds.

The Corvid app's Settings reads and drives this through `GET` and `POST /corvid/update` on the
web tier. Caddy checks the bearer token against Stalwart's `/jmap/session`, then proxies to the
sentinel, which serves `status.json` and writes the request file. The sentinel answers 404 where
`/opt/corvid/update` is not mounted, as on the chart, so the app shows "does not report its
version". On a host that follows a channel tag such as `main`, versions read
`main-<first 7 of the revision>`; a release tag keeps its own version.

Sign-in alerts and failed or rolled-back updates become notices in the sentinel's feed. It keeps the
newest 200 in its state, and the app reads it through `GET /corvid/notices?after=<id>` on the same
authenticated route, oldest first. An alert therefore shows only once a device has the app open.

A host running a `--ship-local` image (`localhost/...`) never updates itself.

To turn it off, set `update: {auto: false}` in `config.yaml`; the timer then logs that it is off.

To cut a release, bump `version` in `Cargo.toml`, merge, and push a tag:

    git tag v0.2.0 && git push origin v0.2.0

The `image` workflow publishes `0.2.0` and moves `stable`. A prerelease tag such as `v0.2.0-rc1`
publishes its own tag and leaves `stable` alone. The GHCR package must be public, or the host
cannot pull it.

To roll back by hand, on the host:

    podman tag localhost/corvid:rollback "$(cat /opt/corvid/image.ref)"
    podman image inspect -f '{{.Id}}' localhost/corvid:rollback > /opt/corvid/.deployed-id
    rm -rf /opt/corvid/deploy && mv /opt/corvid/deploy.prev /opt/corvid/deploy
    /opt/corvid/deploy/bootstrap.sh && /opt/corvid/deploy/deploy.sh

Set `update.auto: false` first, or the next night's run takes the release again.

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
