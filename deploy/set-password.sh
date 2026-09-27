#!/usr/bin/env bash
# Sets the mailbox password for server.mail_user, read from stdin so it never reaches a
# command line. Exits 65 with Stalwart's reason when it refuses the password as too weak.
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
set -a; . "$ENV_FILE"; set +a
. "$HERE/lib.sh"
load_server

id="$(account_id)"

# A bare "credentials" key replaces the whole map, wiping every enrolled app password, so the
# key to patch is found rather than assumed. The map is empty on a brand-new account (the plan
# never sets credentials), so no Password entry is expected there; "0" then creates one.
# "credentials/<key>/secret" touches only the secret, leaving that credential's own allowedIps
# and otpAuth (2FA) untouched.
key="$(password_key "$id")"
key="${key:-0}"

if ! out="$(python3 -c '
import json, sys
print(json.dumps({f"credentials/{sys.argv[1]}/secret": sys.stdin.read()}))' "$key" |
  stalwart-cli update Account "$id" --stdin 2>&1)"; then
  grep -q invalidProperties <<<"$out" || { echo "$out" >&2; exit 1; }
  grep '^  ' <<<"$out" | grep -v 'Properties:' >&2
  exit 65
fi
echo "  password set for $MAIL_USER@$MAIL_DOMAIN"
