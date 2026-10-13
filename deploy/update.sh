#!/usr/bin/env bash
# Follows the release tag in image.ref: pulls it, deploys what the new image carries, and puts
# the previous image and deploy files back if that fails. Run as root by corvid-update.timer,
# or by corvid-update.path when /opt/corvid/update/request holds `check`, `apply` or `deploy`.
# `deploy` runs the deploy files already on the host against the running image, with no pull; the
# sentinel asks for it when the first passkey that approves devices is set up. Every run leaves
# its outcome in update/status.json.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

ref="$CORVID_IMAGE"
rollback_tag=localhost/corvid:rollback
deploy_dir="$CORVID_DIR/deploy"
update_dir="$CORVID_DIR/update"
failed_marker="$CORVID_DIR/.update-failed"
deployed_marker="$CORVID_DIR/.deployed-id"

registry_ref() {
  [[ "$1" == */* ]] || return 1
  case "${1%%/*}" in
    localhost|localhost:*) return 1 ;;
    *.*|*:*) return 0 ;;
    *) return 1 ;;
  esac
}

image_id() { podman image inspect -f '{{.Id}}' "$1"; }

label_of() { podman image inspect -f "{{index .Labels \"org.opencontainers.image.$2\"}}" "$1"; }

# A moving tag such as main carries one version label on every build, so the revision tells
# builds apart.
version_of() {
  local version revision
  version="$(label_of "$1" version)"
  if [[ -z "$version" ]]; then
    image_id "$1" | cut -c1-12
  elif [[ "$version" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    echo "$version"
  else
    revision="$(label_of "$1" revision)"
    echo "$version${revision:+-${revision:0:7}}"
  fi
}

write_status() {
  local status
  status="$(RUNNING="$running" LATEST="$latest" RESULT="$result" DETAIL="$detail" AUTO="$UPDATE_AUTO" BUSY="${1:-false}" \
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
if status["busy"]:
    if os.path.exists(e["PRIOR"]):
        status = {**json.load(open(e["PRIOR"])), "busy": True}
    else:
        status = {**status, "result": "skipped", "detail": "the first run is in progress"}
print(json.dumps(status))')"
  atomic_write "$update_dir/status.json" "$status"
}

# CORVID_UPDATING keeps deploy.sh from queuing a check that would overwrite this run's status.
deploy_from() { bash "$1/bootstrap.sh" && CORVID_UPDATING=1 bash "$1/deploy.sh"; }
keep_old() { podman tag "$rollback_tag" "$ref"; }
prune_images() { podman image prune -f >/dev/null; }

mode=timer
if [[ -e "$update_dir/request" ]]; then
  mode="$(head -c 16 "$update_dir/request" | tr -d '[:space:]')"
  rm -f "$update_dir/request"
  case "$mode" in
    check|apply|deploy) ;;
    *) echo "ignoring an update request that is not check, apply or deploy"; exit 0 ;;
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
if unit_masked corvid-pod.service; then
  result=skipped
  detail="corvid-pod is masked (a container replacement is in progress); not updating"
  echo "$detail"
  exit 0
fi
if [[ "$mode" == deploy ]]; then
  if deploy_from "$deploy_dir"; then
    result=current
    detail=""
    exit 0
  fi
  detail="deploying $running failed; the host needs attention"
  echo "$detail" >&2
  exit 1
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
  prune_images
  result=skipped
  detail="$latest is available; this was a check, so nothing was deployed"
  echo "$detail"
  exit 0
fi
if [[ "$mode" == timer && "$new_id" == "$(cat "$failed_marker" 2>/dev/null)" ]]; then
  keep_old
  prune_images
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
  rm -f "$failed_marker"
  prune_images
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
  prune_images
  result=rolledBack
  detail="$latest failed to deploy; $running is running again and verified"
else
  result=failed
  detail="$latest failed to deploy and restoring $running failed too; the host needs attention"
fi
echo "$detail" >&2
exit 1
