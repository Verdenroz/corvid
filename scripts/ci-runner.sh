#!/usr/bin/env bash
# Runs this repo's GitHub Actions jobs on the machine it is run from: GitHub's runner in a
# container, registered to this repo only under the label `corvid`. Needs docker and an
# authenticated `gh` with admin on the repo.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
readonly HERE
readonly REPO=Verdenroz/corvid
readonly NAME=corvid-ci-runner
readonly IMAGE=localhost/corvid-ci-runner:latest
readonly WORK="${XDG_DATA_HOME:-$HOME/.local/share}/corvid-ci/work"
readonly SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
readonly UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

usage() {
  echo "usage: $(basename "$0") up|down [--purge]|status" >&2
  exit 64
}

up() {
  docker build -q -f "$HERE/ci-runner/Containerfile" \
    --build-arg "HOST_UID=$(id -u)" --build-arg "HOST_GID=$(id -g)" \
    -t "$IMAGE" "$HERE/ci-runner" >/dev/null
  mkdir -p "$WORK/tmp" "$UNIT_DIR"
  systemctl --user stop "$NAME" 2>/dev/null || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true

  local token
  token="$(gh api -X POST "repos/$REPO/actions/runners/registration-token" --jq .token)"
  RUNNER_TOKEN="$token" docker create --name "$NAME" \
    --network host \
    --group-add "$(stat -c %g /var/run/docker.sock)" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$WORK:$WORK" \
    -v "$SDK:$SDK:ro" \
    -v corvid-ci-home:/home/runner \
    -e RUNNER_TOKEN \
    -e "RUNNER_URL=https://github.com/$REPO" \
    -e "RUNNER_NAME=$NAME" \
    -e RUNNER_LABELS=corvid \
    -e "RUNNER_WORK=$WORK" \
    -e "TMPDIR=$WORK/tmp" \
    -e "ANDROID_HOME=$SDK" \
    "$IMAGE" >/dev/null

  install -m 644 "$HERE/ci-runner/$NAME.service" "$UNIT_DIR/$NAME.service"
  systemctl --user daemon-reload
  systemctl --user enable --now "$NAME"
}

down() {
  systemctl --user disable --now "$NAME" 2>/dev/null || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -f "$UNIT_DIR/$NAME.service"
  systemctl --user daemon-reload
  [[ "${1:-}" == --purge ]] || return 0

  local id
  id="$(gh api "repos/$REPO/actions/runners" --jq ".runners[] | select(.name == \"$NAME\") | .id")"
  [[ -z "$id" ]] || gh api -X DELETE "repos/$REPO/actions/runners/$id"
  docker volume rm corvid-ci-home >/dev/null
}

status() {
  systemctl --user is-active "$NAME" || true
  docker ps -a --filter "name=^$NAME\$" --format '{{.Status}}'
  gh api "repos/$REPO/actions/runners" \
    --jq '.runners[] | "\(.name) \(.status) busy=\(.busy) \([.labels[].name] | join(","))"'
}

case "${1:-}" in
  up) up ;;
  down) down "${2:-}" ;;
  status) status ;;
  *) usage ;;
esac
