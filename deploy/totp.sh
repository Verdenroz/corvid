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

usage() { echo "usage: $(basename "$0") enable [--force] | disable | status" >&2; exit 64; }

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

app_passwords_exist() {
  stalwart-cli get Account "$1" --fields credentials --json | python3 -c '
import json, sys
creds = json.load(sys.stdin)["credentials"]
sys.exit(0 if any(v.get("@type") == "AppPassword" for v in creds.values()) else 1)'
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

cmd_enable() {
  local force="$1" id key auth secret label uri code
  id="$(account_id)"
  key="$(password_key "$id")"
  auth="$(otp_auth "$id" "$key")"
  if [[ -n "$auth" ]]; then
    echo "TOTP is already enabled for $MAIL_USER@$MAIL_DOMAIN; run disable first" >&2
    exit 65
  fi
  if [[ "$force" != 1 ]] && ! app_passwords_exist "$id"; then
    echo "no app passwords exist for $MAIL_USER; enabling TOTP would lock out every mail app" >&2
    echo "add one first: deploy/device.sh add, or pass --force" >&2
    exit 65
  fi

  secret="$(python3 -c 'import base64, secrets; print(base64.b32encode(secrets.token_bytes(20)).decode())')"
  label="$(python3 -c 'import urllib.parse, sys; print(urllib.parse.quote(sys.argv[1]))' "$MAIL_USER@$MAIL_DOMAIN")"
  uri="otpauth://totp/Corvid:$label?secret=$secret&issuer=Corvid"

  echo "Scan this into your authenticator app, then enter the code it shows:"
  echo "$uri"
  if command -v qrencode >/dev/null; then
    qrencode -t ANSIUTF8 "$uri"
  fi

  read -r -p "6-digit code: " code
  if ! python3 "$HERE/totp.py" verify "$secret" "$code"; then
    echo "wrong code; nothing was changed" >&2
    exit 65
  fi

  patch_otp_auth "$id" "$key" "$uri"
  echo "TOTP enabled for $MAIL_USER@$MAIL_DOMAIN"
}

[[ $# -ge 1 ]] || usage
cmd="$1"; shift
force=0
case "$cmd" in
  enable)
    if [[ "${1:-}" == "--force" ]]; then force=1; shift; fi
    [[ $# -eq 0 ]] || usage
    ;;
  disable|status) [[ $# -eq 0 ]] || usage ;;
  *) usage ;;
esac

case "$cmd" in
  enable) cmd_enable "$force" ;;
  disable) cmd_disable ;;
  status) cmd_status ;;
esac
