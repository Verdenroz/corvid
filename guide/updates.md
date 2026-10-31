# Updates

A server installed from a registry image updates itself. `install.sh` defaults to
`ghcr.io/verdenroz/corvid:stable`. Every night at 04:00, with up to an hour of random delay, the
server pulls the tag in `/opt/corvid/image.ref` and does nothing if the image has not changed.

!!! note
    A server running a locally built image (`localhost/...`) never updates itself.

## What a run does

1. Keeps the current image as `localhost/corvid:rollback` and the current deploy files in
   `/opt/corvid/deploy.prev`.
2. Copies the new image's deploy files into `/opt/corvid/deploy` and runs `bootstrap.sh`, then
   `deploy.sh`, which ends in `verify.sh`.
3. If any step fails, puts the old image and files back and deploys them again.

A release that failed is not retried until a newer one is tagged. The log is
`journalctl -u corvid-update.service`.

## Status

Every run writes `/opt/corvid/update/status.json`, marking it `busy: true` while it works. The app
shows it under **Settings, Updates**, and a failed or rolled-back update also appears in its
notices. On a server that follows a channel tag such as `main`, versions read
`main-<first 7 of the commit>`; a release keeps its own version.

Two checks read files a Cloudflare Worker may serve instead of the server: `assetlinks.json` and
the MTA-STS policy. When the Worker's copy is out of date, `verify.sh` reports it as `stale`
instead of failing, since no release or rollback can change it. The app shows it as "Out of date
off this host". Rerun `install.sh` to republish it.

## On demand

```sh
echo check > /opt/corvid/update/request   # record the latest version only
echo apply > /opt/corvid/update/request   # update now, even with auto updates off
```

`deploy.sh` queues a `check` when it finishes, so a fresh server has a status within seconds.

## Turning it off

Set `update: {auto: false}` in `/opt/corvid/config.yaml`. The timer still runs and logs that
updates are off.

## Rolling back by hand

Turn auto updates off first, or the next night's run takes the release again. Then, on the
server:

```sh
podman tag localhost/corvid:rollback "$(cat /opt/corvid/image.ref)"
podman image inspect -f '{{.Id}}' localhost/corvid:rollback > /opt/corvid/.deployed-id
rm -rf /opt/corvid/deploy && mv /opt/corvid/deploy.prev /opt/corvid/deploy
/opt/corvid/deploy/bootstrap.sh && /opt/corvid/deploy/deploy.sh
```

### Past the approver-key release

The sentinel's `auth.json` changed format in the release that introduced approver keys. Its first
start keeps the old file as `auth.json.schema1.bak`, once. Rolling back past that release works
on its own until someone signs in or enrolls, because that rewrites the file in the new format.
After that the older image refuses the file and exits 1, so restore the copy first:

```sh
cp /opt/corvid/sentinel/auth.json.schema1.bak /opt/corvid/sentinel/auth.json
```

Passkeys enrolled since the update are lost and need new setup codes.
