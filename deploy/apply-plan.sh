#!/usr/bin/env bash
# Fills the Stalwart plan from config.yaml's server section and applies it. Secrets are piped,
# never written to disk. --dry-run checks syntax and changes nothing. deploy.sh runs this.
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
STALWART_CLI="${STALWART_CLI:-stalwart-cli}"

[[ -r "$ENV_FILE" ]] || { echo "env file not readable: $ENV_FILE" >&2; exit 66; }
. "$HERE/lib.sh"
load_env "$ENV_FILE"
load_server

[[ -n "${STALWART_URL:-}" ]] || { echo "missing required variable: STALWART_URL" >&2; exit 78; }
if [[ -z "${STALWART_TOKEN:-}" ]]; then
  for v in STALWART_USER STALWART_PASSWORD; do
    [[ -n "${!v:-}" ]] || { echo "missing required variable: $v (or STALWART_TOKEN)" >&2; exit 78; }
  done
fi

TOKEN="$CORVID_DIR/secrets/cloudflare.token"
[[ -s "$TOKEN" ]] || { echo "$TOKEN is empty; write the Cloudflare API token to it" >&2; exit 78; }

command -v "$STALWART_CLI" >/dev/null 2>&1 || {
  echo "stalwart-cli not found. Set STALWART_CLI, or use the container image:" >&2
  echo "  STALWART_CLI='podman run --rm -i --network=host -e STALWART_URL -e STALWART_USER -e STALWART_PASSWORD -e STALWART_TOKEN ghcr.io/stalwartlabs/cli'" >&2
  exit 69
}

curl_authed -fsS -o /dev/null "$STALWART_URL/api/account" \
  || { echo "credentials in $ENV_FILE do not authenticate against $STALWART_URL" >&2; exit 1; }

# deploy.sh applies oidc.plan.ndjson, which points Authentication at an external directory, only
# after idp.sh has provisioned the users; until someone is enrolled nobody can log in.
PLAN="${PLAN:-$HERE/stalwart.plan.ndjson}"
[[ -r "$PLAN" ]] || { echo "no such plan: $PLAN" >&2; exit 66; }

# The plan lists every record type for the mail domain; this drops the two that config.yaml
# rules out, since a JSON boolean cannot be a template placeholder.
narrow_records() {
  python3 -c '
import json, sys
domain, mta_sts, tlsa = sys.argv[1], sys.argv[2] == "true", sys.argv[3] == "true"
for line in sys.stdin:
    if not line.strip():
        continue
    op = json.loads(line)
    if op.get("object") == "Domain":
        for value in op["value"].values():
            records = value.get("dnsManagement", {}).get("publishRecords")
            if value.get("name") == domain and records:
                records.update(mtaSts=mta_sts, tlsa=tlsa)
    print(json.dumps(op, separators=(",", ":")))
' "$MAIL_DOMAIN" "${PUBLISH_MTA_STS:?corvid-config did not report PUBLISH_MTA_STS; rebuild the image}" \
    "${PUBLISH_TLSA:?corvid-config did not report PUBLISH_TLSA; rebuild the image}"
}

# Stalwart bans an address for scanning or failed logins. Traffic that leaves this host through
# its own address, as a VPN on the same machine sends it, would ban every one of its users.
export SERVER_IP="${SERVER_IP:-$(server_ip)}"
[[ -n "$SERVER_IP" ]] || echo "  could not find this server's public IP, so it is not exempt from bans" >&2

# Restricted to named variables: a bare envsubst rewrites every $ in the plan, hashes included.
# The fragments ride in the same apply so their #references resolve against this plan.
render() {
  local vars='${MAIL_DOMAIN} ${MAIL_HOSTNAME} ${MAIL_USER} ${ACME_CONTACT} ${WEB_PORT} ${HOST_ZONE} ${IDP_HOSTNAME} ${SERVER_IP} ${MTA_STS_MODE}'
  {
    envsubst "$vars" < "$PLAN"
    if [[ "$PLAN" == "$HERE/stalwart.plan.ndjson" ]]; then
      [[ -z "${HOST_ZONE:-}" ]] || envsubst "$vars" < "$HERE/stalwart.host-zone.ndjson"
      [[ -z "$SERVER_IP" ]] || envsubst "$vars" < "$HERE/stalwart.allowed-ip.ndjson"
    fi
  } | narrow_records
}

# A supplied credentials map replaces the stored one, wiping every app password while apply
# reports success. Credentials are set out of band; see RUNBOOK.md.
if render | grep -n '"object":"Account"' | grep -q '"credentials"'; then
  echo "the plan sets credentials on an Account, which would replace every existing one" >&2
  echo "set the initial password out of band instead; see deploy/RUNBOOK.md" >&2
  exit 65
fi

# `apply --dry-run` misses wrong field types, which fail partway through a real apply.
check=0
rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
render > "$rendered"
python3 "$HERE/check-plan.py" "$rendered" || check=$?
case $check in
  0) ;;
  69) echo "  no schema to check against; continuing" ;;
  *) exit $check ;;
esac

render | $STALWART_CLI apply --stdin "$@"

case " $* " in *" --dry-run "*) exit 0;; esac

# Applied settings sit inert until the server rereads them. A fresh v0.16.21 refuses the reload
# over spam DNSBL objects it ships and its own validator rejects; a restart loads them anyway.
if [[ -n "${CORVID_SKIP_RELOAD:-}" ]]; then
  echo "  reload skipped; the caller restarts the server"
elif ! $STALWART_CLI create Action --json '{"@type":"ReloadSettings"}'; then
  echo "the plan is applied but Stalwart refused to reload it; run: systemctl restart corvid-mail" >&2
  exit 1
fi

# A plan can lock the admin out of the interface that would fix it, or fail partway on a
# required field --dry-run cannot see. This notices while a shell is still open.
curl_authed -fsS -o /dev/null "$STALWART_URL/api/account" \
  || { echo "the admin credential can no longer log in after this plan; fix it before closing this shell" >&2; exit 1; }
