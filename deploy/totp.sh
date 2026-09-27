#!/usr/bin/env bash
# Sets or clears TOTP on the account's Password credential for server.mail_user.
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
set -a; . "$ENV_FILE"; set +a
. "$HERE/lib.sh"
load_server

usage() { echo "usage: $(basename "$0") enable [--force] | disable | status" >&2; exit 64; }

# Unlike set-password.sh, totp.sh never defaults a missing password_key to "0": a pointer patch
# into a missing key creates a credential holding nothing but otpAuth.
require_password_key() {
  local id="$1" key
  key="$(password_key "$id")"
  if [[ -z "$key" ]]; then
    echo "no password credential exists for $MAIL_USER@$MAIL_DOMAIN; set one first with set-password.sh" >&2
    exit 65
  fi
  echo "$key"
}

otp_auth() {
  stalwart-cli get Account "$1" --fields credentials --json | python3 -c '
import json, sys
entry = json.load(sys.stdin)["credentials"].get(sys.argv[1]) or {}
print(entry.get("otpAuth") or "")' "$2"
}

# App passwords scoped to loopback only (device.sh add --local) serve in-pod services, not mail
# apps, so they do not count toward the lockout guard.
app_password_guards_a_mail_app() {
  stalwart-cli get Account "$1" --fields credentials --json | python3 -c '
import json, sys
creds = json.load(sys.stdin)["credentials"]
loopback = {"127.0.0.1", "::1"}
def guards(entry):
    return entry.get("@type") == "AppPassword" and set(entry.get("allowedIps") or {}) != loopback
sys.exit(0 if any(guards(v) for v in creds.values()) else 1)'
}

# VALUE (the otpauth:// URI, or empty to clear) is read from stdin so the secret it carries
# never reaches this process's argv.
patch_otp_auth() {
  local id="$1" key="$2"
  python3 -c '
import json, sys
value = sys.stdin.readline().rstrip("\n") or None
print(json.dumps({f"credentials/{sys.argv[1]}/otpAuth": value}))' "$key" |
    stalwart-cli update Account "$id" --stdin >/dev/null
}

cmd_status() {
  local id key auth
  id="$(account_id)"
  key="$(require_password_key "$id")"
  auth="$(otp_auth "$id" "$key")"
  if [[ -n "$auth" ]]; then
    echo "TOTP enabled for $MAIL_USER@$MAIL_DOMAIN"
  else
    echo "TOTP disabled for $MAIL_USER@$MAIL_DOMAIN"
  fi
}

cmd_disable() {
  local id key auth
  id="$(account_id)"
  key="$(require_password_key "$id")"
  auth="$(otp_auth "$id" "$key")"
  patch_otp_auth "$id" "$key" <<<""
  if [[ -n "$auth" ]]; then
    echo "TOTP disabled for $MAIL_USER@$MAIL_DOMAIN"
  else
    echo "TOTP was not enabled for $MAIL_USER@$MAIL_DOMAIN"
  fi
}

cmd_enable() {
  local force="$1" id key auth secret label uri code
  id="$(account_id)"
  key="$(require_password_key "$id")"
  auth="$(otp_auth "$id" "$key")"
  if [[ -n "$auth" ]]; then
    echo "TOTP is already enabled for $MAIL_USER@$MAIL_DOMAIN; run disable first" >&2
    exit 65
  fi
  if [[ "$force" != 1 ]] && ! app_password_guards_a_mail_app "$id"; then
    echo "no app password for $MAIL_USER guards a mail app (loopback-only ones do not count)" >&2
    echo "enabling TOTP would lock every mail app out; add one first: deploy/device.sh add, or pass --force" >&2
    exit 65
  fi

  secret="$(python3 -c 'import base64, secrets; print(base64.b32encode(secrets.token_bytes(20)).decode())')"
  label="$(python3 -c 'import urllib.parse, sys; print(urllib.parse.quote(sys.argv[1]))' "$MAIL_USER@$MAIL_DOMAIN")"
  uri="otpauth://totp/Corvid:$label?secret=$secret&issuer=Corvid"

  echo "Scan this into your authenticator app, then enter the code it shows:"
  echo "$uri"
  if command -v qrencode >/dev/null; then
    printf '%s' "$uri" | qrencode -t ANSIUTF8
  fi

  read -r -p "6-digit code: " code
  if ! python3 "$HERE/totp.py" verify "$code" <<<"$secret"; then
    echo "wrong code; nothing was changed" >&2
    exit 65
  fi

  patch_otp_auth "$id" "$key" <<<"$uri"
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
