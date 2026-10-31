# Configuration

Settings live in one file, `/opt/corvid/config.yaml`, mounted read-only into each container at
`/etc/corvid/config.yaml` (`CORVID_CONFIG` names another path). `bootstrap.sh` installs the
commented template from `deploy/config.yaml` once and never overwrites it, and `deploy.sh` refuses
to run while it still holds the template's `example.com` values.

Each service reads only its own section and refuses unknown keys, so a misspelt setting stops it
with exit 78 and the line at fault instead of a silent default.

!!! warning
    A key the running image does not know also exits every service 78. Add a new section to a
    server's `config.yaml` only once it runs an image that parses it.

## Secrets

`config.yaml` holds no secrets, so it can be shared or committed. Secrets live in env files that
`bootstrap.sh` creates empty and mode 600, with templates in `deploy/env/`:

| File | Holds |
|---|---|
| `rewriter.env` | `CORVID_IMGPROXY_SECRET` (`openssl rand -hex 32`) |
| `labeler.env` | `CORVID_JMAP_PASSWORD`, from `device.sh add --local corvid-labeler` |
| `mirror.env` | `CORVID_JMAP_PASSWORD`, from `device.sh add corvid-mirror`, on the mirror machine |
| `sentinel-webhook.env` | `CORVID_SENTINEL_SECRET` (`openssl rand -hex 32`), shared with Stalwart |
| `scribe-mail.env` | `CORVID_JMAP_PASSWORD`, from `device.sh add --local corvid-scribe` |
| `scribe.env` | `CORVID_SCRIBE_SECRET` (`openssl rand -hex 32`), shared by labeler, scribe and sentinel |

`scribe.env` is read only when `labeler.suggest`, `scribe` or `sentinel.scribe` is in
`config.yaml`. Fill it before adding any of them, since each service exits 78 on its section
without the secret. `install.sh` generates it once and keeps it afterwards.

The image-proxy secret signs image URLs in the milter and verifies them in the proxy. Changing it
breaks the images in mail already delivered: they stop loading, though the mailbox is fine.

## The web port

<!-- soothfast:bind corvid_config::WebPort corvid_config::POD_PORTS -->
The web tier answers on `server.web_port`, which `install.sh` suggests as 443, or 4443 when
something on the server already holds 443. It cannot be a port the pod already uses, since every
container shares one network namespace: 25, 80, 465, 587, 993, 5335, 8080, 8081, 8443, 11332 or
11333.
<!-- /soothfast:bind -->

Everything that names the web tier is generated from `server.hostname` and `server.web_port`: the
pod's published port, Caddy's site address, Stalwart's hostname and the milter's proxy base, all
in `/opt/corvid/server.env`, which every run rewrites. Rerun `install.sh` to change either; it
also opens the new port in the firewall.

Changing the hostname or port after mail has arrived breaks its images. The milter writes proxy
URLs into each message at delivery and that rewritten copy is what Stalwart stores, so images in
mail already delivered point at an address that no longer answers.

## The mail user

<!-- soothfast:bind corvid_config::LocalPart -->
`server.mail_user` is the local part alone: `alice`, not `alice@example.com`. Stalwart forms the
address from the account name and its domain, so a full address would become
`alice@example.com@example.com`, and the config refuses one.
<!-- /soothfast:bind -->

On a server that already has the mailbox, it must match the existing account's name exactly, or
the plan creates a second account beside it.

## A hostname outside the mail domain

A server can keep a name in another zone, such as `n1.example.net` serving mail for
`example.com`, when that name is also its PTR. Set `server.host_zone: example.net`. Stalwart then
gets a second domain for that zone, used only to keep a certificate: it publishes no records
there, and mail to that zone is refused. The Cloudflare token must reach both zones. Records the
zone already has, such as an old MX, stay until you change them.

## MTA-STS off 443

Senders fetch the MTA-STS policy from `https://mta-sts.<domain>` on 443 only. When
`server.web_port` is not 443, `install.sh` deploys a Cloudflare Worker on that name to serve it.
The token then needs two more permissions: **Workers Scripts: Edit** on the account and
**Workers Routes: Edit** on the zone holding `server.domain`. A token missing one fails naming
it. Switching back to 443 removes the Worker and its `_mta-sts` record.

`server.mta_sts_mode` starts as `testing`. Watch delivery before switching it to `enforce`: a
sender that sees `enforce` and cannot complete TLS to your MX drops the message rather than
deliver it unencrypted.

## The labeler's model

`labeler.model` is optional. Without it the labeler still labels mail from senders it knows,
learned from your own labels; the model adds guesses for senders it has not seen.

<!-- soothfast:bind corvid_config::Model -->
Any OpenAI-compatible chat endpoint works if it returns `logprobs` and `top_logprobs`, since the
labeler reads the probability of each option instead of generated text. It asks about each
message once per rotation of the options (your labels plus "none of these"), so a message with
five labels costs six calls, and writes a label only when its choice reaches `confidence`
percent, 80 by default. A pass handles up to `budget` messages, 20 by default, and stops starting
new ones after `deadline_secs`.
<!-- /soothfast:bind -->

A server that returns only text fails the first call with an error saying so. It is tested on
llama-server; vLLM and SGLang implement the same fields and are untested. A model with a thinking
mode needs it turned off in `request_extra`, or every answer goes to the thinking block; the
template shows the switch for Qwen3-family models.

Model size matters more than speed. On four obvious messages with five labels, a 2B model cleared
the 80% threshold on one and a 35B-A3B model on all four, at about 4 seconds a message. Run the
live test against a new server or model before trusting it:

```sh
cargo test -p corvid-labeler --test live -- --ignored --nocapture
```

## Thread summaries

The `scribe` section turns on thread summaries, written by any OpenAI-compatible chat endpoint,
one thread per call. Its unit stays masked until `config.yaml` has the section. A model with a
thinking mode needs it turned off in `scribe.model.request_extra`. A reply that is empty, cut off
at `max_tokens` or starts with `<think>` is not stored, and that thread is not asked about again
until it changes.

The first run adopts the mailbox as it is, so only threads that change afterwards are
summarized. Junk, Trash and `exclude_mailboxes` are never sent to the model. A pass that cannot
reach the model ends quietly and tries again next time.

## The morning digest

`sentinel.scribe.digest` (`at: "07:00"` and an IANA `timezone`) has the sentinel build one digest
a day from the Inbox mail since the last one, using thread summaries where it has them. It needs
`sentinel.devices` but no model. A sentinel that was down at `at` builds the day's digest when it
comes back. Devices that ask for it also get a push, which names no mail.
