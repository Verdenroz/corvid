#!/usr/bin/env bash
# Follows the release tag in image.ref: pulls it, deploys what the new image carries, and puts
# the previous image and deploy files back if that fails. Run as root by corvid-update.timer,
# or by corvid-update.path when /opt/corvid/update/request holds `check` or `apply`. Every run
# leaves its outcome in update/status.json.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

ref="$CORVID_IMAGE"
rollback_tag=localhost/corvid:rollback
deploy_dir="$CORVID_DIR/deploy"
update_dir="$CORVID_DIR/update"
failed_marker="$CORVID_DIR/.update-failed"
deployed_marker="$CORVID_DIR/.deployed-id"
smtp_port="${CORVID_SMTP_PORT:-25}"

registry_ref() {
  [[ "$1" == */* ]] || return 1
  case "${1%%/*}" in
    localhost|localhost:*) return 1 ;;
    *.*|*:*) return 0 ;;
    *) return 1 ;;
  esac
}

image_id() { podman image inspect -f '{{.Id}}' "$1"; }

version_of() {
  local version
  version="$(podman image inspect -f '{{index .Labels "org.opencontainers.image.version"}}' "$1")"
  echo "${version:-$(image_id "$1" | cut -c1-12)}"
}

# The temp file lives outside update/, which the pod's uid can write, so a planted symlink
# cannot redirect root's write. Rename does not follow one either.
write_status() {
  local tmp
  tmp="$(mktemp "$CORVID_DIR/.status.XXXXXX")"
  RUNNING="$running" LATEST="$latest" RESULT="$result" DETAIL="$detail" AUTO="$UPDATE_AUTO" BUSY="${1:-false}" \
    PRIOR="$update_dir/status.json" python3 -c '
import datetime, json, os
e = os.environ
status = {
    "running": e["RUNNING"],
    "latest": e["LATEST"] or None,
    "checkedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "result": e["RESULT"],
    "detail": e["DETAIL"] or None,
    "auto": e["AUTO"] == "true",
    "busy": e["BUSY"] == "true",
}
if status["busy"] and os.path.exists(e["PRIOR"]):
    status = {**json.load(open(e["PRIOR"])), "busy": True}
print(json.dumps(status))' >"$tmp"
  chmod 644 "$tmp"
  mv "$tmp" "$update_dir/status.json"
}

smtp_expect() {
  local line
  while IFS= read -r -u "$smtp_fd" line; do
    line="${line%$'\r'}"
    [[ "$line" == "$1"* ]] || { printf 'QUIT\r\n' >&"$smtp_fd"; return 1; }
    [[ "${line:3:1}" == " " ]] && return 0
  done
  return 1
}

# Plain inbound SMTP to the host's own Stalwart. Every exit says QUIT: one malformed command
# gets the sender's address banned.
send_mail() {
  local to="$1" subject="$2" body="${3//$'\n'/$'\r\n'}" status
  exec {smtp_fd}<>"/dev/tcp/127.0.0.1/$smtp_port" || return 1
  smtp_expect 220 &&
    printf 'EHLO %s\r\n' "$MAIL_HOSTNAME" >&"$smtp_fd" && smtp_expect 250 &&
    printf 'MAIL FROM:<corvid-update@%s>\r\n' "$MAIL_HOSTNAME" >&"$smtp_fd" && smtp_expect 250 &&
    printf 'RCPT TO:<%s>\r\n' "$to" >&"$smtp_fd" && smtp_expect 250 &&
    printf 'DATA\r\n' >&"$smtp_fd" && smtp_expect 354 &&
    printf 'From: corvid-update@%s\r\nTo: %s\r\nSubject: %s\r\n\r\n%s\r\n.\r\n' \
      "$MAIL_HOSTNAME" "$to" "$subject" "$body" >&"$smtp_fd" && smtp_expect 250 &&
    printf 'QUIT\r\n' >&"$smtp_fd"
  status=$?
  exec {smtp_fd}>&-
  return $status
}

deploy_from() { bash "$1/bootstrap.sh" && bash "$1/deploy.sh"; }
keep_old() { podman tag "$rollback_tag" "$ref"; }

mode=timer
if [[ -e "$update_dir/request" ]]; then
  mode="$(head -c 16 "$update_dir/request" | tr -d '[:space:]')"
  rm -f "$update_dir/request"
  case "$mode" in
    check|apply) ;;
    *) echo "ignoring an update request that is neither check nor apply"; exit 0 ;;
  esac
fi

load_server
[[ -e "$deployed_marker" ]] || image_id "$ref" > "$deployed_marker"
deployed_id="$(cat "$deployed_marker")"
running="$(version_of "$deployed_id")"
latest=""
result=failed
detail="stopped before finishing"
trap write_status EXIT
write_status true

if ! registry_ref "$ref"; then
  result=skipped
  detail="image.ref is $ref, which is not a registry image; this host does not update itself"
  echo "$detail"
  exit 0
fi
if [[ "$mode" == timer && "$UPDATE_AUTO" != true ]]; then
  result=skipped
  detail="automatic updates are off (update.auto in $CORVID_DIR/config.yaml)"
  echo "$detail"
  exit 0
fi

podman tag "$deployed_id" "$rollback_tag"
if ! podman pull -q "$ref" >/dev/null; then
  detail="could not pull $ref"
  echo "$detail" >&2
  exit 1
fi
new_id="$(image_id "$ref")"
latest="$(version_of "$ref")"
if [[ "$new_id" == "$deployed_id" ]]; then
  result=current
  detail=""
  exit 0
fi
if [[ "$mode" == check ]]; then
  keep_old
  result=skipped
  detail="$latest is available; this was a check, so nothing was deployed"
  echo "$detail"
  exit 0
fi
if [[ "$mode" == timer && "$new_id" == "$(cat "$failed_marker" 2>/dev/null)" ]]; then
  keep_old
  result=skipped
  detail="$latest failed to deploy earlier; waiting for a newer release"
  echo "$detail"
  exit 0
fi

echo "updating $running to $latest"
rm -rf "$deploy_dir.prev" "$deploy_dir.new"
cp -a "$deploy_dir" "$deploy_dir.prev"
ctr="$(podman create "$ref")"
mkdir "$deploy_dir.new"
podman cp "$ctr:/usr/share/corvid/deploy/." "$deploy_dir.new"
podman rm "$ctr" >/dev/null
# bash reads this script through an open descriptor, so replacing its directory is safe.
rm -rf "$deploy_dir"
mv "$deploy_dir.new" "$deploy_dir"

if deploy_from "$deploy_dir"; then
  echo "$new_id" > "$deployed_marker"
  rm -f "$failed_marker"
  running="$latest"
  result=updated
  detail=""
  echo "updated to $latest"
  exit 0
fi

echo "deploying $latest failed; restoring $running" >&2
echo "$new_id" > "$failed_marker"
keep_old
rm -rf "$deploy_dir"
mv "$deploy_dir.prev" "$deploy_dir"
if deploy_from "$deploy_dir"; then
  result=rolledBack
  detail="$latest failed to deploy; $running is running again and verified"
else
  result=failed
  detail="$latest failed to deploy and restoring $running failed too; the host needs attention"
fi
echo "$detail" >&2
send_mail "postmaster@$MAIL_DOMAIN" "Corvid update to $latest failed on $MAIL_HOSTNAME" \
  "Updating $MAIL_HOSTNAME from $running to $latest failed.

$detail.

The log is in the journal: journalctl -u corvid-update.service" ||
  echo "could not mail postmaster@$MAIL_DOMAIN" >&2
exit 1
