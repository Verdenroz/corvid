#!/usr/bin/env bash
# Configures Pocket-ID through its REST API, authenticated by STATIC_API_KEY, so nobody claims its
# setup page. `provision` creates the admin and mail users, the mail group and the corvid client
# restricted to it, and the admins group (the admin user alone) with the corvid-admin client that
# demands a fresh passkey on every authorization. It changes nothing that already exists beyond
# restricting a client registered by hand. It prints one "username link" line on
# stdout for each user it created. `link USERNAME` prints a fresh one-time enrollment link, and
# `client CLIENT_ID JSON` sets the given settings on a client and keeps the rest. deploy.sh runs
# provision.
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
CALLBACK_URLS='["http://127.0.0.1/callback", "com.verdenroz.corvid:/oauth2redirect"]'
# A refresh grant mints a token with a new issue time and no passkey, so this client's refresh
# token lives one minute, the shortest Pocket-ID accepts.
ADMIN_CLIENT='{"requiresReauthentication": true, "accessTokenDurationMinutes": 5, "refreshTokenDurationMinutes": 1}'

api() {
  local method="$1" path="$2"
  in_pod corvid-idp curl -fsS -X "$method" -H 'Content-Type: application/json' \
    --config <(printf 'header = "X-API-Key: %s"\n' "$API_KEY") \
    ${3:+-d "$3"} "$IDP_URL/api/$path"
}

# Prints the Python expression $1 over the JSON answer on stdin, bound to d; $2... arrive as a.
pick() { python3 -c 'import json, sys; d = json.load(sys.stdin); a = sys.argv[2:]; print(eval(sys.argv[1]))' "$@"; }

# Prints the ids of the list under key $1 of the JSON on stdin, as a JSON array holding id $2.
with_id() {
  python3 -c '
import json, sys
ids = [row["id"] for row in json.load(sys.stdin)[sys.argv[1]]]
print(json.dumps(ids if sys.argv[2] in ids else ids + [sys.argv[2]]))' "$1" "$2"
}

user_id() {
  api GET "users?search=$1" | pick 'next((u["id"] for u in d["data"] if u["username"] == a[0]), "")' "$1"
}

wait_for_api() {
  for _ in $(seq 1 60); do
    in_pod corvid-idp curl -fsS -o /dev/null "$IDP_URL/.well-known/openid-configuration" >/dev/null 2>&1 && return
    sleep 2
  done
  echo "Pocket-ID did not answer; check: journalctl -u corvid-idp" >&2
  exit 69
}

# Sets USER_ID, and adds "USERNAME ID" to CREATED when it had to create the user.
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
                  "isAdmin": admin == "true"}))' "$username" "$username@$MAIL_DOMAIN" "$first" "$last" "$admin")" | pick 'd["id"]')"
  CREATED+=("$username $USER_ID")
  echo "  user $username created" >&2
}

# Prints the group's id.
ensure_group() {
  local id
  id="$(api GET "user-groups?search=$1" | pick 'next((g["id"] for g in d["data"] if g["name"] == a[0]), "")' "$1")"
  if [[ -z "$id" ]]; then
    id="$(api POST user-groups "{\"name\":\"$1\",\"friendlyName\":\"$1\"}" | pick 'd["id"]')"
    echo "  group $1 created" >&2
  fi
  echo "$id"
}

ensure_member() {
  local ids
  ids="$(api GET "user-groups/$1" | with_id users "$2")"
  api PUT "user-groups/$1/users" "{\"userIds\":$ids}" >/dev/null
}

# The client $1 as the JSON for a PUT: its current settings $2, changed by the JSON object $3.
client_update() {
  python3 -c '
import json, sys
keep = ("name", "callbackURLs", "logoutCallbackURLs", "isPublic", "pkceEnabled", "skipConsent", "isGroupRestricted",
        "accessTokenDurationMinutes", "refreshTokenDurationMinutes")
client = json.loads(sys.argv[1])
print(json.dumps({**{k: client[k] for k in keep}, **json.loads(sys.argv[2])}))' "$1" "$2"
}

