#!/usr/bin/env bash
# Prepares a host to run Corvid and installs the quadlet units. Run as root; safe to rerun.
# Starts nothing. The steps printed at the end lead to deploy.sh, which does.
set -euo pipefail

QUADLET_DIR=/etc/containers/systemd
STALWART_UID=2000
LOOPBACK_PORTS=(8080 8443)
CONTAINER_SUBNET=10.88.0.0/16
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"

die() { echo "FAIL: $*" >&2; exit 1; }
ok()  { echo "  ok   $*"; }

[[ $EUID -eq 0 ]] || die "must run as root"

echo "[1/6] preflight"
# verify.sh and apply-plan.sh fail confusingly without these, so check them up front.
for dep in podman openssl curl dig ss nft envsubst stalwart-cli; do
  command -v "$dep" >/dev/null || die "$dep is not installed (on Debian, envsubst is in gettext-base and dig in dnsutils)"
done
ok "dependencies present"

install -d -m 755 "$CORVID_DIR"
if [[ ! -e "$CORVID_DIR/config.yaml" ]]; then
  install -m 644 "$HERE/config.yaml" "$CORVID_DIR/config.yaml"
  echo "  NOTE installed the template; edit $CORVID_DIR/config.yaml, then rerun this script"
fi
require_image
load_server
HOST_PORTS=(25 465 587 993 80 "$WEB_PORT")
ok "config.yaml read: $MAIL_HOSTNAME, web port $WEB_PORT"
# On a host already serving mail, a container Corvid replaces may hold the ports. Anything else
# is a real conflict, which the pod would otherwise report later as a bare bind failure.
holder() {
  podman ps --format '{{.Names}}|{{.PodName}}|{{.Ports}}' 2>/dev/null |
    awk -F'|' -v want=":$1->" 'index($3, want) { print ($2 == "corvid" ? "corvid pod" : $1); exit }'
}

replaced=()
for p in "${HOST_PORTS[@]}" "${LOOPBACK_PORTS[@]}"; do
  if ! ss -tlnH "sport = :$p" 2>/dev/null | grep -q .; then
    ok "port $p free"
    continue
  fi
  owner="$(holder "$p")"
  [[ -n "$owner" ]] || die "port $p is held by something other than a container; free it and rerun (ss -tlnp 'sport = :$p')"
  if [[ "$owner" == "corvid pod" ]]; then
    ok "port $p held by Corvid's own pod"
    continue
  fi
  echo "  NOTE port $p is held by container '$owner'"
  replaced+=("$owner")
