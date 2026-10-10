#!/usr/bin/env bash
# Completes Stalwart's one-time first-boot setup without a browser. deploy.sh runs it.
# Stalwart accepts this setup only once, so on a server that already has an admin this
# exits cleanly and changes nothing.
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
STALWART_CLI="${STALWART_CLI:-stalwart-cli}"

[[ -r "$ENV_FILE" ]] || { echo "env file not readable: $ENV_FILE" >&2; exit 66; }
. "$HERE/lib.sh"
load_env "$ENV_FILE"
load_server

for v in STALWART_URL RECOVERY_ADMIN_USER RECOVERY_ADMIN_PASSWORD; do
  [[ -n "${!v:-}" ]] || { echo "missing required variable: $v" >&2; exit 78; }
done

if server_set_up; then
  echo "An admin credential is already set in $ENV_FILE, so the server is set up. Nothing to do."
  exit 0
fi

export STALWART_USER="$RECOVERY_ADMIN_USER" STALWART_PASSWORD="$RECOVERY_ADMIN_PASSWORD"

echo "waiting for the recovery listener..."
for _ in $(seq 1 60); do
  curl -sf -o /dev/null --max-time 3 "$STALWART_URL/healthz/live" && break
  sleep 2
done
curl -sf -o /dev/null --max-time 3 "$STALWART_URL/healthz/live" || { echo "Stalwart never answered at $STALWART_URL; check: journalctl -u corvid-mail" >&2; exit 69; }

# Certificates and DKIM are left to the Domain in stalwart.plan.ndjson, so each has one owner.
# RocksDB buffers are sized for one mailbox on a 1 GB host; Stalwart's defaults are 128 MB each.
payload=$(cat <<JSON
{
  "serverHostname": "$MAIL_HOSTNAME",
  "defaultDomain": "$MAIL_DOMAIN",
  "requestTlsCertificate": false,
  "generateDkimKeys": false,
  "dataStore": {
    "@type": "RocksDb",
    "path": "/var/lib/stalwart/data",
    "bufferSize": 8388608,
    "cacheSize": 16777216
  }
}
JSON
)

echo "applying Bootstrap..."
out=$($STALWART_CLI update Bootstrap --json "$payload" 2>&1) || { echo "$out" >&2; exit 1; }

# Stalwart always names the first admin admin@<domain>.
user="admin@${MAIL_DOMAIN}"
# An empty match is handled below; without || true, set -e would exit here silently.
secret=$(grep -oiE 'secret[^A-Za-z0-9]+([A-Za-z0-9]{12,})' <<<"$out" | grep -oE '[A-Za-z0-9]{12,}$' | head -1 || true)

if [[ -n "$user" && -n "$secret" ]]; then
  umask 077
  sed -i '/^STALWART_USER=/d;/^STALWART_PASSWORD=/d' "$ENV_FILE"
  printf 'STALWART_USER=%s\nSTALWART_PASSWORD=%s\n' "$user" "$secret" >> "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "admin credential written to $ENV_FILE"
  # The recovery admin logs in on every listener, ahead of the directory, for as long as it is set.
  if [[ -w "$CORVID_DIR/stalwart.env" ]]; then
    sed -i '/^STALWART_RECOVERY_ADMIN=/d' "$CORVID_DIR/stalwart.env"
    echo "recovery credential removed from stalwart.env"
  fi
else
  # The response carries the only copy of the secret, so it is kept rather than printed.
  response="$(dirname "$ENV_FILE")/bootstrap-response"
  (umask 077; printf '%s\n' "$out" > "$response")
  echo "could not find the admin credential in Stalwart's reply; the reply is saved in $response (mode 600)." >&2
  echo "Copy STALWART_USER and STALWART_PASSWORD from it into $ENV_FILE, then delete it." >&2
  exit 1
fi
