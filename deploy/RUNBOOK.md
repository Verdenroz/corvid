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

These cannot be provisioned at all: the secret is server-generated and returned once, and
setting it fails with `Cannot modify server set property`. They are also per-account and
invisible across accounts, so an operator can neither list nor revoke someone else's.

The account holder creates each one, authenticated as themselves:

    stalwart-cli create AppPassword --json '{"description":"phone-imap","allowedIps":{}}'

The secret is printed once, at creation. `description` is the per-device label that makes
revocation meaningful later; without it the list is a row of opaque ids.

App passwords belong to the account holder rather than the operator. That is the security
property, not a gap. It does mean the Phase 2 test (a passkey login on the web while an app
password still works for IMAP) has a human step that no script can do.

## Configuration

Settings live in one file, `/opt/corvid/config.yaml`, mounted read-only into each container at
`/etc/corvid/config.yaml` (another path can be given in `CORVID_CONFIG`). `bootstrap.sh` installs
the commented template from `deploy/config.yaml` once and never overwrites it; `deploy.sh`
refuses while it still holds the template's `example.com` values. Each service reads only its
own section and refuses unknown keys, so a misspelt setting stops it with exit 78 and the line
at fault rather than silently taking a default.

The file holds no secrets, so it can be shared or committed. Secrets come from the env files
`bootstrap.sh` creates empty and mode 600, with templates in `deploy/env/`:

    rewriter.env   CORVID_IMGPROXY_SECRET (openssl rand -hex 32)
    labeler.env    CORVID_JMAP_PASSWORD, an app password for the labeler alone
    mirror.env     CORVID_JMAP_PASSWORD, a different app password, on the mirror machine

Use two separate app passwords, created as in the section above, so revoking either does not stop
the other. The image-proxy secret signs URLs in the milter and verifies them in the proxy, so both
read the same value. Changing it invalidates the links in mail already delivered: those images
stop loading, which is a visible cost and not a broken mailbox.

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

## Checking it

    passkey login on the web       succeeds through the identity provider
    app password over IMAP         still authenticates
    revoke one app password        the others and the account password keep working