done
if (( ${#replaced[@]} )); then
  printf '  NOTE %s must be retired before the pod starts; see RUNBOOK.md\n' \
    "$(printf '%s\n' "${replaced[@]}" | sort -u | paste -sd, -)"
fi

echo "[2/6] directories and secrets"
install -d -o "$STALWART_UID" -g "$STALWART_UID" -m 750 "$CORVID_DIR/stalwart" "$CORVID_DIR/data"
install -d -m 700 "$CORVID_DIR/secrets"
install -m 644 "$HERE/unbound.conf" "$CORVID_DIR/unbound.conf"
# The pod's uid writes update requests and reads status.json here; nothing else is its to touch.
install -d -o "$STALWART_UID" -g "$STALWART_UID" -m 750 "$CORVID_DIR/update"
ok "directories"

# Read by the Stalwart process, which runs as uid 2000. Root-owned 600 would be
# unreadable and the DNS provider would fail at first use.
if [[ ! -s "$CORVID_DIR/secrets/cloudflare.token" ]]; then
  install -m 400 -o "$STALWART_UID" -g "$STALWART_UID" /dev/null "$CORVID_DIR/secrets/cloudflare.token"
  echo "  NOTE write the Cloudflare token to $CORVID_DIR/secrets/cloudflare.token"
else
  chown "$STALWART_UID:$STALWART_UID" "$CORVID_DIR/secrets/cloudflare.token"
  chmod 400 "$CORVID_DIR/secrets/cloudflare.token"
  ok "cloudflare token present"
fi

# Generated, never named "admin", and removed after first boot: while set it
# authenticates on every listener, ahead of the directory.
if [[ ! -f "$CORVID_DIR/stalwart.env" && ! -f "$CORVID_DIR/corvid.env" ]]; then
  recovery_pw="$(openssl rand -base64 24)"
  umask 077
  cat > "$CORVID_DIR/stalwart.env" <<ENVEOF
STALWART_RECOVERY_ADMIN=recovery:${recovery_pw}
ENVEOF
  cat > "$CORVID_DIR/corvid.env" <<ENVEOF
STALWART_URL=http://127.0.0.1:8080
STALWART_USER=
STALWART_PASSWORD=
RECOVERY_ADMIN_USER=recovery
RECOVERY_ADMIN_PASSWORD=${recovery_pw}
ENVEOF
  chmod 600 "$CORVID_DIR/stalwart.env" "$CORVID_DIR/corvid.env"
  ok "generated recovery credential (user: recovery)"
else
  ok "stalwart.env and corvid.env already present, left untouched"
fi

echo "[3/6] unit prerequisites"
# install.sh leaves the optional services these notes describe switched off, so it silences them.
note() { [[ -n "${CORVID_INSTALLER:-}" ]] || echo "  NOTE $*"; }
# Read from the units so no EnvironmentFile or host volume goes uncreated. A missing
# EnvironmentFile fails as a systemd error rather than as missing configuration.
for unit in "$HERE"/*.container "$HERE"/*.service; do
  while read -r envfile; do
    [[ -n "$envfile" && ! -e "$envfile" ]] || continue
    install -m 600 /dev/null "$envfile"
    # server.env is written by render_units below; the rest hold secrets only a person has.
    [[ "$envfile" == "$CORVID_DIR/server.env" ]] || note "fill in $envfile for $(basename "$unit")"
  done < <(sed -n 's/^EnvironmentFile=//p' "$unit")

  while read -r spec; do
    host="${spec%%:*}"
    [[ "$host" == "$CORVID_DIR"/* && ! -e "$host" ]] || continue
    base="$(basename "$host")"
    if [[ -f "$HERE/$base" ]]; then
      install -m 644 "$HERE/$base" "$host"
      ok "installed $base"
    elif [[ "$base" == *.* ]]; then
      # A file with no source here, such as a secret, is created by the step that knows its
      # owner and mode. A directory in its place fails later as a bare permission error.
      note "$host is a file this script does not create"
    else
      install -d -m 755 "$host"
      # A container running as a non-root uid cannot write a root-owned directory.
      note "$host must be owned by the uid $(basename "$unit") runs as"
    fi
  done < <(sed -n 's/^Volume=//p' "$unit")
done
ok "env files and volumes present"

echo "[4/6] quadlet units"
render_units "$QUADLET_DIR" "$HERE"
install -m 644 "$HERE/corvid-update.service" "$HERE/corvid-update.timer" "$HERE/corvid-update.path" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now corvid-update.timer corvid-update.path >/dev/null
ok "units installed from $CORVID_IMAGE, pod publishing web port $WEB_PORT"

# Installed quadlets start at the next boot and would collide with the container still
# holding the ports. Mask, not disable: the generator restores enable symlinks on every reload.
if (( ${#replaced[@]} )); then
  systemctl mask corvid-pod.service >/dev/null
  echo "  NOTE corvid-pod.service is masked until ${replaced[0]} is retired; RUNBOOK.md unmasks it"
fi

echo "[5/6] firewall"
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q '^Status: active'; then
  # Published ports are DNAT'd through FORWARD, so the INPUT rule `ufw allow` makes never
  # matches and mail drops silently. Both directions are required.
  ufw route allow from "$CONTAINER_SUBNET" to any >/dev/null
  # Other mail servers deliver on 25 and Let's Encrypt checks port 80, so both stay public;
  # server.client_access decides who reaches the ports mail apps use.
  public_ports=(25 80)
  client_ports=(465 587 993 "$WEB_PORT")
  read -ra sources <<<"${CLIENT_SOURCES?corvid-config did not report CLIENT_SOURCES; rebuild the image}"
  for p in "${public_ports[@]}"; do
    ufw route allow proto tcp from any to "$CONTAINER_SUBNET" port "$p" >/dev/null
  done
  for p in "${client_ports[@]}"; do
    for source in "${sources[@]}"; do
      ufw route allow proto tcp from "$source" to "$CONTAINER_SUBNET" port "$p" >/dev/null
    done
  done
  # Drop what the setting no longer allows, highest number first so the rest stay valid.
  wanted=" ${public_ports[*]/%/:any} "
  for p in "${client_ports[@]}"; do
    for source in "${sources[@]}"; do wanted+="$p:$source "; done
  done
  ufw status numbered | sed -nE "s|^\[ *([0-9]+)\] +${CONTAINER_SUBNET//./\\.} ([0-9]+)/tcp +ALLOW FWD +([^ ]+).*|\1 \2 \3|p" |
    sort -rn | while read -r number port source; do
      [[ "$source" == Anywhere ]] && source=any
      [[ "$wanted" == *" $port:$source "* ]] || ufw --force delete "$number" >/dev/null
    done
  ok "route rules: ${public_ports[*]} open to all, ${client_ports[*]} open to ${CLIENT_SOURCES:-this host only}"
else
  die "ufw is not active. Stalwart's setup endpoint is unauthenticated until the plan is applied, so this stops here; enable ufw and rerun"
fi

echo "[6/6] dns trust anchor"
# After the firewall: this fetches from data.iana.org through the container-to-internet
# route. auto-trust-anchor-file refuses to start against an empty volume.
UNBOUND_IMAGE=$(sed -n 's/^Image=//p' "$HERE/corvid-unbound.container")
podman volume exists corvid-unbound-keys 2>/dev/null || podman volume create corvid-unbound-keys >/dev/null
# unbound-anchor exits 1 when it creates or updates the file, which is the
# success case here, so the artifact is checked rather than the exit status.
podman run --rm -v corvid-unbound-keys:/var/lib/unbound --entrypoint unbound-anchor \
  "$UNBOUND_IMAGE" -a /var/lib/unbound/root.key >/dev/null 2>&1 || true
anchor=$(podman run --rm -v corvid-unbound-keys:/var/lib/unbound --entrypoint unbound-anchor \
           "$UNBOUND_IMAGE" -a /var/lib/unbound/root.key -l 2>/dev/null || true)
if grep -qE '^\. IN (DS|DNSKEY)' <<<"$anchor"; then
  ok "root trust anchor seeded"
else
  die "could not seed the DNSSEC root anchor (unbound-anchor fetches it from data.iana.org); unbound cannot start without it"
fi

[[ -n "${CORVID_INSTALLER:-}" ]] || cat <<'NEXT'

Next: edit /opt/corvid/config.yaml (rerun this script if server.web_port changes), write the
Cloudflare token, and fill in the env files listed above (templates in deploy/env/, see
RUNBOOK.md). Then run ./deploy.sh
NEXT
