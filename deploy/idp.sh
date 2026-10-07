#!/usr/bin/env bash
# Configures Pocket-ID through its REST API, authenticated by STATIC_API_KEY, so nobody claims its
# setup page. `provision` creates the admin and mail users, the mail group and the corvid client
# restricted to it, changing nothing that already exists. It prints one "username link" line on
# stdout for each user it created. `link USERNAME` prints a fresh one-time enrollment link.
# deploy.sh runs provision.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
load_server

IDP_ENV="${CORVID_IDP_ENV:-$CORVID_DIR/idp.env}"
API_KEY="$(sed -n 's/^STATIC_API_KEY=//p' "$IDP_ENV" 2>/dev/null || true)"
[[ -n "$API_KEY" ]] || { echo "STATIC_API_KEY is not set in $IDP_ENV; install.sh generates it" >&2; exit 78; }

# The API is only on the pod's loopback: the host cannot reach it, and Caddy answers only once it
# holds a certificate. Running the host's curl inside the container's network namespace needs
# neither, and skips Caddy's block on the setup routes.
if [[ -n "${CORVID_IDP_URL:-}" ]]; then
  IDP_URL="$CORVID_IDP_URL"
  in_idp=()
else
  IDP_URL=http://127.0.0.1:1411
  in_idp=(nsenter "--net=/proc/$(podman inspect -f '{{.State.Pid}}' corvid-idp)/ns/net")
fi

CLIENT_ID=corvid
GROUP=mail
ADMIN_USER="$MAIL_USER-admin"

api() {
  local method="$1" path="$2"
  "${in_idp[@]}" curl -fsS -X "$method" -H 'Content-Type: application/json' \
    --config <(printf 'header = "X-API-Key: %s"\n' "$API_KEY") \
    ${3:+-d "$3"} "$IDP_URL/api/$path"
}

field() { python3 -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

user_id() {
  api GET "users?search=$1" | python3 -c '
import json, sys
print(next((u["id"] for u in json.load(sys.stdin)["data"] if u["username"] == sys.argv[1]), ""))' "$1"
}

wait_for_api() {
  for _ in $(seq 1 60); do
    api GET users >/dev/null 2>&1 && return
    sleep 2
  done
  echo "Pocket-ID did not answer; check: journalctl -u corvid-idp" >&2
  exit 69
}

# Sets USER_ID, and adds USERNAME to CREATED when it had to create the user.
ensure_user() {
  local username="$1" admin="$2" first="$3" last="$4"
  USER_ID="$(user_id "$username")"
  if [[ -n "$USER_ID" ]]; then
    echo "  user $username exists" >&2
    return
  fi
  USER_ID="$(api POST users "$(python3 -c '
import json, sys
username, email, first, last, admin = sys.argv[1:]
print(json.dumps({"username": username, "email": email, "firstName": first, "lastName": last,
                  "isAdmin": admin == "true"}))' "$username" "$username@$MAIL_DOMAIN" "$first" "$last" "$admin")" | field id)"
  CREATED+=("$username")
  echo "  user $username created" >&2
}

ensure_group() {
  GROUP_ID="$(api GET "user-groups?search=$GROUP" | python3 -c '
import json, sys
print(next((g["id"] for g in json.load(sys.stdin)["data"] if g["name"] == sys.argv[1]), ""))' "$GROUP")"
  if [[ -z "$GROUP_ID" ]]; then
    GROUP_ID="$(api POST user-groups "{\"name\":\"$GROUP\",\"friendlyName\":\"$GROUP\"}" | field id)"
    echo "  group $GROUP created" >&2
  fi
}

ensure_member() {
  local ids
  ids="$(api GET "user-groups/$GROUP_ID" | python3 -c '
import json, sys
ids = [u["id"] for u in json.load(sys.stdin)["users"]]
print(json.dumps(ids if sys.argv[1] in ids else ids + [sys.argv[1]]))' "$1")"
  api PUT "user-groups/$GROUP_ID/users" "{\"userIds\":$ids}" >/dev/null
}

# Created restricted, so the client admits nobody until its group is attached.
ensure_client() {
  if api GET "oidc/clients/$CLIENT_ID" >/dev/null 2>&1; then
    echo "  client $CLIENT_ID exists" >&2
  else
    api POST oidc/clients '{"id":"corvid","name":"corvid","isPublic":true,"pkceEnabled":true,
      "callbackURLs":["http://127.0.0.1/callback","com.verdenroz.corvid:/oauth2redirect"],
      "isGroupRestricted":true}' >/dev/null
    echo "  client $CLIENT_ID created" >&2
  fi
  local ids
  ids="$(api GET "oidc/clients/$CLIENT_ID" | python3 -c '
import json, sys
ids = [g["id"] for g in json.load(sys.stdin)["allowedUserGroups"]]
print(json.dumps(ids if sys.argv[1] in ids else ids + [sys.argv[1]]))' "$GROUP_ID")"
  api PUT "oidc/clients/$CLIENT_ID/allowed-user-groups" "{\"userGroupIds\":$ids}" >/dev/null
}

cmd_provision() {
  CREATED=()
  wait_for_api
  ensure_user "$ADMIN_USER" true "$MAIL_USER" Admin
  ensure_user "$MAIL_USER" false "$MAIL_USER" Mail
  local mail_id="$USER_ID"
  ensure_group
  ensure_member "$mail_id"
  ensure_client
  local username
  for username in "${CREATED[@]}"; do
    echo "$username $(cmd_link "$username")"
  done
}

cmd_link() {
  local id token
  id="$(user_id "$1")"
  [[ -n "$id" ]] || { echo "no Pocket-ID user named $1" >&2; exit 1; }
  token="$(api POST "users/$id/one-time-access-token" '{"ttl":"24h"}' | field token)"
  echo "https://$IDP_HOSTNAME:$WEB_PORT/lc/$token"
}

case "${1:-}" in
  provision) cmd_provision ;;
  link) [[ -n "${2:-}" ]] || { echo "usage: $(basename "$0") link USERNAME" >&2; exit 64; }; cmd_link "$2" ;;
  *) echo "usage: $(basename "$0") provision | link USERNAME" >&2; exit 64 ;;
esac
