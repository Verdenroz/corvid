# shellcheck shell=bash
# Sourced by the deploy scripts. Reads config.yaml through the image's corvid-config, so the
# scripts and the services share one parser and one set of checks.

CORVID_DIR="${CORVID_DIR:-/opt/corvid}"
# Written by install.sh; a hand-built image loaded as localhost/corvid:latest needs no file.
CORVID_IMAGE="$(cat "$CORVID_DIR/image.ref" 2>/dev/null || echo localhost/corvid:latest)"

# One lookup shared by apply-plan.sh's ban exemption and device.sh's host restriction, so the
# two can never disagree about the server's own address.
server_ip() {
  curl -4 -s --max-time 10 https://1.1.1.1/cdn-cgi/trace | sed -n 's/^ip=//p'
}

# Runs a command in the network namespace of the pod container NAME, whose services listen on the
# pod's loopback and publish nothing. CORVID_HOST_NET runs it as is, for a rig whose services sit
# on the host's network.
in_pod() {
  local name="$1" pid
  shift
  if [[ -z "${CORVID_HOST_NET:-}" ]]; then
    pid="$(podman inspect -f '{{.State.Pid}}' "$name" 2>/dev/null || echo 0)"
    (( pid > 0 )) || { echo "$name is not running; check: systemctl status $name" >&2; exit 69; }
    set -- nsenter "--net=/proc/$pid/ns/net" "$@"
  fi
  "$@"
}

# stalwart-cli refuses a token alongside a user, so a token replaces the password pair in the
# environment. load_env calls it after every source of corvid.env.
prefer_token() {
  [[ -z "${STALWART_TOKEN:-}" ]] || unset STALWART_USER STALWART_PASSWORD
}

# Sources FILE into the environment, then lets a token in it win over a password pair.
load_env() {
  set -a; . "$1"; set +a
  prefer_token
}

# True once corvid.env holds an admin credential. load_env drops the password pair when a
# token exists, so the password alone cannot say so.
server_set_up() {
  [[ -n "${STALWART_PASSWORD:-}" || -n "${STALWART_TOKEN:-}" ]]
}

# curl -u puts the password in argv, readable in /proc by any local user for the call's
# lifetime. --config on a process substitution keeps it off the command line. Its quoted value
# needs its own backslashes and quotes escaped, or a password carrying either breaks the parse.
curl_authed() {
  if [[ -n "${STALWART_TOKEN:-}" ]]; then
    curl --config <(printf 'header = "Authorization: Bearer %s"\n' "$STALWART_TOKEN") "$@"
    return
  fi
  local user="$STALWART_USER" pass="$STALWART_PASSWORD"
  user="${user//\\/\\\\}"; user="${user//\"/\\\"}"
  pass="${pass//\\/\\\\}"; pass="${pass//\"/\\\"}"
  curl --config <(printf 'user = "%s:%s"\n' "$user" "$pass") "$@"
}

# Reads a JMAP request from stdin and prints the response. With EXPECT, refuses a response
# answering for a different account, so a wrong id can never act on another account silently.
jmap_call() {
  local expect="${1:-}"
  curl_authed -fsS -H 'Content-Type: application/json' -d @- "$STALWART_URL/jmap" |
    python3 -c '
import json, sys
expect, body = sys.argv[1], sys.stdin.read()
for _, args, _ in json.loads(body).get("methodResponses", []):
    got = args.get("accountId")
    if expect and got not in (None, expect):
        sys.exit(f"jmap response accountId {got!r} does not match requested {expect!r}")
sys.stdout.write(body)' "$expect"
}

jmap_admin() {
  printf '{"using": ["urn:ietf:params:jmap:core", "urn:stalwart:jmap"], "methodCalls": %s}' "$1" | jmap_call ""
}

