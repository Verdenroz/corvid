# Corvid

Self-hosted email for one mailbox, built on [Stalwart](https://stalw.art). Corvid strips trackers
at delivery, learns labels from the ones you set, and sends nothing through a relay.

This guide covers running it: setting up a server, signing in from your devices, configuring
it, keeping it up to date and backing it up.

## Where to go

- [Setup](setup/): from an empty VPS to a signed-in phone.
- [Passkeys and devices](devices/): passkeys, approving a new device, app passwords for other
  mail clients.
- [Apps](apps/): installing the Android and desktop apps.
- [Configuration](configuration/): `config.yaml`, secrets, the web port and the optional
  models.
- [Updates](updates/): the nightly update, what rolls back, and doing it by hand.
- [Backups](backups/): the encrypted mirror and proving it restores.
- [Kubernetes](kubernetes/): running the same pod from the Helm chart.
- [Troubleshooting](troubleshooting/): replacing an existing Stalwart, and checking that a
  server works.

## What the server can read

The server can read your mail, since search and labeling run on it. Mail is encrypted in
transit; disk encryption is up to you.

## What runs where

Everything on the mail server runs as one Podman pod: Stalwart, a validating resolver for DANE,
the milter that strips trackers, the image proxy, the labeler, the sentinel that handles passkeys
and devices, and Caddy in front of the web tier. The milter is the only Corvid service on the
delivery path. While it is down, Stalwart defers incoming mail and senders retry, so mail is never
accepted unstripped. Everything else is off that path: when it fails, labels or notices arrive
late, and mail still arrives.

The mirror is the exception. It runs on a second machine and pulls the mailbox over JMAP.
