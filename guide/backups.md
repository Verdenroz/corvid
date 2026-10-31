# Backups

Until a mirror runs, the server holds the only copy of your mail.

`corvid-mirror` runs on a second machine. Every night it pulls the whole mailbox over JMAP,
encrypted to an [age](https://age-encryption.org) key, checks the archive for missing messages,
and rehashes every stored file. The nightly run never needs the key's identity.

## Setting it up

1. Make an age key with `age-keygen`, or `cargo run --example keygen -p corvid-mirror` if
   neither `age` nor `rage` is installed. Put the public key in `mirror.age_recipient` in
   `config.yaml`, and keep the identity off the mirror machine entirely.
2. Give the mirror its own app password with `device.sh add corvid-mirror` on the server, and
   put it in `mirror.env` on the mirror machine.
3. Install `corvid-mirror.service` and `corvid-mirror.timer` from `deploy/` on the mirror
   machine. The image does not carry `corvid-mirror`; install the binary there directly.

Only two commands read the identity, and you run both by hand.

## Checking an archive

<!-- soothfast:bind corvid_mirror::verify_deep -->
`verify --deep` decrypts every stored body and checks it against the digest and size recorded
when it was fetched. The nightly check only rehashes the encrypted files; this one proves they
decrypt to the mail that was fetched. It needs no server.
<!-- /soothfast:bind -->

```sh
corvid-mirror verify --deep <identity-file>
```

## Restoring

```sh
corvid-mirror restore <identity-file> <ledger-file> <jmap-url> <jmap-user>
```

<!-- soothfast:bind corvid_mirror::restore -->
Restore writes into a fresh account and records each message in a ledger, so a run that stops
partway resumes with `--resume`. It delivers at least once: a crash between an import and its
ledger entry leaves one duplicate on the target.
<!-- /soothfast:bind -->

Stalwart answers a long run of imports with `429 Too Many Requests`. Restore stops there rather
than retry; wait a minute and rerun with `--resume`.

## Proving it restores

!!! warning
    Practice a restore on a throwaway Stalwart of the same version as the server, never the real
    one. Repeat it after any change to sync, `encrypt-archive` or restore.

1. Start a throwaway Stalwart on loopback ports that no other test server uses, bootstrap it, and
   create one account.
2. Seed it past the mirror's page size of 500 messages, across at least one nested mailbox, with
   keywords on some. `Email/import` after a blob upload is far faster than SMTP, which is
   throttled and capped per session.
3. Mirror it. Run `verify --deep`, and grep the whole archive for a marker planted in one body: it
   must not appear anywhere, metadata and index included.
4. Destroy the server and start a fresh one with a new account.
5. Restore into it. Check the server itself, not the command's exit code: message count, mailbox
   tree and per-mailbox counts, and `receivedAt`, keywords and `Message-Id` on a sample.
6. Do it again, killing `restore` partway (`timeout -s KILL`). Both the ledger and the target
   should show partial progress; rerun with `--resume` and check the audit is clean.

Two independently bootstrapped servers often give their first account the same id, so restore
does not identify a server by account id alone.

## Migrating a plaintext archive

An archive synced before encryption existed has a `ledger.json` and plaintext blobs, and the
mirror refuses to run against it until it is converted:

```sh
corvid-mirror encrypt-archive
```

It refreshes mailboxes and keywords for every message still on the server, encrypts every blob,
then removes `ledger.json` and the plaintext copies. A crash partway is safe to rerun. If
`ledger.json` is still there after it reports success, delete it by hand once `verify --deep`
passes.