# Prints the secret of the object a Foo/set response on stdin created as n0, or fails with why not.
created_secret() {
  python3 -c '
import json, sys
result = json.load(sys.stdin)["methodResponses"][0][1]
created = result.get("created", {}).get("n0")
if not created:
    sys.exit(json.dumps(result.get("notCreated", result)))
print(created["secret"])'
}

# Creates an admin API key and records it in ENV_FILE as STALWART_TOKEN, or as $3 under the
# description $2. A Bearer key is tried before any directory, so it keeps working once an external
# directory refuses the admin password. Keys an earlier run left behind when its token was lost
# are destroyed first, to spare the quota.
create_admin_api_key() {
  local env_file="$1" description="${2:-corvid-deploy}" variable="${3:-STALWART_TOKEN}" stale token
  stale="$(jmap_admin '[["x:ApiKey/get", {"ids": null}, "c0"]]' | python3 -c '
import json, sys
rows = json.load(sys.stdin)["methodResponses"][0][1]["list"]
print(json.dumps([r["id"] for r in rows if r.get("description") == sys.argv[1]]))' "$description")" || return 1
  token="$(jmap_admin "[[\"x:ApiKey/set\", {\"destroy\": $stale, \"create\": {\"n0\": {\"description\": \"$description\"}}}, \"c0\"]]" |
    created_secret)" || return 1
  atomic_write "$env_file" "$(grep -v "^$variable=" "$env_file"; printf '%s=%s' "$variable" "$token")" 600
}

account_id() {
  stalwart-cli query Account --json | python3 -c '
import json, os, sys
address = os.environ["MAIL_USER"] + "@" + os.environ["MAIL_DOMAIN"]
print(next(a["id"] for a in map(json.loads, filter(str.strip, sys.stdin)) if a.get("emailAddress") == address))'
}

# The key of ID's Password credential, found by @type, or empty if it has none. Callers decide
# what empty means: set-password.sh defaults to "0" to create one, totp.sh refuses rather than
# let a pointer patch create a credential holding only otpAuth.
password_key() {
  stalwart-cli get Account "$1" --fields credentials --json | python3 -c '
import json, sys
creds = json.load(sys.stdin)["credentials"]
keys = [k for k, v in creds.items() if v.get("@type") == "Password"]
if len(keys) > 1:
    print(f"expected at most one Password credential, found {len(keys)}", file=sys.stderr)
    sys.exit(1)
print(keys[0] if keys else "")'
}

require_image() {
  podman image exists "$CORVID_IMAGE" && return
  echo "image $CORVID_IMAGE is not on this host; it carries corvid-config and three of the services" >&2
  echo "install.sh pulls it, or pull it by hand: podman pull $CORVID_IMAGE" >&2
  exit 69
}

# Replaces the served app release with the one the image carries. The Caddy container mounts
# apps/ rather than apps/current, so the rename is visible to it. An image without a release
# leaves current empty, which serves 404.
install_apps() {
  local apps="$CORVID_DIR/apps" ctr
  rm -rf "$apps/.new" "$apps/.old"
  mkdir "$apps/.new"
  ctr="$(podman create "$CORVID_IMAGE")"
  # An image built before releases were baked in has no such directory; it serves nothing.
  podman cp "$ctr:/usr/share/corvid/app/." "$apps/.new" 2>/dev/null || echo "  $CORVID_IMAGE carries no app release"
  podman rm "$ctr" >/dev/null
  [[ -e "$apps/.new/manifest.json" ]] || find "$apps/.new" -mindepth 1 -delete
  chmod -R a+rX "$apps/.new"
  [[ ! -e "$apps/current" ]] || mv "$apps/current" "$apps/.old"
  mv "$apps/.new" "$apps/current"
  rm -rf "$apps/.old"
}

# Writes CONTENT to DEST through a temp file in CORVID_DIR, outside update/, which the pod's uid
# can write, so a planted symlink cannot redirect root's write. Rename does not follow one either.
atomic_write() {
  local dest="$1" tmp
  tmp="$(mktemp "$CORVID_DIR/.write.XXXXXX")"
  printf '%s\n' "$2" >"$tmp"
  chmod "${3:-644}" "$tmp"
  mv "$tmp" "$dest"
}

# Prints "<id> <type> <selector> <public key body>" for each active DKIM key of domain NAME.
active_dkim_keys() {
  local domain_id
  domain_id="$($STALWART_CLI query Domain --json | NAME="$1" python3 -c '
import json, os, sys
for line in sys.stdin:
    d = json.loads(line)
    if d.get("name") == os.environ["NAME"]:
        print(d["id"])')"
  $STALWART_CLI query DkimSignature --json | DOMAIN_ID="$domain_id" python3 -c '
import json, os, sys
for line in sys.stdin:
    d = json.loads(line)
    if d["domainId"] == os.environ["DOMAIN_ID"] and d["stage"] == "active":
        key = "".join(l for l in d["publicKey"].splitlines() if not l.startswith("-----"))
        print(d["id"], d["@type"], d["selector"], key)'
}

unit_masked() { [[ "$(systemctl is-enabled "$1" 2>/dev/null)" == masked ]]; }

# Sets and exports MAIL_DOMAIN, MAIL_HOSTNAME, MAIL_USER, ACME_CONTACT, WEB_PORT, HOST_ZONE,
# IDP_HOSTNAME, IDP_URL, SENTINEL_URL, ADMIN_USER, PUBLISH_MTA_STS, PUBLISH_TLSA, MTA_STS_MODE,
# CLIENT_SOURCES and UPDATE_AUTO from config.yaml. Tests set CORVID_CONFIG_CMD to run it without
# the container.
load_server() {
  local out key value
  if [[ -n "${CORVID_CONFIG_CMD:-}" ]]; then
    out="$($CORVID_CONFIG_CMD shell)" || exit 78
  else
    out="$(podman run --rm --network=none \
      -v "$CORVID_DIR/config.yaml:/etc/corvid/config.yaml:ro" \
      "$CORVID_IMAGE" corvid-config shell)" || exit 78
  fi
  while IFS='=' read -r key value; do
    case "$key" in
      MAIL_DOMAIN|MAIL_HOSTNAME|MAIL_USER|ACME_CONTACT|WEB_PORT|HOST_ZONE|IDP_HOSTNAME|IDP_URL|SENTINEL_URL|ADMIN_USER|PUBLISH_MTA_STS|PUBLISH_TLSA|MTA_STS_MODE|CLIENT_SOURCES|UPDATE_AUTO)
        printf -v "$key" '%s' "$value"
        export "${key?}"
        ;;
    esac
  done <<<"$out"
}

