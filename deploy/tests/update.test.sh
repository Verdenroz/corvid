#!/usr/bin/env bash
# Runs update.sh against a stubbed podman and stubbed bootstrap.sh and deploy.sh in each "image".
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

corvid="$work/corvid"
mkdir -p "$work/bin" "$work/ids"

cat > "$work/bin/podman" <<'SH'
#!/bin/bash
echo "$*" >> "$WORK/podman.log"
tagfile() { echo "$WORK/tags/$(echo "$1" | tr '/:' '__')"; }
resolve() { if [[ -e "$(tagfile "$1")" ]]; then cat "$(tagfile "$1")"; else echo "$1"; fi; }
case "$1 $2" in
  "image inspect")
    id="$(resolve "${@: -1}")"
    case "${*:3}" in
      *image.revision*) cat "$WORK/ids/${id}.revision" 2>/dev/null ;;
      *Labels*) cat "$WORK/ids/${id}.version" 2>/dev/null ;;
      *) echo "$id" ;;
    esac
    exit 0 ;;
esac
case "$1" in
  tag) resolve "$2" > "$(tagfile "$3")" ;;
  pull)
    [[ ! -e "$WORK/pull-fails" ]] || exit 1
    cat "$WORK/registry-id" > "$(tagfile "$3")" ;;
  create) echo "ctr-$(resolve "$2")" ;;
  cp) [[ ! -e "$WORK/cp-fails" ]] || exit 1
    src="${2#ctr-}"; src="${src%%:*}"; cp -a "$WORK/images/$src/." "$3/" ;;
  rm) ;;
esac
SH
cat > "$work/bin/systemctl" <<'SH'
#!/bin/bash
[[ -e "$WORK/masked" ]] && echo masked || echo enabled
SH
cat > "$work/config" <<'SH'
#!/bin/sh
printf 'MAIL_DOMAIN=example.com\nMAIL_HOSTNAME=mail.example.com\nUPDATE_AUTO=%s\n' "$(cat "$WORK/auto")"
SH
chmod +x "$work/bin/podman" "$work/bin/systemctl" "$work/config"

for which in old new; do
  mkdir -p "$work/images/$which"
  echo "$which" > "$work/images/$which/which"
  for step in bootstrap deploy; do
    cat > "$work/images/$which/$step.sh" <<SH
#!/bin/bash
echo "$step $which" >> "\$WORK/log"
echo "$step \${CORVID_UPDATING:-}" >> "\$WORK/updating"
[[ ! -e "\$WORK/$which-$step-fails" ]]
SH
    chmod +x "$work/images/$which/$step.sh"
  done
