# Troubleshooting

## Checking a server

`deploy/verify.sh` runs at the end of every install, deploy and update. It checks what can be
observed from outside each service: units, DNS, firewall packet counters and the milter
handshake. By hand:

| Check | Expect |
|---|---|
| Passkey sign-in in the app | succeeds and opens the mailbox |
| An app password over IMAP | still signs in |
| Revoke one app password | the others keep working |

## What a rerun changes

Rerunning `install.sh` or `deploy.sh` brings the server back to the plan and your answers. It
restarts the pod only when its units, generated files or image changed, and restarts Stalwart only
when the applied settings differ from the ones it last started with. A run that changed nothing
restarts nothing.

Some Stalwart settings are reconciled: the plan's list becomes the whole list, and anything added
by hand is removed on the next run. That covers listeners and log outputs, so a debug tracer added
for an investigation is gone after a rerun. Change the plan instead, or remove the addition
yourself when you are done.

The listeners after a run are exactly 25, 465, 587, 993, 8080 and 8443. An older setup that also
served 143 (IMAP with STARTTLS) or 4190 (ManageSieve) loses them. 993 covers IMAP; ManageSieve has
no replacement.

## Replacing an existing Stalwart container

`bootstrap.sh` accepts mail ports held by a Podman container, reports it, and masks
`corvid-pod.service` so the pod cannot collide with it at the next boot. It never stops anything,
since retiring the old container interrupts mail. When you are ready:

```sh
podman rm -f <old-container>
systemctl unmask corvid-pod.service
./deploy/deploy.sh
```

Use `rm`, not `stop`: a container created with `--restart=always` comes back at the next boot
while `podman-restart.service` is enabled, and takes the ports from the pod.

The pod mounts `/opt/corvid/stalwart` as Stalwart's config directory and `/opt/corvid/data` as its
data directory. You can keep the old mailbox or start fresh.

To keep the old mailbox, copy the old `config.json` into `/opt/corvid/stalwart/`, move the old
data to `/opt/corvid/data`, and chown both to uid 2000. Set `STALWART_USER` and
`STALWART_PASSWORD` in `corvid.env` to the old admin credential. `server.mail_user` must match the
existing account name exactly, or the plan creates a second account beside it.

To start fresh, move the old directories aside as a rollback, recreate `/opt/corvid/data` with
`install -d -o 2000 -g 2000 -m 750`, and leave `/opt/corvid/stalwart` without a `config.json` so
Stalwart boots into bootstrap mode. `STALWART_USER` and `STALWART_PASSWORD` must be empty, or the
bootstrap treats the server as already set up and the apply fails to sign in.

## The account password

`install.sh` sets the account's password with `set-password.sh`, and rerunning it with a new
password replaces it. The plan never sets credentials: setting them there would replace every
app password along with the account password, while the apply still reported success, so
`apply-plan.sh` refuses a plan that tries.

## The spam filter learns slowly

Stalwart trains its spam classifier from mail you mark as junk or not junk. An empty Junk folder
looks the same whether nothing is being caught or nothing is being learned, so check the loop
directly:

```sh
stalwart-cli query SpamTrainingSample   # a new row appears after you mark a message as junk
```

Until there are enough samples of each kind (`minSpamSamples` and `minHamSamples`), the
classifier reports `SpamModelNotReady`. That is expected on a new mailbox, and the rules filter
runs regardless. Samples are kept for 180 days, so the floor of 100 spam samples means about four
marked as junk a week, sustained. A quiet mailbox may never reach it, in which case the rules
filter does all the work.

## New domains land in spam

Mail from a new domain lands in spam at first, even with SPF, DKIM and DMARC passing. Reputation
comes from time and from people replying to you, so start with correspondents who will.
