#!/usr/bin/env bash
# Runs the pinned Stalwart with the real sentinel as its OIDC issuer, enrolls passkeys with a
# software authenticator over the sentinel's public routes, applies auth.plan.ndjson, and checks
# what Stalwart makes of the tokens over JMAP: that the account is the same one it was before the
# directory switched, that a master token opens no mailbox, that the admin API key works throughout,
# and that a token stays refused after the issuer was down when Stalwart first checked it. The
# sentinel and the authenticator are built with cargo unless CORVID_SENTINEL_BIN and
# CORVID_SOFT_BIN name them. Needs stalwart-cli, curl, python3, envsubst and docker or podman;
# bootstrap mode needs port 8080 free, and a run takes a few minutes.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
mail_image="$(awk -F= '$1=="Image"{print $2}' "$here/corvid-mail.container")"
engine="$(command -v podman || command -v docker)"
mail_port="${CORVID_RIG_MAIL_PORT:-18080}"
smtp_port="${CORVID_RIG_SMTP_PORT:-18125}"
sentinel_port="${CORVID_RIG_SENTINEL_PORT:-18333}"
recovery_pw=RigRecovery-93kQxz7pW
sentinel_secret=rig-sentinel-secret-0123456789
app_sha256=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff
work="$(mktemp -d)"
mail="corvid-rig-mail-$$"
volumes=("$mail-etc" "$mail-data")
sentinel_pid=
trap '"$engine" rm -f "$mail" >/dev/null 2>&1; "$engine" volume rm "${volumes[@]}" >/dev/null 2>&1; [[ -z "$sentinel_pid" ]] || kill "$sentinel_pid" 2>/dev/null; rm -rf "$work"' EXIT

command -v stalwart-cli >/dev/null || { echo "stalwart-cli not found" >&2; exit 69; }
for port in 8080 "$mail_port" "$smtp_port" "$sentinel_port"; do
  ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || { echo "port $port is in use" >&2; exit 69; }
done

build() {
  (cd "$here/.." && cargo build -p corvid-sentinel --locked --quiet "$@") || { echo "cargo build $* failed" >&2; exit 1; }
}
sentinel_bin="${CORVID_SENTINEL_BIN:-}"
if [[ -z "$sentinel_bin" ]]; then
  build
  sentinel_bin="${CARGO_TARGET_DIR:-$here/../target}/debug/corvid-sentinel"
fi
soft_bin="${CORVID_SOFT_BIN:-}"
if [[ -z "$soft_bin" ]]; then
  build --example soft-authenticator --features soft-authenticator
  soft_bin="${CARGO_TARGET_DIR:-$here/../target}/debug/examples/soft-authenticator"
fi

"$engine" run -d --name "$mail" --network host -e STALWART_RECOVERY_ADMIN="recovery:$recovery_pw" \
  -v "$mail-etc:/etc/stalwart" -v "$mail-data:/var/lib/stalwart" "$mail_image" >/dev/null

wait_for() { for _ in $(seq 1 60); do curl -sf -o /dev/null --max-time 2 "$1" && return 0; sleep 1; done; return 1; }
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never came up" >&2; exit 1; }
json() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

stalwart_id() {
  stalwart-cli query "$1" --json | python3 -c '
import json, sys
print(next(o["id"] for o in map(json.loads, filter(str.strip, sys.stdin)) if o.get(sys.argv[1]) == sys.argv[2]))' "$2" "$3"
}
account_of() { stalwart_id Account emailAddress "$1"; }

export CORVID_DIR="$work"; . "$here/lib.sh"
cat > "$work/shell" <<SH
#!/bin/sh
printf 'MAIL_DOMAIN=rig.test\nMAIL_HOSTNAME=mail.rig.test\nMAIL_USER=mailuser\nACME_CONTACT=ops@rig.test\nWEB_PORT=4443\nIDP_HOSTNAME=idp.rig.test\nADMIN_USER=mailuser-admin\nIDP_URL=http://127.0.0.1:1411\nSENTINEL_URL=http://127.0.0.1:$sentinel_port\nISSUER_URL=http://127.0.0.1:$sentinel_port/corvid/auth\n'
SH
chmod +x "$work/shell"
printf 'STALWART_URL=http://127.0.0.1:8080\nRECOVERY_ADMIN_USER=recovery\nRECOVERY_ADMIN_PASSWORD=%s\n' \
  "$recovery_pw" > "$work/corvid.env"
