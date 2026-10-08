#!/usr/bin/env bash
# Configures Pocket-ID through its REST API, authenticated by STATIC_API_KEY, so nobody claims its
# setup page. `provision` creates the admin and mail users, the mail group and the corvid client
# restricted to it, and the admins group (the admin user alone) with the corvid-admin client that
# demands a fresh passkey on every authorization. It changes nothing that already exists beyond
# restricting a client registered by hand. It prints one "username link" line on
# stdout for each user it created. `link USERNAME` prints a fresh one-time enrollment link.
# deploy.sh runs provision.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
load_server

IDP_ENV="${CORVID_IDP_ENV:-$CORVID_DIR/idp.env}"
API_KEY="$(sed -n 's/^STATIC_API_KEY=//p' "$IDP_ENV" 2>/dev/null || true)"
[[ -n "$API_KEY" ]] || { echo "STATIC_API_KEY is not set in $IDP_ENV; install.sh generates it" >&2; exit 78; }

# The API listens only on the pod's loopback and Caddy may not have a certificate yet, so curl
# runs in the container's network namespace. The first call fails fast, outside any substitution.
in_pod corvid-idp true

CLIENT_ID=corvid
MAIL_GROUP=mail
ADMIN_CLIENT_ID=corvid-admin
ADMIN_GROUP=admins
# A refresh grant mints a token with a new issue time and no passkey, so this client's refresh
# token lives one minute, the shortest Pocket-ID accepts.
ADMIN_CLIENT='{"requiresReauthentication": true, "accessTokenDurationMinutes": 5, "refreshTokenDurationMinutes": 1}'

api() {
  local method="$1" path="$2"
  in_pod corvid-idp curl -fsS -X "$method" -H 'Content-Type: application/json' \
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

# Sets GROUP_ID.
ensure_group() {
  GROUP_ID="$(api GET "user-groups?search=$1" | python3 -c '
import json, sys
print(next((g["id"] for g in json.load(sys.stdin)["data"] if g["name"] == sys.argv[1]), ""))' "$1")"
  if [[ -z "$GROUP_ID" ]]; then
    GROUP_ID="$(api POST user-groups "{\"name\":\"$1\",\"friendlyName\":\"$1\"}" | field id)"
    echo "  group $1 created" >&2
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

# Created restricted, so the client admits nobody until its group is attached. A client registered
# by hand is restricted in place, keeping its other settings. $3 is a JSON object of the settings
# the client must hold, which an existing client is corrected to.
ensure_client() {
  local client_id="$1" group="$2" pinned="$3" client update ids
  if client="$(api GET "oidc/clients/$client_id" 2>/dev/null)"; then
    echo "  client $client_id exists" >&2
    update="$(python3 -c '
import json, sys
keep = ("name", "callbackURLs", "logoutCallbackURLs", "isPublic", "pkceEnabled", "skipConsent",
        "accessTokenDurationMinutes", "refreshTokenDurationMinutes")
client = json.load(sys.stdin)
want = {**json.loads(sys.argv[1]), "isGroupRestricted": True}
if any(client[k] != v for k, v in want.items()):
    print(json.dumps({**{k: client[k] for k in keep}, **want}))' "$pinned" <<<"$client")"
    if [[ -n "$update" ]]; then
      api PUT "oidc/clients/$client_id" "$update" >/dev/null
      echo "  client $client_id restricted to group $group" >&2
    fi
  else
    api POST oidc/clients "$(python3 -c '
import json, sys
print(json.dumps({"id": sys.argv[1], "name": sys.argv[1], "isPublic": True, "pkceEnabled": True,
                  "callbackURLs": ["http://127.0.0.1/callback", "com.verdenroz.corvid:/oauth2redirect"],
                  "isGroupRestricted": True, **json.loads(sys.argv[2])}))' "$client_id" "$pinned")" >/dev/null
    echo "  client $client_id created" >&2
    client="$(api GET "oidc/clients/$client_id")"
  fi
  ids="$(python3 -c '
import json, sys
ids = [g["id"] for g in json.load(sys.stdin)["allowedUserGroups"]]
print(json.dumps(ids if sys.argv[1] in ids else ids + [sys.argv[1]]))' "$GROUP_ID" <<<"$client")"
  api PUT "oidc/clients/$client_id/allowed-user-groups" "{\"userGroupIds\":$ids}" >/dev/null
}

cmd_provision() {
  CREATED=()
  wait_for_api
  ensure_user "$ADMIN_USER" true "$MAIL_USER" Admin
  local admin_id="$USER_ID"
  ensure_user "$MAIL_USER" false "$MAIL_USER" Mail
  local mail_id="$USER_ID"
  ensure_group "$MAIL_GROUP"
  ensure_member "$mail_id"
  ensure_client "$CLIENT_ID" "$MAIL_GROUP" '{"requiresReauthentication": false}'
  ensure_group "$ADMIN_GROUP"
  ensure_member "$admin_id"
  ensure_client "$ADMIN_CLIENT_ID" "$ADMIN_GROUP" "$ADMIN_CLIENT"
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
