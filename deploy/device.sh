#!/usr/bin/env bash
# Issues and revokes one Stalwart app password per device for server.mail_user, plus the
# Corvid app's storage key file for that device in the account's own JMAP file storage.
#
# App password secrets are server-generated and per-account: Stalwart refuses to set them
# through Account ("Secondary credentials cannot be set directly"), and stalwart-cli's own
# verbs carry no accountId, only ever acting on the caller's own account. Add/list/revoke
# speak JMAP directly instead, with an explicit accountId, which the admin credential may set
# for any account (verified: a non-admin gets "you are not an owner of account" for the same
# call against someone else's id).
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
load_env "$ENV_FILE"
load_server

NAME_RE='^[a-z0-9-]{1,32}$'
DEVICES_FOLDER=corvid-devices

usage() {
  echo "usage: $(basename "$0") add [--local] NAME | list | revoke NAME" >&2
  exit 64
}

# Refuses a response answering for a different account than requested, so a wrong id can never
# act on another account silently.
jmap_call() {
  local expect="$1"
  curl_authed -fsS -H 'Content-Type: application/json' -d @- "$STALWART_URL/jmap" |
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
  local name="$1" id="$2" local_only="${3:-0}" allowed_ips restriction request secret
  [[ "$name" =~ $NAME_RE ]] || { echo "device name must match $NAME_RE" >&2; exit 64; }
  if app_passwords "$id" | python3 -c '
import json, sys
sys.exit(0 if any(json.loads(l)["description"] == sys.argv[1] for l in sys.stdin) else 1)' "$name"; then
    echo "a device named $name already exists" >&2
    exit 65
  fi

  if [[ "$local_only" == 1 ]]; then
    allowed_ips='{"127.0.0.1": true, "::1": true}'
    restriction="limited to loopback, for a service running inside the pod"
  else
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
  fi

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

# Confirms a Foo/set destroy actually destroyed the id, rather than trusting a bare 200. A
# refusal reported as success would be worse than no report at all.
require_destroyed() {
  local id="$1"
  python3 -c '
import json, sys
id_ = sys.argv[1]
result = json.load(sys.stdin)["methodResponses"][0][1]
if id_ in result.get("destroyed", []):
    sys.exit(0)
sys.exit(json.dumps(result.get("notDestroyed", {}).get(id_, result)))' "$id"
}

credential_id_for() {
  app_passwords "$1" | python3 -c '
import json, sys
name = sys.argv[1]
for line in sys.stdin:
    entry = json.loads(line)
    if entry["description"] == name:
        print(entry["credentialId"])
        break' "$2"
}

# The id of the top-level "corvid-devices" folder, or nothing if it does not exist. A filter
# of {"parentId": null} is refused as malformed, so root scoping reads parentId back instead.
devices_folder_id() {
  local id="$1"
  python3 -c '
import json, sys
print(json.dumps({
    "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:filenode"],
    "methodCalls": [["FileNode/query", {"accountId": sys.argv[1], "filter": {"name": sys.argv[2]}}, "q0"],
        ["FileNode/get", {"accountId": sys.argv[1],
        "#ids": {"resultOf": "q0", "name": "FileNode/query", "path": "/ids"},
        "properties": ["id", "parentId"]}, "g0"]],
}))' "$id" "$DEVICES_FOLDER" | jmap_call "$id" | python3 -c '
import json, sys
found = json.load(sys.stdin)["methodResponses"][1][1]["list"]
roots = [f["id"] for f in found if f["parentId"] is None]
if len(roots) > 1:
    sys.exit(f"{len(roots)} top-level folders named {sys.argv[1]!r}; refusing to guess which")
print(roots[0] if roots else "")' "$DEVICES_FOLDER"
}

# Looks up the file "$2.key" inside the top-level "corvid-devices" folder, printing
# "id<TAB>blobId" or nothing if either is missing.
find_key_file() {
  local id="$1" name="$2" folder_id
  folder_id="$(devices_folder_id "$id")"
  [[ -n "$folder_id" ]] || return 0

  python3 -c '
import json, sys
print(json.dumps({
    "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:filenode"],
    "methodCalls": [["FileNode/query", {"accountId": sys.argv[1],
        "filter": {"parentId": sys.argv[2], "name": sys.argv[3] + ".key"}}, "q0"],
        ["FileNode/get", {"accountId": sys.argv[1],
        "#ids": {"resultOf": "q0", "name": "FileNode/query", "path": "/ids"},
        "properties": ["id", "blobId"]}, "g0"]],
}))' "$id" "$folder_id" "$name" | jmap_call "$id" | python3 -c '
import json, sys
found = json.load(sys.stdin)["methodResponses"][1][1]["list"]
if found:
    file_id, blob_id = found[0]["id"], found[0]["blobId"]
    print(f"{file_id}\t{blob_id}")'
}