printf 'CORVID_SENTINEL_SECRET=%s\n' "$sentinel_secret" > "$work/sentinel-webhook.env"
CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
  bash "$here/bootstrap-server.sh" >/dev/null || { echo "bootstrap-server.sh failed" >&2; exit 1; }
load_env "$work/corvid.env"
admin_user="$STALWART_USER"; admin_pw="$STALWART_PASSWORD"
"$engine" restart "$mail" >/dev/null
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never restarted" >&2; exit 1; }
create_admin_api_key "$work/corvid.env" || { echo "the admin API key was not created" >&2; exit 1; }
load_env "$work/corvid.env"

domain_id="$(stalwart_id Domain name rig.test)"
{
  cat <<JSON
{"@type":"reconcile","object":"Tracer","matchOn":["@type"],"value":{"stdout":{"@type":"Stdout","enable":true,"ansi":false,"buffered":false,"level":"debug","eventsPolicy":"exclude","events":{}}}}
{"@type":"reconcile","object":"NetworkListener","matchOn":["name"],"value":{"l-http":{"name":"http","protocol":"http","useTls":false,"tlsImplicit":false,"bind":{"[::]:$mail_port":true}},"l-smtp":{"name":"smtp","protocol":"smtp","useTls":false,"tlsImplicit":false,"bind":{"[::]:$smtp_port":true}}}}
{"@type":"upsert","object":"Account","matchOn":["name"],"value":{"acct":{"@type":"User","name":"mailuser","description":"mailuser","domainId":"$domain_id","roles":{"@type":"User"}}}}
{"@type":"update","object":"MtaStageAuth","value":{"require":{"match":{},"else":"false"}}}
JSON
  grep -E '"keysdom"|"acctkeys"|refuse-keys' "$here/stalwart.plan.ndjson" | MAIL_DOMAIN=rig.test envsubst '${MAIL_DOMAIN}'
} | stalwart-cli apply --stdin >/dev/null
"$engine" restart "$mail" >/dev/null
export STALWART_URL="http://127.0.0.1:$mail_port"
sed -i "s#^STALWART_URL=.*#STALWART_URL=$STALWART_URL#" "$work/corvid.env"
wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart never listened on $mail_port" >&2; exit 1; }

android_origin="android:apk-key-hash:$(python3 -c '
import base64, sys
print(base64.urlsafe_b64encode(bytes.fromhex(sys.argv[1])).decode().rstrip("="))' "$app_sha256")"
cat > "$work/sentinel.yaml" <<YAML
server:
  domain: rig.test
  hostname: mail.rig.test
  mail_user: mailuser
  acme_contact: ops@rig.test
  android_apps:
    - {package: org.rig.corvid, sha256: $app_sha256}
sentinel:
  bind: 127.0.0.1:$sentinel_port
  state: $work/sentinel.json
  auth: {state: $work/auth.json}
  devices: {state: $work/devices.json, stalwart_url: "$STALWART_URL"}
YAML
start_sentinel() {
  CORVID_CONFIG="$work/sentinel.yaml" CORVID_SENTINEL_SECRET="$sentinel_secret" CORVID_STALWART_TOKEN="$STALWART_TOKEN" \
    "$sentinel_bin" >> "$work/sentinel.log" 2>&1 &
  sentinel_pid=$!
  wait_for "http://127.0.0.1:$sentinel_port/corvid/auth/.well-known/openid-configuration" \
    || { cat "$work/sentinel.log" >&2; echo "the sentinel never came up" >&2; exit 1; }
}
stop_sentinel() { kill "$sentinel_pid"; wait "$sentinel_pid" 2>/dev/null; sentinel_pid=; }
start_sentinel

fail=0
check() { if [[ "$2" == "$3" ]]; then echo "  ok    $1"; else echo "  FAIL  $1 (got '$2')"; fail=1; fi; }
passkey() { CORVID_HOST_NET=1 CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" bash "$here/passkey.sh" "$@" 2>/dev/null; }
# An authenticator of its own per name, signing as the app the config lists.
soft() { local name="$1"; shift; "$soft_bin" "$work/$name.state" "http://127.0.0.1:$sentinel_port" rig.test "$android_origin" "$@" 2>/dev/null; }
soft_status() { soft "$@" >/dev/null; echo $?; }
access_token() { json access_token; }
# Prints the status and, when Stalwart logged a failure for the request, its reason.
session() {
  local since status failure
  since="$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
  status="$(curl -s -o "$work/body" -w '%{http_code}' -H "Authorization: Bearer $1" "$STALWART_URL/jmap/session")"
  failure="$("$engine" logs --since "$since" "$mail" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'auth\.(failed|error)' |
    tail -1 | sed -E 's/.*(reason|details) = "(.*)", causedBy.*/\2/')"
  printf '%s' "$status"
  [[ -z "$failure" ]] || printf ' %s' "$failure"
  echo
}
primary_account() {
  python3 -c '
import json, sys
s = json.load(sys.stdin)
print(s["primaryAccounts"]["urn:ietf:params:jmap:mail"])' < "$work/body"
}

