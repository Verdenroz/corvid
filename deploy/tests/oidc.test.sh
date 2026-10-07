#!/usr/bin/env bash
# Runs the pinned Stalwart and Pocket-ID side by side with oidc.plan.ndjson applied, mints a real
# Pocket-ID access token for a user without a passkey or browser, and checks what Stalwart makes
# of it over JMAP. Pocket-ID is driven only through STATIC_API_KEY and its REST API. Needs
# stalwart-cli, curl, python3 and docker or podman; bootstrap mode needs port 8080 free.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
image() { awk -F= '$1=="Image"{print $2}' "$here/$1.container"; }
mail_image="$(image corvid-mail)"
idp_image="$(image corvid-idp)"
engine="$(command -v podman || command -v docker)"
mail_port="${CORVID_RIG_MAIL_PORT:-18080}"
idp_port="${CORVID_RIG_IDP_PORT:-18411}"
issuer="http://localhost:$idp_port"
api_key=rig-static-api-key-0123456789
recovery_pw=RigRecovery-93kQxz7pW
work="$(mktemp -d)"
mail="corvid-rig-mail-$$"
idp="corvid-rig-idp-$$"
volumes=("$mail-etc" "$mail-data" "$idp-data")
trap '"$engine" rm -f "$mail" "$idp" >/dev/null 2>&1; "$engine" volume rm "${volumes[@]}" >/dev/null 2>&1; rm -rf "$work"' EXIT

command -v stalwart-cli >/dev/null || { echo "stalwart-cli not found" >&2; exit 69; }
for port in 8080 "$mail_port" "$idp_port"; do
  ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || { echo "port $port is in use" >&2; exit 69; }
done

"$engine" run -d --name "$idp" --network host \
  -e PORT="$idp_port" -e APP_URL="$issuer" -e ENCRYPTION_KEY=rig-encryption-key-0123456789 \
  -e STATIC_API_KEY="$api_key" -e ANALYTICS_DISABLED=true \
  -v "$idp-data:/app/data" "$idp_image" >/dev/null
"$engine" run -d --name "$mail" --network host -e STALWART_RECOVERY_ADMIN="recovery:$recovery_pw" \
  -v "$mail-etc:/etc/stalwart" -v "$mail-data:/var/lib/stalwart" "$mail_image" >/dev/null

wait_for() { for _ in $(seq 1 60); do curl -sf -o /dev/null --max-time 2 "$1" && return 0; sleep 1; done; return 1; }
wait_for "$issuer/.well-known/openid-configuration" || { echo "Pocket-ID never came up" >&2; exit 1; }
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never came up" >&2; exit 1; }

idp_api() {
  local path="$1"; shift
  curl -sf -H "X-API-Key: $api_key" -H 'content-type: application/json' "$issuer/api/$path" "$@"
}
json() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

