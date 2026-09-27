#!/usr/bin/env bash
# Sets the mailbox password for server.mail_user, read from stdin so it never reaches a
# command line. Exits 65 with Stalwart's reason when it refuses the password as too weak.
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
set -a; . "$ENV_FILE"; set +a
. "$HERE/lib.sh"
load_server

# query --json prints one object per line.
id="$(stalwart-cli query Account --json | python3 -c '
import json, os, sys
address = os.environ["MAIL_USER"] + "@" + os.environ["MAIL_DOMAIN"]
print(next(a["id"] for a in map(json.loads, filter(str.strip, sys.stdin)) if a.get("emailAddress") == address))')"
if ! out="$(python3 -c '
import json, sys
print(json.dumps({"credentials": {"0": {"@type": "Password", "secret": sys.stdin.read()}}}))' |
  stalwart-cli update Account "$id" --stdin 2>&1)"; then
  grep -q invalidProperties <<<"$out" || { echo "$out" >&2; exit 1; }
  grep '^  ' <<<"$out" | grep -v 'Properties:' >&2
  exit 65
fi
echo "  password set for $MAIL_USER@$MAIL_DOMAIN"