echo "== enrollment"
mailuser_account="$(account_of mailuser@rig.test)"
check "the status of a fresh sentinel is empty" "$(passkey status)" "daily 0, master 0, codes waiting: none"
daily_code="$(passkey code daily)"
master_code="$(passkey code master)"
check "a code is four groups of four characters" "$(grep -cE '^([A-Z2-7]{4}-){3}[A-Z2-7]{4}$' <<<"$daily_code")" 1
check "the status shows both codes waiting" "$(passkey status)" "daily 0, master 0, codes waiting: daily master"
check "a master passkey that can be backed up is refused" \
  "$(soft_status master enroll "$master_code" backup-eligible)" 1
check "the code is still good after a refused enrollment" "$(soft_status master enroll "$master_code")" 0
check "a code works once" "$(soft_status second enroll "$master_code")" 1
check "the daily code enrolls the daily passkey" "$(soft_status daily enroll "$daily_code")" 0
check "the status counts both roles" "$(passkey status)" "daily 1, master 1, codes waiting: none"
check "the list shows a synced and a device-bound passkey" \
  "$(passkey list | awk '{print $2, $4}' | sort | tr '\n' ' ')" "daily device-bound master device-bound "
check "a user's last passkey cannot be removed" \
  "$(passkey remove "$(passkey list | awk '$2=="daily"{print $1}')" >/dev/null; echo $?)" 1

echo "== before the directory switches"
check "the admin password logs in to JMAP" \
  "$(curl -s -u "$admin_user:$admin_pw" "$STALWART_URL/jmap/session" -o /dev/null -w '%{http_code}')" 200

echo "== switching the directory"
# A decoy first, so the reconcile has something to remove.
IDP_HOSTNAME=localhost WEB_PORT=1 MAIL_DOMAIN=rig.test \
  envsubst '${MAIL_DOMAIN} ${IDP_HOSTNAME} ${WEB_PORT}' < "$here/oidc.plan.ndjson" | sed 's#"https://#"http://#' |
  stalwart-cli apply --stdin >/dev/null