# Writes the units with the image and web port filled in, and server.env for the containers
# that need the hostname. Generated values, so no secrets and mode 644.
render_units() {
  local quadlet_dir="$1" here="$2" unit
  install -d -m 755 "$quadlet_dir"
  WEB_PORT="$WEB_PORT" envsubst '${WEB_PORT}' <"$here/corvid.pod" >"$quadlet_dir/corvid.pod"
  for unit in "$here"/*.container; do
    CORVID_IMAGE="$CORVID_IMAGE" envsubst '${CORVID_IMAGE}' <"$unit" >"$quadlet_dir/$(basename "$unit")"
  done
  cat >"$CORVID_DIR/server.env" <<EOF
STALWART_HOSTNAME=$MAIL_HOSTNAME
CORVID_WEB_HOSTNAME=$MAIL_HOSTNAME
CORVID_WEB_PORT=$WEB_PORT
CORVID_MAIL_DOMAIN=$MAIL_DOMAIN
CORVID_IDP_HOSTNAME=$IDP_HOSTNAME
CORVID_MTA_STS_SITE=$([[ "$PUBLISH_MTA_STS" == true ]] && echo "mta-sts.$MAIL_DOMAIN:443")
APP_URL=https://$IDP_HOSTNAME:$WEB_PORT
EOF
  chmod 644 "$CORVID_DIR/server.env"
}
