#!/usr/bin/env bash
# Enrolls and removes passkeys through the running sentinel. A second process writing the sentinel's
# state file would have its write overwritten by the sentinel's next commit, so every command here
# is a signed call into the pod, the way device.sh revokes a device.
#
#   passkey.sh code daily|master   mint a one-time enrollment code, shown once, valid for 24 hours
#   passkey.sh status              how many passkeys each role has
#   passkey.sh list                every passkey, by id
#   passkey.sh remove ID           remove one; a user's last passkey is refused
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
load_server

usage() {
  echo "usage: $(basename "$0") code daily|master | status | list | remove ID" >&2
  exit 64
}

# Prints the body of a signed call and fails with the sentinel's status unless it is wanted.
call() {
  local reply status
  reply="$(sentinel_signed "$1" "$2" "$3")"
  status="${reply%%$'\n'*}"
  case "$status" in
    200|204) printf '%s\n' "${reply#*$'\n'}" ;;
    503) echo "passkey sign-in is off: add sentinel.auth to config.yaml and restart corvid-sentinel" >&2; exit 78 ;;
    *) echo "the sentinel answered $status for $2: ${reply#*$'\n'}" >&2; exit 1 ;;
  esac
}

[[ $# -ge 1 ]] || usage
case "$1" in
  code)
    [[ $# -eq 2 && "$2" =~ ^(daily|master)$ ]] || usage
    call POST "/internal/auth/codes/$2" "code $2" | python3 -c '
import json, sys
reply = json.load(sys.stdin)
print(reply["code"])
print("valid until %s, once; minting another replaces it" % reply["expiresAt"], file=sys.stderr)'
    ;;
  status)
    [[ $# -eq 1 ]] || usage
    call GET /internal/auth/status status | python3 -c '
import json, sys
s = json.load(sys.stdin)
print("daily %d, master %d, codes waiting: %s" % (s["daily"], s["master"], " ".join(s["codes"]) or "none"))'
    ;;
  list)
    [[ $# -eq 1 ]] || usage
    call GET /internal/auth/status status | python3 -c '
import json, sys
for p in json.load(sys.stdin)["passkeys"]:
    print(p["id"], p["role"], p["createdAt"], "synced" if p["backupEligible"] else "device-bound")'
    ;;
  remove)
    [[ $# -eq 2 ]] || usage
    call DELETE "/internal/auth/passkeys/$2" "remove-passkey $2" >/dev/null
    echo "removed"
    ;;
  *) usage ;;
esac
