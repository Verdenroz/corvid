#!/usr/bin/env bash
# Enrols or clears TOTP two-factor on the account credential for server.mail_user. Guards the
# account password's own sign-in (web, JMAP, IMAP, SMTP); app passwords are a separate
# credential and keep signing in unchanged.
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
set -a; . "$ENV_FILE"; set +a
. "$HERE/lib.sh"
load_server

usage() { echo "usage: $(basename "$0") disable | status" >&2; exit 64; }

account_id() {
  stalwart-cli query Account --json | python3 -c '
import json, os, sys
address = os.environ["MAIL_USER"] + "@" + os.environ["MAIL_DOMAIN"]
print(next(a["id"] for a in map(json.loads, filter(str.strip, sys.stdin)) if a.get("emailAddress") == address))'
}

# The key of the account's Password credential, same lookup set-password.sh uses: found by
# @type rather than assumed, since "0" is only a fallback for an account with none yet.
password_key() {
  stalwart-cli get Account "$1" --fields credentials --json | python3 -c '
import json, sys
creds = json.load(sys.stdin)["credentials"]
keys = [k for k, v in creds.items() if v.get("@type") == "Password"]
if len(keys) > 1:
    print(f"expected at most one Password credential, found {len(keys)}", file=sys.stderr)
    sys.exit(1)
print(keys[0] if keys else "0")'
}

otp_auth() {
  stalwart-cli get Account "$1" --fields credentials --json | python3 -c '
import json, sys
entry = json.load(sys.stdin)["credentials"].get(sys.argv[1]) or {}
print(entry.get("otpAuth") or "")' "$2"
}

patch_otp_auth() {
  local id="$1" key="$2" value="$3"
  python3 -c '
import json, sys
value = sys.argv[2] or None
print(json.dumps({f"credentials/{sys.argv[1]}/otpAuth": value}))' "$key" "$value" |
    stalwart-cli update Account "$id" --stdin >/dev/null
}

cmd_status() {
  local id key auth
  id="$(account_id)"
  key="$(password_key "$id")"
  auth="$(otp_auth "$id" "$key")"
  if [[ -n "$auth" ]]; then
    echo "TOTP enabled for $MAIL_USER@$MAIL_DOMAIN"
  else
    echo "TOTP disabled for $MAIL_USER@$MAIL_DOMAIN"
  fi
}

cmd_disable() {
  local id key
  id="$(account_id)"
  key="$(password_key "$id")"
  patch_otp_auth "$id" "$key" ""
  echo "TOTP disabled for $MAIL_USER@$MAIL_DOMAIN"
}

[[ $# -ge 1 ]] || usage
cmd="$1"; shift
case "$cmd" in
  disable|status) [[ $# -eq 0 ]] || usage ;;
  *) usage ;;
esac

case "$cmd" in
  disable) cmd_disable ;;
  status) cmd_status ;;
esac
