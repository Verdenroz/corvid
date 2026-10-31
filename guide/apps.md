# Apps

The Corvid app runs on Android 11+ and Linux desktop, from one Kotlin Multiplatform codebase. It
speaks only JMAP and signs in with passkeys.

## Android

Download `corvid.apk` from the
[latest release](https://github.com/Verdenroz/corvid/releases/latest) and install it. After that,
the app updates itself from your server.

!!! note
    Samsung's Auto Blocker refuses apps from outside the store, updates included. Turn it off to
    install or update Corvid.

## Linux desktop

Download `corvid-linux-x64.tar.gz` and `install-desktop.sh` from the
[latest release](https://github.com/Verdenroz/corvid/releases/latest), then run:

```sh
bash install-desktop.sh corvid-linux-x64.tar.gz
```

It unpacks into `~/.local/share/corvid/versions/<n>` (respecting `$XDG_DATA_HOME`), points
`current` at it, links `~/.local/bin/corvid` and adds a `corvid.desktop` entry. The app updates
itself only when it runs from this install: Update unpacks the verified tarball as the next
version, switches `current` and restarts. The newest two versions stay on disk, so the previous
one still runs from `versions/<n>/corvid/bin/corvid`. Started from anywhere else, the app offers
the update and then says it needs the install script.

Passkeys on the desktop are security keys, used through libfido2 1.14 or later. With an older
libfido2 the app reports passkeys as unsupported.

## Signing in from an email address

The app takes an address such as `you@example.com` and finds the server from its domain.
`install.sh` publishes what that needs, in the mail domain's zone:

- an SRV record, `_corvid._tcp.<domain>`, pointing at `server.hostname` on `server.web_port`
- `/.well-known/corvid` on the web tier: `{"version":2,"server":"https://<hostname>:<web_port>"}`

Without the SRV record the app tries `https://<domain>/.well-known/corvid` on 443, which works
only when the web tier is there. The app shows the two hostnames it found before signing in, and
**Advanced** takes both by hand.

Check both from anywhere:

```sh
dig +short SRV _corvid._tcp.example.com
curl https://mail.example.com:4443/.well-known/corvid
```

A changed `server.web_port` updates the record on the next install. When a record already points
elsewhere, `install.sh` stops before changing anything; fix it and rerun. When Cloudflare refuses
to create it, `install.sh` warns and carries on, since mail works without it. Add it by hand:

```text
_corvid._tcp.example.com.  SRV  0 5 4443 mail.example.com.
```
