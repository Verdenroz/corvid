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

# curl -u puts the password in argv, readable in /proc by any local user for the call's
# lifetime. --config on a process substitution keeps it off the command line. Its quoted value
# needs its own backslashes and quotes escaped, or a password carrying either breaks the parse.
curl_authed() {
  local user="$STALWART_USER" pass="$STALWART_PASSWORD"
  user="${user//\\/\\\\}"; user="${user//\"/\\\"}"
  pass="${pass//\\/\\\\}"; pass="${pass//\"/\\\"}"
  curl --config <(printf 'user = "%s:%s"\n' "$user" "$pass") "$@"
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

# Writes CONTENT to DEST through a temp file in CORVID_DIR, outside update/, which the pod's uid
# can write, so a planted symlink cannot redirect root's write. Rename does not follow one either.
atomic_write() {
  local dest="$1" tmp
  tmp="$(mktemp "$CORVID_DIR/.write.XXXXXX")"
  printf '%s\n' "$2" >"$tmp"
  chmod 644 "$tmp"
  mv "$tmp" "$dest"
}

unit_masked() { [[ "$(systemctl is-enabled "$1" 2>/dev/null)" == masked ]]; }

# Sets and exports MAIL_DOMAIN, MAIL_HOSTNAME, MAIL_USER, ACME_CONTACT, WEB_PORT, HOST_ZONE and
# IDP_HOSTNAME from config.yaml's server section. Tests set CORVID_CONFIG_CMD to run it without
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
      MAIL_DOMAIN|MAIL_HOSTNAME|MAIL_USER|ACME_CONTACT|WEB_PORT|HOST_ZONE|IDP_HOSTNAME|PUBLISH_MTA_STS|PUBLISH_TLSA|MTA_STS_MODE|CLIENT_SOURCES|UPDATE_AUTO)
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
