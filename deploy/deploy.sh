#!/usr/bin/env bash
# Starts Corvid and configures it, then runs verify.sh. Run as root after bootstrap.sh, once
# config.yaml and the env files are filled in.
set -euo pipefail

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"

[[ $EUID -eq 0 ]] || { echo "must run as root" >&2; exit 1; }
[[ -r "$ENV_FILE" ]] || { echo "env file not readable: $ENV_FILE" >&2; exit 66; }
. "$HERE/lib.sh"
load_env "$ENV_FILE"

[[ -r "$CORVID_DIR/config.yaml" ]] || { echo "$CORVID_DIR/config.yaml is missing; bootstrap.sh installs it" >&2; exit 78; }
# The milter writes the proxy base into every delivered message, so a template value is permanent.
if grep -q 'example\.com' "$CORVID_DIR/config.yaml"; then
  echo "$CORVID_DIR/config.yaml still holds example.com values from the template; set your own and rerun" >&2
  exit 78
fi
require_image
load_server

required=(RECOVERY_ADMIN_USER RECOVERY_ADMIN_PASSWORD)
# Whatever the plan interpolates is required too, read from the plan so the lists cannot drift.
while read -r v; do required+=("$v"); done \
  < <(grep -oh '\${[A-Z_]*}' "$HERE"/*.plan.ndjson | tr -d '${}' | sort -u)

missing=()
for v in $(printf '%s\n' "${required[@]}" | sort -u); do
  [[ -n "${!v:-}" ]] || missing+=("$v")
done
if (( ${#missing[@]} )); then
  echo "no value for: ${missing[*]} (config.yaml server section, or $ENV_FILE)" >&2
  exit 78
fi
[[ -s "$CORVID_DIR/secrets/cloudflare.token" ]] || { echo "$CORVID_DIR/secrets/cloudflare.token is empty; write the Cloudflare API token to it" >&2; exit 78; }

step() { echo; echo "== $* =="; }

# bootstrap.sh masks the pod while another container holds the mail ports. Starting the pod
# interrupts mail, so unmasking it is a deliberate step.
if unit_masked corvid-pod.service; then
  echo "corvid-pod.service is masked: retire the container holding the mail ports first" >&2
  echo "see deploy/RUNBOOK.md, 'Replacing an existing Stalwart container'" >&2
  exit 69
fi

# The apply queues DNS, DKIM and certificate tasks, and a running task holds a lock for an hour
# that survives a restart. Restarting mid-task stalls it until the lock expires.
wait_for_due_tasks() {
  local pending
  for _ in $(seq 1 60); do
    # Renewals sit in the queue months ahead, so only tasks already due count.
    pending=$(stalwart-cli query Task --json 2>/dev/null | python3 -c '
import datetime, json, sys
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
print(sum(1 for t in map(json.loads, filter(str.strip, sys.stdin)) if t["due"] <= now))' || true)
    (( pending == 0 )) && { echo "  queue drained"; return; }
    sleep 5
  done
  echo "  WARN $pending task(s) still pending; restarting now delays them up to an hour" >&2
}

wait_healthy() {
  for _ in $(seq 1 90); do
    curl -sf -o /dev/null --max-time 3 "$STALWART_URL/healthz/live" && return 0
    sleep 2
  done
  echo "Stalwart did not become healthy at $STALWART_URL; check: journalctl -u corvid-mail" >&2; return 1
}

# Each marker holds a fingerprint of what the running service last started with, so a rerun
# restarts only what changed, and a run that died before its restart is caught by the next.
POD_MARKER="$CORVID_DIR/.live-pod"
CONFIG_MARKER="$CORVID_DIR/.live-config"
mapfile -t PLAN_OBJECTS < <(grep -oh '"object":"[A-Za-z]*"' "$HERE"/stalwart*.ndjson "$HERE/auth.plan.ndjson" | cut -d'"' -f4 | sort -u)
pod_inputs() {
  { cat /etc/containers/systemd/corvid* "$CORVID_DIR"/{server.env,Caddyfile,unbound.conf}
    podman image inspect -f '{{.Id}}' "$CORVID_IMAGE"; } | sha256sum
}
# Snapshots list object keys in a different order each call, so they are sorted before hashing.
config_state() {
  stalwart-cli snapshot --quiet --allow-unresolved Tenant,Directory,PublicKey,Role "${PLAN_OBJECTS[@]}" |
    python3 -c 'import json, sys; [print(json.dumps(json.loads(l), sort_keys=True)) for l in sys.stdin if l.strip()]' |
    sha256sum
}

step "rendering units from config.yaml"
render_units /etc/containers/systemd "$HERE"
systemctl daemon-reload

step "installing the app release"
install_apps

pod="$(pod_inputs)"
if ! systemctl is-active -q corvid-pod.service; then
  step "starting pod"
  systemctl start corvid-pod.service
elif [[ "$pod" != "$(cat "$POD_MARKER" 2>/dev/null)" ]]; then
  step "units, config files or image changed; restarting the pod"
  systemctl restart corvid-pod.service
else
  step "pod running and unchanged"
fi
wait_healthy
printf '%s\n' "$pod" > "$POD_MARKER"

if ! server_set_up; then
  step "first boot"
  "$HERE/bootstrap-server.sh"

  step "restart to leave bootstrap mode"
  systemctl restart corvid-mail.service
  wait_healthy
fi

# A parse can succeed on the wrong string, so prove the credential before anything uses it.
load_env "$ENV_FILE"
curl_authed -fsS -o /dev/null "$STALWART_URL/api/account" \
  || { echo "the admin credential in $ENV_FILE does not log in; check STALWART_USER and STALWART_PASSWORD, or STALWART_TOKEN" >&2; exit 1; }
echo "  credential verified"

if [[ -z "${STALWART_TOKEN:-}" ]]; then
  step "creating the admin API key"
  create_admin_api_key "$ENV_FILE"
  load_env "$ENV_FILE"
  curl_authed -fsS -o /dev/null "$STALWART_URL/api/account" \
    || { echo "the new API key in $ENV_FILE does not log in" >&2; exit 1; }
  echo "  API key verified"
fi

# The sentinel writes device keys as admin and gets a key of its own, so replacing either key
# leaves the other working.
if ! grep -q '^CORVID_STALWART_TOKEN=.' "$CORVID_DIR/sentinel-devices.env" 2>/dev/null; then
  step "creating the sentinel's admin API key"
  create_admin_api_key "$CORVID_DIR/sentinel-devices.env" corvid-sentinel CORVID_STALWART_TOKEN
  systemctl restart corvid-sentinel.service
fi

# Asked before any plan is applied: a sentinel that is down says nothing about who is enrolled.
step "checking passkey enrollment"
if unit_masked corvid-sentinel.service; then
  summary="off 0"
else
  summary="$(auth_decision)" || {
    echo "the sentinel did not answer /internal/auth/status; sign-in is left as it is, check: journalctl -u corvid-sentinel" >&2
    exit 1
  }
fi
read -r sign_in waiting_approver_codes <<<"$summary"

step "applying configuration"
CORVID_SKIP_RELOAD=1 "$HERE/apply-plan.sh"

# Account passwords stop working once the sentinel is Stalwart's directory, so a passkey that
# approves devices must exist first, and the deploy scripts use the API key. Until then Stalwart
# keeps the directory it has.
if [[ "$sign_in" == passkeys ]]; then
  step "switching sign-in to passkeys"
  CORVID_SKIP_RELOAD=1 PLAN="$HERE/auth.plan.ndjson" "$HERE/apply-plan.sh"
fi

config="$(config_state)" || config="unknown"
if [[ "$config" == "$(cat "$CONFIG_MARKER" 2>/dev/null)" ]]; then
  step "configuration unchanged; no restart needed"
else
  step "waiting for queued tasks"
  wait_for_due_tasks
  step "waiting for the issuer"
  "$HERE/wait-issuer.sh" 300 || { echo "Stalwart is not restarted; check: journalctl -u corvid-caddy -u corvid-sentinel, then rerun" >&2; exit 1; }
  step "restart to make the configuration live"
  systemctl restart corvid-mail.service
  wait_healthy
  [[ "$config" == unknown ]] || printf '%s\n' "$config" > "$CONFIG_MARKER"
fi

# Until a passkey approves devices there is no way to make a second code, so a deploy leaves one
# approver code waiting. An unattended update never does: its output lands in the journal.
if [[ -z "${CORVID_UPDATING:-}" && "$sign_in" == none && "$waiting_approver_codes" == 0 ]]; then
  step "passkey setup code"
  if "$HERE/passkey.sh" code --approver; then
    echo "Enter it in the Corvid app: Set up this device. deploy/passkey.sh code mints another."
  fi
fi

# install.sh verifies once certificates are in, so a second run here would repeat it.
if [[ -z "${CORVID_INSTALLER:-}" ]]; then
  step "verifying"
  "$HERE/verify.sh" "$MAIL_DOMAIN" "$MAIL_HOSTNAME"
fi

# update.sh compares the registry against this, so a hand-run deploy must record what it started.
podman image inspect -f '{{.Id}}' "$CORVID_IMAGE" > "$CORVID_DIR/.deployed-id"

if [[ -z "${CORVID_UPDATING:-}" && -e /etc/systemd/system/corvid-update.path ]]; then
  # corvid-update.path fires on the file existing, so it must appear already holding `check`.
  atomic_write "$CORVID_DIR/update/request" check
fi
