#!/usr/bin/env bash
# Tests deploy/lib.sh's install_apps against a stubbed podman whose image holds a chosen tree.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/podman" <<'SH'
#!/bin/bash
case "$1" in
  create) echo ctr ;;
  cp) cp -a "$WORK/image/." "$3/" ;;
esac
SH
chmod +x "$work/bin/podman"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }
install_apps() {
  WORK="$work" CORVID_DIR="$work/corvid" PATH="$work/bin:$PATH" bash -c "set -euo pipefail; . \"$here/lib.sh\"; install_apps"
}
apps="$work/corvid/apps"
mkdir -p "$apps"

echo "== a first install from an image with a release"
mkdir -p "$work/image"
echo one > "$work/image/manifest.json"; echo apk1 > "$work/image/corvid.apk"
install_apps
check "the release lands in current" '[[ "$(cat "$apps/current/manifest.json")" == one ]]'
check "the staging directories are gone" '[[ ! -e "$apps/.new" && ! -e "$apps/.old" ]]'

echo "== a newer release"
echo two > "$work/image/manifest.json"; rm "$work/image/corvid.apk"
install_apps
check "current holds the new manifest" '[[ "$(cat "$apps/current/manifest.json")" == two ]]'
check "a file only the old release had is gone" '[[ ! -e "$apps/current/corvid.apk" ]]'

echo "== an image without a release"
rm "$work/image/manifest.json"; touch "$work/image/.gitkeep"
install_apps
check "current exists and is empty" '[[ -d "$apps/current" && -z "$(ls -A "$apps/current")" ]]'

echo "== an image older than baked releases"
echo three > "$work/image/manifest.json"
install_apps
rm -rf "$work/image"
install_apps
status=$?
check "the install still succeeds" '[[ $status -eq 0 ]]'
check "current is left empty rather than holding the old release" '[[ -d "$apps/current" && -z "$(ls -A "$apps/current")" ]]'

exit "$fail"
