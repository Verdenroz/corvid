# Passkeys and devices

Every passkey signs in to mail. A passkey set up as an **approver** can also approve new devices
and change passkeys. Whoever makes a setup code chooses which kind it enrolls, and the first
passkey on a server is always an approver.

## The first passkey

`install.sh` ends by printing a setup code, valid for 24 hours. In the app, choose
**Set up this device**, enter the code and add a passkey. Registering signs you in, so there is
no second prompt.

!!! tip
    Make the first key a security key. A phone passkey works, but it can be synced off the device.

The sentinel notices the first approver and asks the host to redeploy from the files it already
has, with no image pull. That switches Stalwart to passkey sign-in and restarts it, which takes a
minute or two; the app shows "Finishing setup" until Stalwart accepts the token. From then on
Stalwart refuses its admin password, and the deploy scripts use an admin API key
(`STALWART_TOKEN` in `corvid.env`) instead. App passwords for IMAP and SMTP keep working.

If that deploy fails, the app's notices say "deploying <tag> failed; the host needs attention".
Run `/opt/corvid/deploy/deploy.sh` by hand and read `journalctl -u corvid-update`. The passkey is
already enrolled, so nothing needs enrolling again.

A later interactive deploy prints another setup code only while no approver exists and no
approver code is waiting. The nightly update never prints one, since its output goes to the
journal.

## More passkeys

In the app, **Settings, Passkeys** adds a passkey on this device or shows a code for another one.
Both ask for an approver passkey. On the server:

```sh
/opt/corvid/deploy/passkey.sh code              # a code for a key that signs in only
/opt/corvid/deploy/passkey.sh code --approver   # a code for a key that also approves devices
/opt/corvid/deploy/passkey.sh status            # passkeys, approvers and codes waiting
/opt/corvid/deploy/passkey.sh list              # every passkey and whether it approves devices
/opt/corvid/deploy/passkey.sh remove ID
```

The last passkey and the last approver cannot be removed, so no removal can lock the server out.
Removing a passkey signs out every session it started.

## Lost every key

Shell access is enough: run `passkey.sh code --approver` on the server and set up a replacement
with the code it prints.

## Approving a new device

The Corvid app opens only once it holds a key the server keeps for that device, and a device gets
its key only when an approver passkey approves it. A stolen mail session therefore cannot enroll a
device. When a device's key is removed, the app locks itself and shows that it was revoked.

<!-- soothfast:bind corvid_sentinel::devices::routes -->
A new device signs in and asks for a key. It shows a QR code, a request id and a six-digit code,
and checks every three seconds for an answer. On a device you already use, open
**Settings, Approve a device**, pick the new device by name, scan or type the code, and touch an
approver passkey. That passkey sign-in is used for this one request and nothing else. A request
lasts ten minutes, is spent by the first correct code, and dies after five wrong ones.
<!-- /soothfast:bind -->

A device can approve its own request with **Approve with a passkey** on its enrollment screen, so
the first device of an account needs no second one.

`device.sh revoke NAME` removes the device's key along with its record and its app password.

## Other mail clients

Clients other than Corvid sign in with an app password, one per device. On the server:

```sh
/opt/corvid/deploy/device.sh add laptop
/opt/corvid/deploy/device.sh list
/opt/corvid/deploy/device.sh revoke laptop
```

The secret prints once, in that terminal only. `add` refuses a name already in use. An app
password is limited to the networks `server.client_access` allows. Revoking one leaves the
others working, and reminds you to revoke the device's VPN access too, if it has any.

### Services

The labeler, the scribe and the mirror sign in over JMAP like any other client, each with its own
app password, so revoking one does not stop the others. The labeler and scribe call JMAP from
inside the pod, so their app passwords are limited to loopback with `--local`:

```sh
/opt/corvid/deploy/device.sh add --local corvid-labeler   # into labeler.env
/opt/corvid/deploy/device.sh add --local corvid-scribe    # into scribe-mail.env
/opt/corvid/deploy/device.sh add corvid-mirror            # into mirror.env, on the mirror machine
```

Each secret goes in that file's `CORVID_JMAP_PASSWORD`. The mirror is off the server and reaches
JMAP like a phone would, so it takes a plain `add`.

## Two-factor on the account password

`totp.sh` adds a TOTP code to the account password:

```sh
/opt/corvid/deploy/totp.sh enable
/opt/corvid/deploy/totp.sh status
/opt/corvid/deploy/totp.sh disable
```

Once it is on, IMAP, SMTP and JMAP refuse the account password outright, with no way to enter a
code, so a leaked password alone opens nothing. Every other mail client must be on an app
password first; `enable` refuses until one exists (`--force` skips the check). Loopback app
passwords from `--local` do not count.

`enable` prints an `otpauth://` URI once, with a QR code if `qrencode` is installed, and changes
nothing on the server until a code typed back at the terminal checks out. `disable` needs only
shell access, so it is the way back after losing the phone.

The Corvid app signs in with passkeys, so this does not guard it.

## Sign-in alerts

Stalwart reports sign-ins to the sentinel, which turns them into notices in the app. Stalwart
drops a report it could not deliver after 24 hours, so a sentinel down longer than that loses
the sign-ins in that window, with no record anywhere else. Check it with
`systemctl status corvid-sentinel`.

A notice shows once a device has the app open; there is no push for it.
