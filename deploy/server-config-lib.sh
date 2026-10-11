# shellcheck shell=bash
# The server: section install.sh writes to config.yaml. It owns that section outright and rewrites
# it on every run, so anything an operator set there that install.sh does not ask about has to be
# read back and written again, or the next run silently drops it.

# The server.android_apps block of CONFIG, key line included, or nothing when it is unset.
kept_android_apps() {
  awk '/^server:/ {s = 1; next} /^[^ ]/ {s = 0}
       s && /^  android_apps:/ {a = 1; print; next}
       s && a && /^    / {print; next}
       s {a = 0}' <<<"$1"
}

# Arguments: DOMAIN HOSTNAME MAIL_USER ACME_CONTACT WEB_PORT CLIENT_ACCESS HOST_ZONE ASSETLINKS_CADDY
# ANDROID_APPS. HOST_ZONE and ASSETLINKS_CADDY (any text for yes) may be empty; ANDROID_APPS is the
# block kept_android_apps printed.
server_block() {
  local domain="$1" hostname="$2" mail_user="$3" acme_contact="$4" web_port="$5" client_access="$6"
  local host_zone="$7" assetlinks_caddy="$8" android_apps="$9"
  cat <<EOF
# Written by install.sh, which rewrites server: on every run. No secrets here; see deploy/config.yaml.
server:
  domain: $domain
  hostname: $hostname
  mail_user: $mail_user
  acme_contact: $acme_contact
  web_port: $web_port
  client_access: $client_access${host_zone:+
  host_zone: $host_zone}${assetlinks_caddy:+
  assetlinks_caddy: true}${android_apps:+
$android_apps}

EOF
}
