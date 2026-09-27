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

require_image() {
  podman image exists "$CORVID_IMAGE" && return
  echo "image $CORVID_IMAGE is not on this host; it carries corvid-config and three of the services" >&2
  echo "install.sh pulls it, or pull it by hand: podman pull $CORVID_IMAGE" >&2
  exit 69
}

# Sets and exports MAIL_DOMAIN, MAIL_HOSTNAME, MAIL_USER, ACME_CONTACT, WEB_PORT and HOST_ZONE
# from config.yaml's server section. Tests set CORVID_CONFIG_CMD to run it without the container.
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
      MAIL_DOMAIN|MAIL_HOSTNAME|MAIL_USER|ACME_CONTACT|WEB_PORT|HOST_ZONE|PUBLISH_MTA_STS|PUBLISH_TLSA|MTA_STS_MODE|CLIENT_SOURCES)
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
CORVID_MTA_STS_SITE=$([[ "$PUBLISH_MTA_STS" == true ]] && echo "mta-sts.$MAIL_DOMAIN:443")
APP_URL=https://$MAIL_HOSTNAME:$WEB_PORT/idp
EOF
  chmod 644 "$CORVID_DIR/server.env"
}