# Asks the sentinel to destroy the key file it approved for NAME and forget it, and prints 204,
# or 404 when it recorded none. The route is not served through Caddy, so it runs inside the pod,
# and it takes an HMAC of the request under the sentinel's webhook secret.
sentinel_revoke() {
  local url="${CORVID_SENTINEL_URL:-}" pid status signature in_pod=()
  signature="$(python3 -c '
import base64, hashlib, hmac, sys
secret = next(l.split("=", 1)[1].strip() for l in open(sys.argv[1]) if l.startswith("CORVID_SENTINEL_SECRET="))
print(base64.b64encode(hmac.new(secret.encode(), f"revoke {sys.argv[2]}".encode(), hashlib.sha256).digest()).decode())' \
    "${CORVID_SENTINEL_ENV:-$CORVID_DIR/sentinel-webhook.env}" "$1")"
  if [[ -z "$url" ]]; then
    url=http://127.0.0.1:11333
    pid="$(podman inspect -f '{{.State.Pid}}' corvid-sentinel 2>/dev/null || echo 0)"
    (( pid > 0 )) || { echo "corvid-sentinel is not running; check: systemctl status corvid-sentinel" >&2; exit 69; }
    in_pod=(nsenter "--net=/proc/$pid/ns/net")
  fi
  status="$("${in_pod[@]}" curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "X-Signature: $signature" \
    "$url/internal/devices/$1" || true)"
  [[ "$status" == 204 || "$status" == 404 ]] || { echo "the sentinel answered $status revoking $1's approved key" >&2; exit 1; }
  echo "$status"
}

# Not one atomic step, so a rerun after a partial failure must still make progress: only none of
# the credential, a key file and an approved key existing counts as "no such device".
cmd_revoke() {
  local name="$1" id="$2" credential_id found approved file_id blob_id status

  credential_id="$(credential_id_for "$id" "$name")"
  found="$(find_key_file "$id" "$name")"
  approved="$(sentinel_revoke "$name")"
  [[ -n "$credential_id" || -n "$found" || "$approved" == 204 ]] || { echo "no such device: $name" >&2; exit 65; }
  [[ "$approved" != 204 ]] || echo "  approved key revoked"

  if [[ -n "$credential_id" ]]; then
    python3 -c '
import json, sys
print(json.dumps({
    "using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"],
    "methodCalls": [["x:AppPassword/set", {"accountId": sys.argv[1], "destroy": [sys.argv[2]]}, "d0"]],
}))' "$id" "$credential_id" | jmap_call "$id" | require_destroyed "$credential_id"

    if app_passwords "$id" | python3 -c '
import json, sys
target = sys.argv[1]
sys.exit(0 if any(json.loads(l)["credentialId"] == target for l in sys.stdin) else 1)' "$credential_id"; then
      echo "credential $credential_id still present after revoke" >&2
      exit 1
    fi
    echo "  credential revoked"
  else
    echo "  no credential found for $name (already revoked)"
  fi

  if [[ -n "$found" ]]; then
    file_id="${found%%$'\t'*}"
    blob_id="${found#*$'\t'}"
    python3 -c '
import json, sys
print(json.dumps({
    "using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:filenode"],
    "methodCalls": [["FileNode/set", {"accountId": sys.argv[1], "destroy": [sys.argv[2]]}, "d0"]],
}))' "$id" "$file_id" | jmap_call "$id" | require_destroyed "$file_id"

    # curl exits nonzero on a connection failure even without -f, which would otherwise trip
    # set -e before the status below is ever inspected.
    status="$(curl_authed -s -o /dev/null -w '%{http_code}' \
      "$STALWART_URL/jmap/download/$id/$blob_id/$name.key" || true)"
    if [[ "$status" != "404" ]]; then
      echo "  storage key file revoked, but its blob did not confirm gone (status $status)" >&2
      exit 1
    fi
    echo "  storage key file revoked, blob no longer downloads"
  else
    echo "  no storage key file found for $name (nothing to remove)"
  fi

  cat <<EOF

If $name also reaches this server through a VPN, revoke its VPN access there too:
Corvid cannot, and a copy of that identity still reaches the mail ports.
EOF
}

[[ $# -ge 1 ]] || usage
cmd="$1"; shift
local_only=0
case "$cmd" in
  add)
    if [[ "${1:-}" == "--local" ]]; then
      local_only=1
      shift
    fi
    [[ $# -eq 1 ]] || usage
    ;;
  list) [[ $# -eq 0 ]] || usage ;;
  revoke) [[ $# -eq 1 ]] || usage ;;
  *) usage ;;
esac

id="$(account_id)"
case "$cmd" in
  add) cmd_add "$1" "$id" "$local_only" ;;
  list) cmd_list "$id" ;;
  revoke) cmd_revoke "$1" "$id" ;;
esac