# One-time access token, then a session, then the authorization-code flow with PKCE, the way the
# client does it minus the passkey and the browser. Prints the access token. Pocket-ID skips the
# consent step when the user already consented to the scopes, so the interaction is optional.
mint() {
  local user_id="$1" scope="$2" jar="$work/jar-$1" verifier challenge ott location code
  verifier="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  challenge="$(printf %s "$verifier" | python3 -c '
import base64, hashlib, sys
print(base64.urlsafe_b64encode(hashlib.sha256(sys.stdin.buffer.read()).digest()).decode().rstrip("="))')"
  ott="$(idp_api "users/$user_id/one-time-access-token" -d '{"ttl":"5m"}' | json token)"
  [[ -n "$ott" ]] || { echo "mint: no one-time access token" >&2; return 1; }
  curl -sf -c "$jar" -o /dev/null -X POST "$issuer/api/one-time-access-token/$ott" \
    || { echo "mint: the one-time access token was refused" >&2; return 1; }
  location="$(curl -s -b "$jar" -o /dev/null -w '%{redirect_url}' -G "$issuer/authorize" \
    --data-urlencode client_id=corvid --data-urlencode response_type=code \
    --data-urlencode redirect_uri=http://127.0.0.1/callback --data-urlencode "scope=$scope" \
    --data-urlencode "code_challenge=$challenge" --data-urlencode code_challenge_method=S256 \
    --data-urlencode state=rig-state-0123)"
  if [[ "$location" == *interaction=* ]]; then
    location="${location##*interaction=}"
    curl -sf -b "$jar" -o /dev/null -H 'content-type: application/json' \
      -d '{"step":"consent"}' "$issuer/api/oidc/interactions/$location/complete" \
      || { echo "mint: the consent step was refused" >&2; return 1; }
    location="$(curl -s -b "$jar" -o /dev/null -w '%{redirect_url}' "$issuer/authorize?interaction=$location")"
  fi
  [[ "$location" == *code=* ]] || { echo "mint: no authorization code in '$location'" >&2; return 1; }
  code="$(sed 's/.*[?&]code=\([^&]*\).*/\1/' <<<"$location")"
  curl -sf "$issuer/api/oidc/token" -d grant_type=authorization_code -d client_id=corvid \
    -d "code=$code" -d redirect_uri=http://127.0.0.1/callback -d "code_verifier=$verifier" \
    | json access_token
}

idp_api oidc/clients -d '{"id":"corvid","name":"corvid","isPublic":true,"pkceEnabled":true,"callbackURLs":["http://127.0.0.1/callback"]}' >/dev/null
nomail_id="$(idp_api users -d '{"username":"nomail","email":"nomail@rig.test","firstName":"No","lastName":"Mail","isAdmin":false}' | json id)"
outsider_id="$(idp_api users -d '{"username":"outsider","email":"outsider@rig.test","firstName":"Out","lastName":"Sider","isAdmin":false}' | json id)"
mailuser_id="$(idp_api users -d '{"username":"mailuser","email":"mailuser@rig.test","firstName":"Mailuser","lastName":"Rig","isAdmin":false}' | json id)"

cat > "$work/shell" <<SH
#!/bin/sh
printf 'MAIL_DOMAIN=rig.test\nMAIL_HOSTNAME=mail.rig.test\nMAIL_USER=mailuser\nACME_CONTACT=ops@rig.test\nWEB_PORT=$idp_port\n'
SH
chmod +x "$work/shell"
printf 'STALWART_URL=http://127.0.0.1:8080\nRECOVERY_ADMIN_USER=recovery\nRECOVERY_ADMIN_PASSWORD=%s\n' \
  "$recovery_pw" > "$work/corvid.env"
CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
  bash "$here/bootstrap-server.sh" >/dev/null || { echo "bootstrap-server.sh failed" >&2; exit 1; }
set -a; . "$work/corvid.env"; set +a
admin_user="$STALWART_USER"; admin_pw="$STALWART_PASSWORD"
"$engine" restart "$mail" >/dev/null
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never restarted" >&2; exit 1; }
export CORVID_DIR="$work"; . "$here/lib.sh"
create_admin_api_key "$work/corvid.env" || { echo "the admin API key was not created" >&2; exit 1; }
set -a; . "$work/corvid.env"; set +a
prefer_token
first_token="$STALWART_TOKEN"
create_admin_api_key "$work/corvid.env" || { echo "the admin API key was not recreated" >&2; exit 1; }
set -a; . "$work/corvid.env"; set +a
prefer_token

domain_id="$(stalwart-cli query Domain --json | python3 -c '
import json, sys
print(next(d["id"] for d in map(json.loads, filter(str.strip, sys.stdin)) if d["name"] == "rig.test"))')"
# Pocket-ID here is plain http on a loopback port; the plan's issuer is https on the web port.
{
  cat <<JSON
{"@type":"reconcile","object":"Tracer","matchOn":["@type"],"value":{"stdout":{"@type":"Stdout","enable":true,"ansi":false,"buffered":false,"level":"debug","eventsPolicy":"exclude","events":{}}}}
{"@type":"reconcile","object":"NetworkListener","matchOn":["name"],"value":{"l-http":{"name":"http","protocol":"http","useTls":false,"tlsImplicit":false,"bind":{"[::]:$mail_port":true}}}}
{"@type":"upsert","object":"Account","matchOn":["name"],"value":{"acct":{"@type":"User","name":"mailuser","description":"mailuser","domainId":"$domain_id","roles":{"@type":"User"}}}}
JSON
  IDP_HOSTNAME=localhost WEB_PORT="$idp_port" MAIL_DOMAIN=rig.test \
    envsubst '${MAIL_DOMAIN} ${IDP_HOSTNAME} ${WEB_PORT}' < "$here/oidc.plan.ndjson" | sed 's#"https://#"http://#'
} | stalwart-cli apply --stdin >/dev/null
"$engine" restart "$mail" >/dev/null
export STALWART_URL="http://127.0.0.1:$mail_port"
wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart never listened on $mail_port" >&2; exit 1; }

fail=0
check() { if [[ "$2" == "$3" ]]; then echo "  ok    $1"; else echo "  FAIL  $1 (got '$2')"; fail=1; fi; }
auth_failures() { "$engine" logs "$mail" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'auth\.(failed|error)'; }
# Prints the status and, when Stalwart logged a failure for the request, its reason.
session() {
  local before after status
  before="$(auth_failures | wc -l)"
  status="$(curl -s -o "$work/body" -w '%{http_code}' -H "Authorization: Bearer $1" "$STALWART_URL/jmap/session")"
  after="$(auth_failures | wc -l)"
  printf '%s' "$status"
  [[ "$after" -gt "$before" ]] && printf ' %s' "$(auth_failures | tail -1 | sed -E 's/.*(reason|details) = "(.*)", causedBy.*/\2/')"
  echo
}
mailbox_of() {
  python3 -c '
import json, sys
s = json.load(sys.stdin)
print(s["accounts"][s["primaryAccounts"]["urn:ietf:params:jmap:mail"]]["name"])' < "$work/body"
}

token="$(mint "$mailuser_id" "openid profile")" || exit 1
check "Pocket-ID issues an at+jwt for scope 'openid profile'" \
  "$(cut -d. -f1 <<<"$token" | python3 -c 'import base64,json,sys; s=sys.stdin.read().strip(); print(json.loads(base64.urlsafe_b64decode(s+"="*(-len(s)%4)))["typ"])')" at+jwt
check "the token's userinfo names the user" \
  "$(curl -s -H "Authorization: Bearer $token" "$issuer/api/oidc/userinfo" | json preferred_username)" mailuser

check "Stalwart accepts the token over JMAP" "$(session "$token")" 200
check "the session's mail account is the user's mailbox" "$(mailbox_of 2>/dev/null)" mailuser@rig.test

echo "== tokens and credentials Stalwart must refuse"
check "a token without the profile scope" "$(session "$(mint "$mailuser_id" openid)")" \
  "401 Missing required scope 'profile', present scopes: [\"openid\"]"
status="$(curl -s -u "$admin_user:$admin_pw" "$STALWART_URL/jmap/session" -o /dev/null -w '%{http_code}')"
check "the admin password once the OIDC directory is active" "$status" 401
check "a second key replaces the first rather than adding to it" \
  "$(jmap_admin '[["x:ApiKey/get", {"ids": null}, "c0"]]' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["methodResponses"][0][1]["list"]))')" 1
check "the replaced key no longer authenticates" \
  "$(curl -s -H "Authorization: Bearer $first_token" "$STALWART_URL/api/account" -o /dev/null -w '%{http_code}')" 401
check "corvid.env keeps one token line and its mode" \
  "$(grep -c '^STALWART_TOKEN=' "$work/corvid.env") $(stat -c %a "$work/corvid.env")" "1 600"
check "the admin API key once the OIDC directory is active" \
  "$(curl_authed -s "$STALWART_URL/api/account" -o /dev/null -w '%{http_code}')" 200
check "stalwart-cli with the admin API key once the OIDC directory is active" \
  "$(stalwart-cli query Account --json 2>&1 | grep -c '"emailAddress":"admin@rig.test"')" 1
check "stalwart-cli with the admin password once the OIDC directory is active" \
  "$( (unset STALWART_TOKEN; stalwart-cli --user "$admin_user" --password "$admin_pw" query Account --json 2>&1) | grep -c '"emailAddress"')" 0

mailuser_account="$(stalwart-cli query Account --json | python3 -c '
import json, sys
print(next(a["id"] for a in map(json.loads, filter(str.strip, sys.stdin)) if a.get("emailAddress") == "mailuser@rig.test"))')"
check "an apply through the admin API key writes once the OIDC directory is active" \
  "$(printf '%s\n' '{"@type":"upsert","object":"Account","matchOn":["name"],"value":{"acct":{"@type":"User","name":"mailuser","description":"mailuser rig"}}}' |
    stalwart-cli apply --stdin 2>&1 | grep -c '0 failed')" 1
