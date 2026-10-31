# Setup

## 1. Get a server and a domain

These happen at your provider, registrar and Cloudflare, so the installer cannot do them.

- A VPS running Debian 13 or Ubuntu 24.04+, x86_64 or ARM, with outbound port 25 open. Many
  providers block 25 by default, so check before you pay.
- Its reverse DNS (PTR) set to the name the server will use, such as `mail.example.com`.
- Your domain on Cloudflare, and an API token for it with Zone / DNS / Edit and Zone / Zone / Read.
- Ports 25, 465, 587, 993, 80 and the web port open in the provider's firewall, if it has one.

## 2. Run the installer

From a checkout of this repository on your own machine, with an SSH key for root or a
passwordless sudo user:

```sh
./install.sh --ssh-key ~/.ssh/id_ed25519 root@mail.example.com
```

It offers to install Podman and ufw on a fresh server, then asks for your address and password,
the server's name, the web port (443, or 4443 if something already holds 443), who may connect,
a contact for certificate notices and the Cloudflare token. It checks DNS, PTR, port 25 and mail
blocklists and shows a summary before it changes anything.

After that it runs on its own: DNS records, certificates, then `deploy/verify.sh`. It ends by
printing a **setup code**, valid for 24 hours. Keep it for the next step.

The installer also offers to turn on DNSSEC, which DANE needs. If the domain is registered
outside Cloudflare, add the DS record it prints at your registrar.

Answering `host` to who may connect closes the client ports to the internet, so only programs on
the server, such as a VPN it runs, can reach them. Port 25 stays open for incoming mail.

## 3. Sign in from the app

Download the app from the [latest release](https://github.com/Verdenroz/corvid/releases/latest):
`corvid.apk` for Android 11+, or `corvid-linux-x64.tar.gz` with `install-desktop.sh` for Linux
(see [Apps](../apps/)). Enter your email address, choose **Set up this device** and type the
setup code. Then add a passkey. A security key makes the best first key, since it cannot be
synced off the device, but a phone passkey works too.

That first passkey approves devices. The server switches to passkey sign-in on its own, which
takes a minute or two while the app shows "Finishing setup".

## 4. Add more devices

A new device signs in and shows a code and a QR code. On a device you already use, open
Settings, **Approve a device**, pick it by name, enter or scan the code and touch your passkey.
[Passkeys and devices](../devices/) has the details.

If you lose every key, run `/opt/corvid/deploy/passkey.sh code --approver` on the server for a
new code.

## 5. Check it

Send yourself a message from another provider and reply to it. Mail from a new domain lands in
spam at first, even with SPF, DKIM and DMARC passing; reputation comes from time and from
people replying to you.

## Later

To change a setting, rerun `install.sh`. Each question offers the current value, and Enter
keeps it. [Configuration](../configuration/) covers the settings it does not ask about.
`deploy/uninstall.sh` on the server removes Corvid, and `--purge` deletes the mail too.

Until a mirror runs on a second machine, the server holds the only copy of your mail; see
[Backups](../backups/). To label mail from senders the labeler has not seen, point it at a model
on a machine you own ([Configuration](../configuration/#the-labelers-model)). To run on
Kubernetes instead of Podman, see [Kubernetes](../kubernetes/).
