#!/usr/bin/env bash
# Issues per-device Stalwart app passwords for server.mail_user.
#
# App password secrets are server-generated and per-account: Stalwart refuses to set them
# through Account ("Secondary credentials cannot be set directly"), and stalwart-cli's own
# verbs carry no accountId, only ever acting on the caller's own account. Add/list speak JMAP
# directly instead, with an explicit accountId, which the admin credential may set for any
# account (verified: a non-admin gets "you are not an owner of account" for the same call
# against someone else's id).
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
set -a; . "$ENV_FILE"; set +a
. "$HERE/lib.sh"
load_server

NAME_RE='^[a-z0-9-]{1,32}$'

usage() {
  echo "usage: $(basename "$0") add NAME | list" >&2
  exit 64
}

account_id() {
  stalwart-cli query Account --json | python3 -c '
import json, os, sys
address = os.environ["MAIL_USER"] + "@" + os.environ["MAIL_DOMAIN"]
print(next(a["id"] for a in map(json.loads, filter(str.strip, sys.stdin)) if a.get("emailAddress") == address))'
}

# Refuses a response answering for a different account than requested, so a wrong id can never
# act on another account silently.
jmap_call() {
  local expect="$1"
  curl -fsS -u "$STALWART_USER:$STALWART_PASSWORD" -H 'Content-Type: application/json' -d @- "$STALWART_URL/jmap" |
    python3 -c '
import json, sys
expect, body = sys.argv[1], sys.stdin.read()
for _, args, _ in json.loads(body).get("methodResponses", []):
    got = args.get("accountId")
    if got not in (None, expect):
        sys.exit(f"jmap response accountId {got!r} does not match requested {expect!r}")
sys.stdout.write(body)' "$expect"
}

app_passwords() {
  stalwart-cli get Account "$1" --fields credentials --json | python3 -c '
import json, sys
for entry in json.load(sys.stdin)["credentials"].values():
    if entry.get("@type") == "AppPassword":
        print(json.dumps(entry))'
}

cmd_add() {
  local name="$1" id="$2" allowed_ips restriction request secret
  [[ "$name" =~ $NAME_RE ]] || { echo "device name must match $NAME_RE" >&2; exit 64; }
  if app_passwords "$id" | python3 -c '
import json, sys
sys.exit(0 if any(json.loads(l)["description"] == sys.argv[1] for l in sys.stdin) else 1)' "$name"; then
    echo "a device named $name already exists" >&2
    exit 65
  fi

  case "$CLIENT_SOURCES" in
    any)
      allowed_ips='{}'
      restriction="no source restriction (client_access: anywhere)"
      ;;
    '')
      SERVER_IP="${SERVER_IP:-$(server_ip)}"
      [[ -n "$SERVER_IP" ]] || {
        echo "could not find this server's public IP; refusing to issue an unrestricted credential" >&2
        exit 1
      }
      allowed_ips="$(python3 -c 'import json, sys; print(json.dumps({sys.argv[1]: True}))' "$SERVER_IP")"
      restriction="limited to this server's address ($SERVER_IP)"
      ;;
    *)
      # shellcheck disable=SC2086  # CLIENT_SOURCES is a space-separated list of bare words
      allowed_ips="$(python3 -c 'import json, sys; print(json.dumps({n: True for n in sys.argv[1:]}))' $CLIENT_SOURCES)"
      restriction="limited to $CLIENT_SOURCES"
      ;;
  esac

  request="$(python3 -c '
import json, sys
name, allowed_ips, account = sys.argv[1], json.loads(sys.argv[2]), sys.argv[3]
print(json.dumps({
    "using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"],
    "methodCalls": [["x:AppPassword/set", {"accountId": account,
        "create": {"n0": {"description": name, "allowedIps": allowed_ips}}}, "c0"]],
}))' "$name" "$allowed_ips" "$id")"

  secret="$(printf '%s' "$request" | jmap_call "$id" | python3 -c '
import json, sys
result = json.load(sys.stdin)["methodResponses"][0][1]
created = result.get("created", {}).get("n0")
if not created:
    sys.exit(json.dumps(result.get("notCreated", result)))
print(created["secret"])')"
  echo "$secret"
  echo "  device $name added, $restriction" >&2
}

cmd_list() {
  app_passwords "$1" | python3 -c '
import json, sys
rows = [json.loads(l) for l in sys.stdin]
if not rows:
    print("  no devices")
for entry in rows:
    ips = entry.get("allowedIps") or {}
    where = "any" if not ips else " ".join(sorted(ips))
    desc, created = entry["description"], entry["createdAt"]
    print(f"{desc}\t{created}\t{where}")'
}

[[ $# -ge 1 ]] || usage
cmd="$1"; shift
case "$cmd" in
  add) [[ $# -eq 1 ]] || usage ;;
  list) [[ $# -eq 0 ]] || usage ;;
  *) usage ;;
esac

id="$(account_id)"
case "$cmd" in
  add) cmd_add "$1" "$id" ;;
  list) cmd_list "$id" ;;
esac