# Created restricted, so the client admits nobody until its group is attached. A client registered
# by hand is restricted in place, keeping its other settings. $4 is a JSON object of the settings
# the client must hold, which an existing client is corrected to.
ensure_client() {
  local client_id="$1" group="$2" group_id="$3" pinned="$4" client want ids
  want="$(python3 -c '
import json, sys
print(json.dumps({**json.loads(sys.argv[1]), "isGroupRestricted": True, "logoutCallbackURLs": json.loads(sys.argv[2])}))' "$pinned" "$CALLBACK_URLS")"
  if client="$(api GET "oidc/clients/$client_id" 2>/dev/null)"; then
    echo "  client $client_id exists" >&2
    if [[ "$(pick 'all(d[k] == v for k, v in json.loads(a[0]).items())' "$want" <<<"$client")" == False ]]; then
      api PUT "oidc/clients/$client_id" "$(client_update "$client" "$want")" >/dev/null
      echo "  client $client_id restricted to group $group" >&2
    fi
  else
    client="$(api POST oidc/clients "$(python3 -c '
import json, sys
print(json.dumps({"id": sys.argv[1], "name": sys.argv[1], "isPublic": True, "pkceEnabled": True,
                  "callbackURLs": json.loads(sys.argv[3]), **json.loads(sys.argv[2])}))' "$client_id" "$want" "$CALLBACK_URLS")")"
    echo "  client $client_id created" >&2
  fi
  ids="$(with_id allowedUserGroups "$group_id" <<<"$client")"
  api PUT "oidc/clients/$client_id/allowed-user-groups" "{\"userGroupIds\":$ids}" >/dev/null
}

enrollment_link() {
  local token
  token="$(api POST "users/$1/one-time-access-token" '{"ttl":"24h"}' | pick 'd["token"]')"
  echo "https://$IDP_HOSTNAME:$WEB_PORT/lc/$token"
}

cmd_provision() {
  CREATED=()
  wait_for_api
  ensure_user "$ADMIN_USER" true "$MAIL_USER" Admin
  local admin_id="$USER_ID" mail_group admin_group entry
  ensure_user "$MAIL_USER" false "$MAIL_USER" Mail
  local mail_id="$USER_ID"
  mail_group="$(ensure_group "$MAIL_GROUP")"
  ensure_member "$mail_group" "$mail_id"
  ensure_client "$CLIENT_ID" "$MAIL_GROUP" "$mail_group" '{"requiresReauthentication": false}'
  admin_group="$(ensure_group "$ADMIN_GROUP")"
  ensure_member "$admin_group" "$admin_id"
  ensure_client "$ADMIN_CLIENT_ID" "$ADMIN_GROUP" "$admin_group" "$ADMIN_CLIENT"
  for entry in "${CREATED[@]}"; do
    echo "${entry% *} $(enrollment_link "${entry#* }")"
  done
}

cmd_link() {
  local id
  id="$(user_id "$1")"
  [[ -n "$id" ]] || { echo "no Pocket-ID user named $1" >&2; exit 1; }
  enrollment_link "$id"
}

cmd_client() {
  api PUT "oidc/clients/$1" "$(client_update "$(api GET "oidc/clients/$1")" "$2")" >/dev/null
}

case "${1:-}" in
  provision) cmd_provision ;;
  link) [[ -n "${2:-}" ]] || { echo "usage: $(basename "$0") link USERNAME" >&2; exit 64; }; cmd_link "$2" ;;
  client) [[ $# -eq 3 ]] || { echo "usage: $(basename "$0") client CLIENT_ID JSON" >&2; exit 64; }; cmd_client "$2" "$3" ;;
  *) echo "usage: $(basename "$0") provision | link USERNAME | client CLIENT_ID JSON" >&2; exit 64 ;;
esac