done
echo 1.0.0 > "$work/ids/old.version"
echo 1.1.0 > "$work/ids/new.version"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; sed "s/^/        /" "$work/out" 2>/dev/null; fail=1; fi; }
field() { python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$corvid/update/status.json" "$1"; }
tag() { cat "$work/tags/$(echo "$1" | tr '/:' '__')"; }

# fresh: a host running "old" from the registry tag, the registry still serving "old"
fresh() {
  rm -rf "$corvid" "$work/tags" "$work/log" "$work/updating" "$work/podman.log" \
    "$work"/*-fails "$work/pull-fails" "$work/masked"
  mkdir -p "$corvid/deploy" "$corvid/update" "$work/tags"
  cp -a "$work/images/old/." "$corvid/deploy/"
  cp "$here/update.sh" "$here/lib.sh" "$corvid/deploy/"
  echo ghcr.io/example/corvid:stable > "$corvid/image.ref"
  echo old > "$work/tags/ghcr.io_example_corvid_stable"
  echo old > "$work/registry-id"
  echo true > "$work/auto"
  touch "$work/log" "$work/podman.log"
}
# run [REQUEST]: runs the updater the way systemd would, then keeps its output and status
run() {
  [[ $# -eq 0 ]] || printf '%s' "$1" > "$corvid/update/request"
  WORK="$work" CORVID_DIR="$corvid" CORVID_CONFIG_CMD="$work/config" \
    PATH="$work/bin:$PATH" bash "$corvid/deploy/update.sh" > "$work/out" 2>&1
  # shellcheck disable=SC2034  # read by the eval-ed checks
  status=$?
}
deployed() { grep -q '^deploy new' "$work/log"; }
pulled() { grep -q '^pull' "$work/podman.log"; }
valid_status() { python3 -m json.tool "$corvid/update/status.json" >/dev/null; }

echo "== an unchanged digest is a no-op"
fresh; run
check "exits 0" '[[ $status -eq 0 ]]'
check "prints nothing" '[[ ! -s "$work/out" ]]'
check "deploys nothing" '[[ ! -s "$work/log" ]]'
check "records current" '[[ "$(field result)" == current && "$(field running)" == 1.0.0 && "$(field busy)" == False ]]'

echo "== a localhost ref never updates"
fresh; echo localhost/corvid:0.1.0 > "$corvid/image.ref"; echo new > "$work/registry-id"
run
check "exits 0 saying why" '[[ $status -eq 0 ]] && grep -q "not a registry image" "$work/out"'
check "pulls and deploys nothing" '! pulled && [[ ! -s "$work/log" ]]'
check "records skipped" '[[ "$(field result)" == skipped ]]'

echo "== update.auto false turns the timer run into a no-op"
fresh; echo false > "$work/auto"; echo new > "$work/registry-id"
run
check "exits 0 saying it is off" '[[ $status -eq 0 ]] && grep -q "automatic updates are off" "$work/out"'
check "pulls and deploys nothing" '! pulled && [[ ! -s "$work/log" ]]'
check "records skipped with auto false" '[[ "$(field result)" == skipped && "$(field auto)" == False ]]'

echo "== a successful update leaves the new image in place"
fresh; echo new > "$work/registry-id"
run
check "exits 0" '[[ $status -eq 0 ]]'
check "bootstraps then deploys the new files" '[[ "$(cat "$work/log")" == $'"'"'bootstrap new\ndeploy new'"'"' ]]'
check "the registry tag points at the new image" '[[ "$(tag ghcr.io/example/corvid:stable)" == new ]]'
check "the host deploy dir holds the new files" '[[ "$(cat "$corvid/deploy/which")" == new ]]'
check "the previous deploy dir is kept" '[[ "$(cat "$corvid/deploy.prev/which")" == old ]]'
check "tells deploy.sh not to queue its own check" '[[ "$(cat "$work/updating")" == $'"'"'bootstrap \ndeploy 1'"'"' ]]'
check "records updated" 'valid_status && [[ "$(field result)" == updated && "$(field running)" == 1.1.0 && "$(field latest)" == 1.1.0 ]]'

echo "== a failing deploy restores the previous image and files"
fresh; echo new > "$work/registry-id"; touch "$work/new-deploy-fails"
run
check "exits 1" '[[ $status -eq 1 ]]'
check "the registry tag points at the old image again" '[[ "$(tag ghcr.io/example/corvid:stable)" == old ]]'
check "the old deploy files are back" '[[ "$(cat "$corvid/deploy/which")" == old ]]'
check "the old files were deployed again" '[[ "$(tail -1 "$work/log")" == "deploy old" ]]'
check "records rolledBack" 'valid_status && [[ "$(field result)" == rolledBack && "$(field running)" == 1.0.0 && "$(field latest)" == 1.1.0 ]]'
check "the failed release is remembered" '[[ "$(cat "$corvid/.update-failed")" == new ]]'

echo "== the next night does not retry the release that failed"
: > "$work/log"
run
check "exits 0 without deploying" '[[ $status -eq 0 && ! -s "$work/log" ]]'
check "leaves the old image tagged" '[[ "$(tag ghcr.io/example/corvid:stable)" == old ]]'
check "records skipped" '[[ "$(field result)" == skipped ]]'

echo "== a rollback that fails too is reported as failed"
fresh; echo new > "$work/registry-id"; touch "$work/new-deploy-fails" "$work/old-deploy-fails"
run
check "records failed and says the host needs attention" 'valid_status && [[ "$(field result)" == failed ]] && [[ "$(field detail)" == *"needs attention" ]]'

echo "== a failed pull changes nothing"
fresh; echo new > "$work/registry-id"; touch "$work/pull-fails"
run
check "exits 1 and keeps the old image" '[[ $status -eq 1 && "$(tag ghcr.io/example/corvid:stable)" == old && ! -s "$work/log" ]]'
check "records failed" 'valid_status && [[ "$(field result)" == failed && "$(field latest)" == None ]]'

echo "== an update cut short after the pull is deployed on the next run"
fresh; echo new > "$work/registry-id"; touch "$work/cp-fails"
run
check "the interrupted run fails and deploys nothing" '[[ $status -ne 0 && ! -s "$work/log" ]] && [[ "$(field result)" == failed ]]'
rm "$work/cp-fails"
run
check "the next run deploys instead of reporting current" '[[ $status -eq 0 ]] && deployed && [[ "$(field result)" == updated ]]'

echo "== a masked pod blocks every mode"
for mode in "" check apply; do
  fresh; echo new > "$work/registry-id"; touch "$work/masked"
  run $mode
  check "${mode:-timer}: exits 0 without pulling or deploying" '[[ $status -eq 0 ]] && ! pulled && [[ ! -s "$work/log" ]]'
  check "${mode:-timer}: records skipped" '[[ "$(field result)" == skipped ]] && grep -q "container replacement" "$work/out"'
done

echo "== a check records the latest version and deploys nothing"
fresh; echo new > "$work/registry-id"
run check
check "exits 0 and deploys nothing" '[[ $status -eq 0 && ! -s "$work/log" ]]'
check "the local tag still points at the old image" '[[ "$(tag ghcr.io/example/corvid:stable)" == old ]]'
check "records latest beside running" 'valid_status && [[ "$(field latest)" == 1.1.0 && "$(field running)" == 1.0.0 && "$(field result)" == skipped ]]'
check "consumes the request" '[[ ! -e "$corvid/update/request" ]]'

echo "== an apply runs even with update.auto false"
fresh; echo new > "$work/registry-id"; echo false > "$work/auto"
run apply
check "deploys the new release" '[[ $status -eq 0 ]] && deployed && [[ "$(field result)" == updated ]]'
check "consumes the request" '[[ ! -e "$corvid/update/request" ]]'

echo "== an apply retries a release that failed before"
fresh; echo new > "$work/registry-id"; echo new > "$corvid/.update-failed"
run apply
check "deploys it" '[[ $status -eq 0 ]] && deployed'

echo "== a request that is neither check nor apply is ignored"
fresh; echo new > "$work/registry-id"
run 'rm -rf /'
check "exits 0, pulls nothing, deploys nothing" '[[ $status -eq 0 && ! -s "$work/log" ]] && ! pulled'
check "consumes the request and writes no status" '[[ ! -e "$corvid/update/request" && ! -e "$corvid/update/status.json" ]]'

echo "== an image without a version label reports its digest"
fresh; echo new > "$work/registry-id"; rm "$work/ids/new.version"
run check
check "latest falls back to the image id" '[[ "$(field latest)" == new ]]'
echo 1.1.0 > "$work/ids/new.version"

echo "== a release label is reported as is"
fresh; echo new > "$work/registry-id"; echo v1.1.0-rc1 > "$work/ids/new.version"; echo 0123456789abcdef > "$work/ids/new.revision"
run check
check "latest ignores the revision" '[[ "$(field latest)" == v1.1.0-rc1 ]]'

echo "== a channel label carries the revision"
echo main > "$work/ids/new.version"
run check
check "latest is the label and the first 7 of the revision" '[[ "$(field latest)" == main-0123456 ]]'
rm "$work/ids/new.revision"
run check
check "a channel label without a revision stays as is" '[[ "$(field latest)" == main ]]'
echo 1.1.0 > "$work/ids/new.version"

exit $fail