directory_urls() {
  local id
  for id in $(stalwart-cli query Directory --json | python3 -c '
import json, sys
for line in filter(str.strip, sys.stdin):
    print(json.loads(line)["id"])'); do
    stalwart-cli get Directory "$id" --fields issuerUrl --json | json issuerUrl
  done
}
check "the decoy directory is applied" "$(directory_urls)" "http://localhost:1"
ISSUER_URL="http://127.0.0.1:$sentinel_port/corvid/auth" MAIL_DOMAIN=rig.test \
  envsubst '${MAIL_DOMAIN} ${ISSUER_URL}' < "$here/auth.plan.ndjson" |
  stalwart-cli apply --stdin >/dev/null
check "the plan leaves the sentinel as the only directory" "$(directory_urls)" "http://127.0.0.1:$sentinel_port/corvid/auth"
"$engine" restart "$mail" >/dev/null
wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart never restarted" >&2; exit 1; }

echo "== signing in"
daily_tokens="$(soft daily login daily)"
daily_token="$(access_token <<<"$daily_tokens")"
check "the sentinel issues a daily token" "$([[ -n "$daily_token" ]] && echo yes)" yes
check "Stalwart accepts the daily token over JMAP" "$(session "$daily_token")" 200
check "the session's mail account is the one the mailbox had before the switch" \
  "$(primary_account)" "$mailuser_account"
master_token="$(soft master login master | access_token)"
check "a master token is refused over JMAP by audience" "$(session "$master_token" | cut -d' ' -f1)" 401
check "the admin password no longer logs in" \
  "$(curl -s -u "$admin_user:$admin_pw" "$STALWART_URL/jmap/session" -o /dev/null -w '%{http_code}')" 401
check "the admin API key still works" \
  "$(curl_authed -s "$STALWART_URL/api/account" -o /dev/null -w '%{http_code}')" 200
check "stalwart-cli with the admin API key still reads" \
  "$(stalwart-cli query Account --json 2>&1 | grep -c '"emailAddress":"mailuser@rig.test"')" 1

echo "== refresh tokens"
refresh="$(json refresh_token <<<"$daily_tokens")"
refreshed="$(curl -s -H 'content-type: application/json' -d "{\"refresh_token\":\"$refresh\"}" \
  "http://127.0.0.1:$sentinel_port/corvid/auth/token")"
check "a refresh answers a new access token Stalwart accepts" "$(session "$(access_token <<<"$refreshed")")" 200
check "the old refresh token works again within its grace" \
  "$(curl -s -o /dev/null -w '%{http_code}' -H 'content-type: application/json' -d "{\"refresh_token\":\"$refresh\"}" \
    "http://127.0.0.1:$sentinel_port/corvid/auth/token")" 200

echo "== approving a device with a master token"
sentinel() {
  local method="$1" path="$2"
  shift 2
  curl -s -o "$work/sbody" -w '%{http_code}' -X "$method" -H 'content-type: application/json' "$@" \
    "http://127.0.0.1:$sentinel_port$path"
}
check "a device asks for a key" "$(sentinel POST /corvid/devices/requests -d '{"name":"rig-phone"}')" 201
request_id="$(json id < "$work/sbody")"
request_code="$(json code < "$work/sbody")"
approve() { sentinel POST "/corvid/devices/requests/$request_id/approve" -H "X-Corvid-Master: $1" -d "{\"code\":\"$2\"}"; }
check "a daily token cannot approve it" "$(approve "$daily_token" "$request_code")" 401
fresh="$(soft master login master | access_token)"
check "a master token with a wrong code is refused" "$(approve "$fresh" 000000)" 403
check "and is spent by that request" "$(approve "$fresh" "$request_code")" 401
fresh="$(soft master login master | access_token)"
check "a fresh master token with the code approves it" "$(approve "$fresh" "$request_code")" 200

echo "== the issuer down when Stalwart verifies a token"
token="$(soft daily login daily | access_token)"
basic_admin() { curl_authed -s "$STALWART_URL/api/account" -o /dev/null -w '%{http_code}'; }
restart_mail() {
  "$engine" restart "$mail" >/dev/null
  wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart never restarted" >&2; exit 1; }
}
stop_sentinel
restart_mail
check "a token is refused while the issuer is down" "$(session "$token" | cut -d' ' -f1)" 401
check "the admin API key still authenticates while the issuer is down" "$(basic_admin)" 200
start_sentinel
check "the token stays refused after the issuer returns, until Stalwart restarts" "$(session "$token" | cut -d' ' -f1)" 401
restart_mail
check "the token is accepted after a restart with the issuer up" "$(session "$token")" 200

echo "== the bounded wait before Stalwart starts (the script the unit runs, not the unit)"
mkdir -p "$work/bin"
printf '#!/bin/sh\necho masked\n' > "$work/bin/systemctl"
chmod +x "$work/bin/systemctl"
printf 'APP_URL=http://localhost:1\nCORVID_ISSUER_URL=http://127.0.0.1:%s/corvid/auth\n' "$sentinel_port" > "$work/server.env"
# corvid-idp masked, as when Pocket-ID is gone, so only the sentinel is waited for.
wait_issuer() { PATH="$work/bin:$PATH" CORVID_HOST_NET=1 CORVID_DIR="$work" bash "$here/wait-issuer.sh" "$@" 2>/dev/null; }
stop_sentinel
SECONDS=0
wait_issuer 4; status=$?
check "it gives up at its bound with the sentinel down" "$status $((SECONDS >= 4 && SECONDS < 15))" "1 1"
( sleep 3; CORVID_CONFIG="$work/sentinel.yaml" CORVID_SENTINEL_SECRET="$sentinel_secret" CORVID_STALWART_TOKEN="$STALWART_TOKEN" \
    exec "$sentinel_bin" >> "$work/sentinel.log" 2>&1 ) &
sentinel_pid=$!
wait_issuer 60; status=$?
check "it returns once the sentinel answers within the bound" "$status" 0
wait_for "http://127.0.0.1:$sentinel_port/corvid/auth/.well-known/openid-configuration"

[[ $fail -eq 0 ]] || { [[ ! -s "$work/sentinel.log" ]] || tail -8 "$work/sentinel.log"; "$engine" logs "$mail" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'auth\.|http\.' | tail -10; }
exit "$fail"
