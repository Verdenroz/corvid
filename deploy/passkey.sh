#!/usr/bin/env bash
# Enrolls and removes passkeys through the running sentinel. A second process writing the sentinel's
# state file would have its write overwritten by the sentinel's next commit, so every command here
# is a signed call into the pod, the way device.sh revokes a device.
#
#   passkey.sh code [--approver]  mint a one-time setup code, shown once, valid for 24 hours; the key
#                                 it enrolls approves devices with --approver, and always does while
#                                 the server has no approver
#   passkey.sh status             how many passkeys there are, how many approve, codes waiting
#   passkey.sh list               every passkey: id, whether it approves devices, added, kind
#   passkey.sh remove ID          remove one; the last passkey and the last approver are refused
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
load_server

usage() {
  echo "usage: $(basename "$0") code [--approver] | status | list | remove ID" >&2
  exit 64
}

# Prints the body of a signed call and fails with the sentinel's status unless it is wanted.
call() {
  local reply status
  reply="$(sentinel_signed "$1" "$2" "$3" "${4:-}")"
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
    approves=false
    case "$#:${2:-}" in
      1:) ;;
      2:--approver) approves=true ;;
      *) usage ;;
    esac
    message=code
    [[ "$approves" == false ]] || message="code approver"
    call POST /internal/auth/codes "$message" "{\"approves\":$approves}" | python3 -c '
import json, sys
reply = json.load(sys.stdin)
print(reply["code"])
kind = "approves devices" if reply["approves"] else "does not approve devices"
print("valid until %s, once; the key it sets up %s" % (reply["expiresAt"], kind), file=sys.stderr)'
    ;;
  status)
    [[ $# -eq 1 ]] || usage
    call GET /internal/auth/status status | python3 -c '
import json, sys
s = json.load(sys.stdin)
approver = sum(code["approves"] for code in s["codes"])
plain = len(s["codes"]) - approver
waiting = ", ".join(n for n in (approver and "%d approver" % approver, plain and "%d plain" % plain) if n)
print("passkeys %d, approvers %d, codes waiting: %s" % (len(s["passkeys"]), s["approvers"], waiting or "none"))'
    ;;
  list)
    [[ $# -eq 1 ]] || usage
    call GET /internal/auth/status status | python3 -c '
import json, sys
for p in json.load(sys.stdin)["passkeys"]:
    print(p["id"], "approves" if p["approves"] else "-", p["createdAt"], "synced" if p["backupEligible"] else "device-bound")'
    ;;
  remove)
    [[ $# -eq 2 ]] || usage
    call DELETE "/internal/auth/passkeys/$2" "remove-passkey $2" >/dev/null
    echo "removed"
    ;;
  *) usage ;;
esac