check "an app password for another account through the admin API key" \
  "$(jmap_admin "[[\"x:AppPassword/set\", {\"accountId\": \"$mailuser_account\", \"create\": {\"n0\": {\"description\": \"rig\"}}}, \"c0\"]]" |
    python3 -c 'import json,sys; print(bool(json.load(sys.stdin)["methodResponses"][0][1].get("created")))')" True

echo "== a Pocket-ID user with no mailbox"
check "gets a mailbox created on its first token" "$(session "$(mint "$nomail_id" "openid profile")")" 200
accounts="$(stalwart-cli query Account --json)"
check "the new account is nomail@rig.test" "$(grep -c '"emailAddress":"nomail@rig.test"' <<<"$accounts")" 1
group_id="$(idp_api user-groups -d '{"name":"mail","friendlyName":"mail"}' | json id)"
idp_api "user-groups/$group_id/users" -X PUT -d "{\"userIds\":[\"$mailuser_id\"]}" >/dev/null
idp_api oidc/clients/corvid/allowed-user-groups -X PUT -d "{\"userGroupIds\":[\"$group_id\"]}" >/dev/null
idp_api oidc/clients/corvid -X PUT \
  -d '{"name":"corvid","isPublic":true,"pkceEnabled":true,"callbackURLs":["http://127.0.0.1/callback"],"isGroupRestricted":true}' >/dev/null
check "a group-restricted client issues no code to a user outside the group" \
  "$(mint "$outsider_id" "openid profile" 2>&1 | grep -o 'not+allowed+to+access+this+service' | head -1)" not+allowed+to+access+this+service
check "a group member still gets a working token" "$(session "$(mint "$mailuser_id" "openid profile")")" 200

[[ $fail -eq 0 ]] || { "$engine" logs "$mail" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'auth\.|http\.' | tail -10; }
exit "$fail"
