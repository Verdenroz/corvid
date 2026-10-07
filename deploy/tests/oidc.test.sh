#!/usr/bin/env bash
# Runs the pinned Stalwart and Pocket-ID side by side with oidc.plan.ndjson applied, mints a real
# Pocket-ID access token for a user without a passkey or browser, and checks what Stalwart makes
# of it over JMAP. idp.sh provisions Pocket-ID, and the rig drives the rest only through
# STATIC_API_KEY and its REST API. The sentinel, built with cargo unless CORVID_SENTINEL_BIN names
# one, then approves a device end to end. Needs stalwart-cli, curl, python3 and docker or podman;
# bootstrap mode needs port 8080 free, and a run takes about six minutes, most of it a refresh chain.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
image() { awk -F= '$1=="Image"{print $2}' "$here/$1.container"; }
mail_image="$(image corvid-mail)"
idp_image="$(image corvid-idp)"
engine="$(command -v podman || command -v docker)"
mail_port="${CORVID_RIG_MAIL_PORT:-18080}"
smtp_port="${CORVID_RIG_SMTP_PORT:-18125}"
idp_port="${CORVID_RIG_IDP_PORT:-18411}"
sentinel_port="${CORVID_RIG_SENTINEL_PORT:-18333}"
issuer="http://localhost:$idp_port"
api_key=rig-static-api-key-0123456789
recovery_pw=RigRecovery-93kQxz7pW
work="$(mktemp -d)"
mail="corvid-rig-mail-$$"
idp="corvid-rig-idp-$$"
volumes=("$mail-etc" "$mail-data" "$idp-data")
sentinel_pid=
trap '"$engine" rm -f "$mail" "$idp" >/dev/null 2>&1; "$engine" volume rm "${volumes[@]}" >/dev/null 2>&1; [[ -z "$sentinel_pid" ]] || kill "$sentinel_pid" 2>/dev/null; rm -rf "$work"' EXIT

command -v stalwart-cli >/dev/null || { echo "stalwart-cli not found" >&2; exit 69; }
for port in 8080 "$mail_port" "$smtp_port" "$idp_port" "$sentinel_port"; do
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

# Prints the status of redeeming a one-time token into cookie jar $2. Pocket-ID rate-limits
# redemption, so a 429 waits for the window to reopen.
redeem() {
  local status
  for _ in $(seq 1 12); do
    status="$(curl -s -c "$2" -o /dev/null -w '%{http_code}' -X POST "$issuer/api/one-time-access-token/$1")"
    [[ "$status" == 429 ]] || break
    sleep 5
  done
  echo "$status"
}

# One-time access token, then a session, then the authorization-code flow with PKCE, the way the
# client does it minus the passkey and the browser. Prints the access token. Pocket-ID skips the
# consent step when the user already consented to the scopes, so the interaction is optional.
mint() {
  local user_id="$1" scope="$2" client="${3:-corvid}" jar="$work/jar-$1-${3:-corvid}" verifier challenge ott location code
  verifier="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  challenge="$(printf %s "$verifier" | python3 -c '
import base64, hashlib, sys
print(base64.urlsafe_b64encode(hashlib.sha256(sys.stdin.buffer.read()).digest()).decode().rstrip("="))')"
  ott="$(idp_api "users/$user_id/one-time-access-token" -d '{"ttl":"5m"}' | json token)"
  [[ -n "$ott" ]] || { echo "mint: no one-time access token" >&2; return 1; }
  [[ "$(redeem "$ott" "$jar")" == 200 ]] || { echo "mint: the one-time access token was refused" >&2; return 1; }
  location="$(curl -s -b "$jar" -o /dev/null -w '%{redirect_url}' -G "$issuer/authorize" \
    --data-urlencode "client_id=$client" --data-urlencode response_type=code \
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
  curl -sf "$issuer/api/oidc/token" -d grant_type=authorization_code -d "client_id=$client" \
    -d "code=$code" -d redirect_uri=http://127.0.0.1/callback -d "code_verifier=$verifier" \
    | tee "$work/last-token.json" | json access_token
}

cat > "$work/shell" <<SH
#!/bin/sh
printf 'MAIL_DOMAIN=rig.test\nMAIL_HOSTNAME=mail.rig.test\nMAIL_USER=mailuser\nACME_CONTACT=ops@rig.test\nWEB_PORT=$idp_port\nIDP_HOSTNAME=localhost\n'
SH
chmod +x "$work/shell"
printf 'STATIC_API_KEY=%s\n' "$api_key" > "$work/idp.env"
provision() {
  CORVID_DIR="$work" CORVID_CONFIG_CMD="$work/shell" CORVID_IDP_URL="$issuer" bash "$here/idp.sh" "$@"
}
snapshot() { idp_api users; idp_api user-groups; idp_api oidc/clients; }
idp_api oidc/clients -d '{"id":"corvid","name":"corvid","isPublic":true,"pkceEnabled":true,"callbackURLs":["http://127.0.0.1/callback"]}' >/dev/null
first_run="$(provision provision 2>/dev/null)" || { echo "idp.sh provision failed" >&2; exit 1; }
after_first="$(snapshot)"
second_run="$(provision provision 2>/dev/null)" || { echo "the second idp.sh provision failed" >&2; exit 1; }
after_second="$(snapshot)"
user_id() {
  idp_api "users?search=$1" | python3 -c '
import json, sys
print(next(u["id"] for u in json.load(sys.stdin)["data"] if u["username"] == sys.argv[1]))' "$1"
}
mailuser_id="$(user_id mailuser)"
admin_id="$(user_id mailuser-admin)"
nomail_id="$(idp_api users -d '{"username":"nomail","email":"nomail@rig.test","firstName":"No","lastName":"Mail","isAdmin":false}' | json id)"
outsider_id="$(idp_api users -d '{"username":"outsider","email":"outsider@rig.test","firstName":"Out","lastName":"Sider","isAdmin":false}' | json id)"
group_id="$(idp_api 'user-groups?search=mail' | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"][0]["id"])')"
idp_api "user-groups/$group_id/users" -X PUT -d "{\"userIds\":[\"$mailuser_id\",\"$nomail_id\"]}" >/dev/null
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
{"@type":"reconcile","object":"NetworkListener","matchOn":["name"],"value":{"l-http":{"name":"http","protocol":"http","useTls":false,"tlsImplicit":false,"bind":{"[::]:$mail_port":true}},"l-smtp":{"name":"smtp","protocol":"smtp","useTls":false,"tlsImplicit":false,"bind":{"[::]:$smtp_port":true}}}}
{"@type":"upsert","object":"Account","matchOn":["name"],"value":{"acct":{"@type":"User","name":"mailuser","description":"mailuser","domainId":"$domain_id","roles":{"@type":"User"}}}}
{"@type":"update","object":"MtaStageAuth","value":{"require":{"match":{},"else":"false"}}}
JSON
  grep -E '"keysdom"|"acctkeys"|refuse-keys' "$here/stalwart.plan.ndjson" | MAIL_DOMAIN=rig.test envsubst '${MAIL_DOMAIN}'
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
app_password="$(jmap_admin "[[\"x:AppPassword/set\", {\"accountId\": \"$mailuser_account\", \"create\": {\"n0\": {\"description\": \"rig\"}}}, \"c0\"]]" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["methodResponses"][0][1]["created"]["n0"]["secret"])')"
check "an app password for another account through the admin API key" "$([[ -n "$app_password" ]] && echo True)" True

echo "== a Pocket-ID user with no mailbox"
check "gets a mailbox created on its first token" "$(session "$(mint "$nomail_id" "openid profile")")" 200
accounts="$(stalwart-cli query Account --json)"
check "the new account is nomail@rig.test" "$(grep -c '"emailAddress":"nomail@rig.test"' <<<"$accounts")" 1

echo "== the provisioned client and users"
denied=not+allowed+to+access+this+service
check "a user outside the mail group gets no code" \
  "$(mint "$outsider_id" "openid profile" 2>&1 | grep -o "$denied" | head -1)" "$denied"
check "the admin user gets no code" \
  "$(mint "$admin_id" "openid profile" 2>&1 | grep -o "$denied" | head -1)" "$denied"
check "the mail user gets a working token" "$(session "$(mint "$mailuser_id" "openid profile")")" 200
check "the first provision prints a link for the admin and the mail user" \
  "$(sed -E 's#^(\S+) https://localhost:[0-9]+/lc/\S+$#\1#' <<<"$first_run" | sort | tr '\n' ' ')" "mailuser mailuser-admin "
check "the second provision prints nothing" "$second_run" ""
check "the second provision changes nothing in Pocket-ID" "$([[ "$after_first" == "$after_second" ]] && echo same)" same
link="$(provision link mailuser)"
check "link mints a fresh enrollment link" "$(grep -cE '^https://localhost:[0-9]+/lc/[A-Za-z0-9]+$' <<<"$link")" 1
check "the link's token signs the user in" \
  "$(redeem "${link##*/}" "$work/jar-link")" 200

echo "== the approval client"
client_flags() {
  idp_api "oidc/clients/$1" | python3 -c '
import json, sys
c = json.load(sys.stdin)
print(c["requiresReauthentication"], c["isGroupRestricted"], sorted(g["name"] for g in c["allowedUserGroups"]),
      c["accessTokenDurationMinutes"], c["refreshTokenDurationMinutes"])'
}
check "corvid-admin is restricted to the admins group, demands reauthentication and refreshes for a minute" \
  "$(client_flags corvid-admin)" "True True ['admins'] 5 1"
check "corvid is restricted to the mail group and keeps the default lifetimes" "$(client_flags corvid)" "False True ['mail'] 60 43200"
admins_id="$(idp_api 'user-groups?search=admins' | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"][0]["id"])')"
check "the admins group holds only the admin user" \
  "$(idp_api "user-groups/$admins_id" | python3 -c '
import json, sys
print(sorted(u["username"] for u in json.load(sys.stdin)["users"]))')" "['mailuser-admin']"
check "the mail user gets no code from corvid-admin" \
  "$(mint "$mailuser_id" "openid profile" corvid-admin 2>&1 | grep -o "$denied" | head -1)" "$denied"
# The passkey step cannot be completed from a one-time link session, which is the point.
admin_interaction() {
  local ott location
  ott="$(idp_api "users/$admin_id/one-time-access-token" -d '{"ttl":"5m"}' | json token)"
  redeem "$ott" "$work/jar-admin-step" >/dev/null
  location="$(curl -s -b "$work/jar-admin-step" -o /dev/null -w '%{redirect_url}' -G "$issuer/authorize" \
    --data-urlencode client_id=corvid-admin --data-urlencode response_type=code \
    --data-urlencode redirect_uri=http://127.0.0.1/callback --data-urlencode "scope=openid profile" \
    --data-urlencode code_challenge=rig-challenge-0123456789-rig-challenge-0123456 \
    --data-urlencode code_challenge_method=S256 --data-urlencode state=rig-state-0123)"
  ADMIN_INTERACTION="${location##*interaction=}"
}
admin_interaction
check "the admin's authorization to corvid-admin waits on a passkey" \
  "$(curl -s -b "$work/jar-admin-step" "$issuer/api/oidc/interactions/$ADMIN_INTERACTION" | json currentStep)" reauthenticate
check "the consent step is refused before the passkey step" \
  "$(curl -s -b "$work/jar-admin-step" -H 'content-type: application/json' -d '{"step":"consent"}' \
    "$issuer/api/oidc/interactions/$ADMIN_INTERACTION/complete" | json code)" validation_failed
check "the passkey step is refused without a reauthentication token" \
  "$(curl -s -b "$work/jar-admin-step" -H 'content-type: application/json' -d '{"step":"reauthenticate"}' \
    "$issuer/api/oidc/interactions/$ADMIN_INTERACTION/complete" | json code)" validation_failed

echo "== device keys owned by another account"
account_of() {
  stalwart-cli query Account --json | python3 -c '
import json, sys
print(next(a["id"] for a in map(json.loads, filter(str.strip, sys.stdin)) if a.get("emailAddress") == sys.argv[1]))' "$1"
}
keys_account="$(account_of corvid-keys@keys.rig.test)"
# Speaks SMTP politely and prints the reply code to RCPT TO, since a malformed command bans the address.
rcpt_code() {
  python3 -c '
import smtplib, sys
smtp = smtplib.SMTP("127.0.0.1", int(sys.argv[1]))
smtp.ehlo("probe.test")
smtp.mail("sender@probe.test")
print(smtp.rcpt(sys.argv[2])[0])
smtp.quit()' "$smtp_port" "$1"
}
check "the mailbox accepts mail" "$(rcpt_code mailuser@rig.test)" 250
check "the keys account refuses mail" "$(rcpt_code corvid-keys@keys.rig.test)" 550
keys_secret="$(jmap_admin "[[\"x:AppPassword/set\", {\"accountId\": \"$keys_account\", \"create\": {\"n0\": {\"description\": \"rig\"}}}, \"c0\"]]" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["methodResponses"][0][1]["created"]["n0"]["secret"])')"
check "the mailbox signs in with an app password" \
  "$(curl -s -u "mailuser@rig.test:$app_password" "$STALWART_URL/jmap/session" -o /dev/null -w '%{http_code}')" 200
check "the keys account cannot sign in even with a credential" \
  "$(curl -s -u "corvid-keys@keys.rig.test:$keys_secret" "$STALWART_URL/jmap/session" -o /dev/null -w '%{http_code}')" 403
keys_user_id="$(idp_api users -d '{"username":"corvid-keys","email":"corvid-keys@keys.rig.test","firstName":"Corvid","lastName":"Keys","isAdmin":false}' | json id)"
idp_api "user-groups/$group_id/users" -X PUT \
  -d "{\"userIds\":[\"$mailuser_id\",\"$nomail_id\",\"$keys_user_id\"]}" >/dev/null
namesake_token="$(mint "$keys_user_id" "openid profile")" || exit 1
check "a Pocket-ID user named for the keys account gets a mailbox of its own" \
  "$(session "$namesake_token" >/dev/null; mailbox_of)" corvid-keys@rig.test
check "and no access to the keys account" \
  "$(curl -s -H "Authorization: Bearer $namesake_token" -H 'content-type: application/json' "$STALWART_URL/jmap" \
    -d "{\"using\": [\"urn:ietf:params:jmap:core\", \"urn:ietf:params:jmap:filenode\"], \"methodCalls\": [[\"FileNode/get\", {\"accountId\": \"$keys_account\", \"ids\": null}, \"c0\"]]}" |
    json methodResponses | grep -c forbidden)" 1
mailuser_token="$(mint "$mailuser_id" "openid profile")" || exit 1
# Prints the JMAP response to one FileNode method on the keys account, called as $1 (admin or
# mailuser) with $3 as the arguments besides accountId.
files_as() {
  local body
  body="$(python3 -c '
import json, sys
print(json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:filenode"],
                  "methodCalls": [[sys.argv[1], {"accountId": sys.argv[2], **json.loads(sys.argv[3])}, "c0"]]}))' \
    "$2" "$keys_account" "$3")"
  if [[ "$1" == admin ]]; then
    curl_authed -s -H 'content-type: application/json' "$STALWART_URL/jmap" -d "$body"
  else
    curl -s -H "Authorization: Bearer $mailuser_token" -H 'content-type: application/json' "$STALWART_URL/jmap" -d "$body"
  fi
}
upload_as() {
  curl -s -H "Authorization: Bearer $1" -H 'content-type: application/octet-stream' \
    --data-binary "@$3" "$STALWART_URL/jmap/upload/$2/" | json blobId
}
created_id() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)["methodResponses"][0][1]["created"]["n"]["id"])'
}
node_names() {
  python3 -c '
import json, sys
print(" ".join(sorted(n["name"] for n in json.load(sys.stdin)["methodResponses"][0][1]["list"])))'
}
node_field() {
  python3 -c '
import json, sys
node = next(n for n in json.load(sys.stdin)["methodResponses"][0][1]["list"] if n["name"] == sys.argv[1])
print(node[sys.argv[2]])' "$1" "$2"
}
refusal() {
  python3 -c '
import json, sys
rows = json.load(sys.stdin)["methodResponses"][0][1].get(sys.argv[1]) or {}
print(" ".join(sorted({row["type"] for row in rows.values()})) or "accepted")' "$1"
}
key_bytes="$work/key.bin"
{ printf '\001'; head -c 32 /dev/urandom; } > "$key_bytes"
folder_id="$(files_as admin FileNode/set '{"create":{"n":{"name":"corvid-devices","parentId":null,"shareWith":{"'"$mailuser_account"'":{"mayRead":true}}}}}' | created_id)"
key_id="$(files_as admin FileNode/set '{"create":{"n":{"name":"dev1.key","parentId":"'"$folder_id"'","blobId":"'"$(upload_as "$STALWART_TOKEN" "$keys_account" "$key_bytes")"'"}}}' | created_id)"
listing="$(files_as mailuser FileNode/get '{"ids":null}')"
check "the mail user lists the shared folder and key" "$(node_names <<<"$listing")" "corvid-devices dev1.key"
check "the mail user holds read access only" \
  "$(python3 -c '
import json, sys
nodes = json.load(sys.stdin)["methodResponses"][0][1]["list"]
print(sorted({right for n in nodes for right, held in n["myRights"].items() if held}))' <<<"$listing")" "['mayRead']"
curl -s -H "Authorization: Bearer $mailuser_token" \
  "$STALWART_URL/jmap/download/$keys_account/$(node_field dev1.key blobId <<<"$listing")/dev1.key" > "$work/downloaded.bin"
check "the mail user downloads the key bytes" "$(cmp -s "$key_bytes" "$work/downloaded.bin" && echo same)" same
mailuser_blob="$(upload_as "$mailuser_token" "$mailuser_account" "$key_bytes")"
check "the mail user can still plant a file in the shared folder" \
  "$(files_as mailuser FileNode/set '{"create":{"n":{"name":"dev2.key","parentId":"'"$folder_id"'","blobId":"'"$mailuser_blob"'"}}}' | refusal notCreated)" accepted
check "the mail user cannot create at the root of the keys account" \
  "$(files_as mailuser FileNode/set '{"create":{"n":{"name":"dev3.key","parentId":null,"blobId":"'"$mailuser_blob"'"}}}' | refusal notCreated)" invalidProperties
check "the mail user cannot replace a key's content" \
  "$(files_as mailuser FileNode/set '{"update":{"'"$key_id"'":{"blobId":"'"$mailuser_blob"'"}}}' | refusal notUpdated)" forbidden
check "the mail user cannot rename a key" \
  "$(files_as mailuser FileNode/set '{"update":{"'"$key_id"'":{"name":"dev4.key"}}}' | refusal notUpdated)" forbidden
check "the mail user cannot destroy a key" \
  "$(files_as mailuser FileNode/set '{"destroy":["'"$key_id"'"]}' | refusal notDestroyed)" forbidden
check "the mail user cannot widen its own access" \
  "$(files_as mailuser FileNode/set '{"update":{"'"$folder_id"'":{"shareWith":{"'"$mailuser_account"'":{"mayRead":true,"mayAddChildren":true}}}}}' | refusal notUpdated)" forbidden
check "the keys account holds only the planted file besides the original" \
  "$(files_as admin FileNode/get '{"ids":null}' | node_names)" "corvid-devices dev1.key dev2.key"
curl_authed -s "$STALWART_URL/jmap/download/$keys_account/$(node_field dev1.key blobId <<<"$listing")/dev1.key" > "$work/after.bin"
check "the original key's bytes are unchanged after every write" "$(cmp -s "$key_bytes" "$work/after.bin" && echo same)" same

echo "== approving a device end to end"
# One-time link sessions cannot pass the passkey step, so the admin client stops asking for it
# while a token is minted and provision puts it back.
set_admin_reauth() {
  idp_api oidc/clients/corvid-admin | python3 -c '
import json, sys
keep = ("name", "callbackURLs", "logoutCallbackURLs", "isPublic", "pkceEnabled", "skipConsent", "isGroupRestricted",
        "accessTokenDurationMinutes", "refreshTokenDurationMinutes")
client = json.load(sys.stdin)
print(json.dumps({**{k: client[k] for k in keep}, "requiresReauthentication": sys.argv[1] == "true"}))' "$1" |
    idp_api oidc/clients/corvid-admin -X PUT -d @- >/dev/null
}
set_admin_reauth false
admin_token="$(mint "$admin_id" "openid profile" corvid-admin)" || exit 1
jwt_claim() {
  cut -d. -f2 <<<"$1" | python3 -c '
import base64, json, sys
s = sys.stdin.read().strip()
print(json.loads(base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)))[sys.argv[1]])' "$2"
}
admin_iat="$(jwt_claim "$admin_token" iat)"
# Refreshes every 45 seconds for just over the five minutes the sentinel allows, each time with
# the refresh token the previous grant rotated in, which keeps the chain inside its one minute.
(
  refresh="$(json refresh_token < "$work/last-token.json")"
  for _ in $(seq 1 7); do
    sleep 45
    curl -s "$issuer/api/oidc/token" -d grant_type=refresh_token -d client_id=corvid-admin \
      -d "refresh_token=$refresh" > "$work/chained.json"
    refresh="$(json refresh_token < "$work/chained.json")"
  done
) &
chain_pid=$!
provision provision >/dev/null 2>&1
check "provision puts the passkey requirement back" "$(client_flags corvid-admin)" "True True ['admins'] 5 1"
check "an admin token is not a mailbox credential" "$(session "$admin_token" | cut -d' ' -f1)" 401

sentinel_bin="${CORVID_SENTINEL_BIN:-}"
if [[ -z "$sentinel_bin" ]]; then
  (cd "$here/.." && cargo build -p corvid-sentinel --locked --quiet) || { echo "the sentinel did not build" >&2; exit 1; }
  sentinel_bin="${CARGO_TARGET_DIR:-$here/../target}/debug/corvid-sentinel"
fi
sentinel_secret=rig-sentinel-secret-0123456789
cat > "$work/sentinel.yaml" <<YAML
server: {domain: rig.test, hostname: mail.rig.test, mail_user: mailuser, acme_contact: ops@rig.test}
sentinel:
  bind: 127.0.0.1:$sentinel_port
  state: $work/sentinel.json
  devices: {state: $work/devices.json, issuer: "$issuer", idp_url: "$issuer", stalwart_url: "$STALWART_URL"}
YAML
CORVID_CONFIG="$work/sentinel.yaml" CORVID_SENTINEL_SECRET="$sentinel_secret" CORVID_STALWART_TOKEN="$STALWART_TOKEN" \
  "$sentinel_bin" > "$work/sentinel.log" 2>&1 &
sentinel_pid=$!
wait_for "http://127.0.0.1:$sentinel_port/corvid/notices" || { cat "$work/sentinel.log" >&2; echo "the sentinel never came up" >&2; exit 1; }
# Prints the status, leaving the body in $work/sbody.
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
check "the request reads as pending" "$(sentinel GET "/corvid/devices/requests/$request_id" >/dev/null; json status < "$work/sbody")" pending
check "a daily token cannot approve it" "$(approve "$mailuser_token" "$request_code")" 401
check "the admin token with a wrong code is refused" "$(approve "$admin_token" 000000)" 403
check "the admin token with the code approves it" "$(approve "$admin_token" "$request_code")" 200
approved_node="$(json nodeId < "$work/sbody")"
approved_blob="$(json blobId < "$work/sbody")"
check "the approval is for the keys account" "$(json accountId < "$work/sbody")" "$keys_account"
check "the request reads as approved" "$(sentinel GET "/corvid/devices/requests/$request_id" >/dev/null; json status < "$work/sbody")" approved
check "the mail user is given the record by device name" \
  "$(sentinel GET /corvid/devices/rig-phone >/dev/null; json nodeId < "$work/sbody")" "$approved_node"
read_key() {
  curl -s -H "Authorization: Bearer $mailuser_token" "$STALWART_URL/jmap/download/$keys_account/$approved_blob/rig-phone.key" |
    python3 -c 'import sys; key = sys.stdin.buffer.read(); print(len(key), key[:1].hex())'
}
check "the mail user reads a 33 byte version 1 key from that node" "$(read_key)" "33 01"
key_before="$(read_key)"
check "the mail user plants a file under a new name beside it" \
  "$(files_as mailuser FileNode/set '{"create":{"n":{"name":"planted.key","parentId":"'"$folder_id"'","blobId":"'"$mailuser_blob"'"}}}' | refusal notCreated)" accepted
check "the mail user cannot plant one under the approved name" \
  "$(files_as mailuser FileNode/set '{"create":{"n":{"name":"rig-phone.key","parentId":"'"$folder_id"'","blobId":"'"$mailuser_blob"'"}}}' | refusal notCreated)" alreadyExists
check "a planted name has no record" "$(sentinel GET /corvid/devices/planted)" 404
check "the approved record still names the approved node" \
  "$(sentinel GET /corvid/devices/rig-phone >/dev/null; json nodeId < "$work/sbody")" "$approved_node"
check "the approved key's bytes are unchanged" "$(read_key)" "$key_before"
check "the same request cannot be approved twice" "$(approve "$admin_token" "$request_code")" 410
revoke_signature() {
  python3 -c '
import base64, hashlib, hmac, sys
print(base64.b64encode(hmac.new(sys.argv[1].encode(), f"revoke {sys.argv[2]}".encode(), hashlib.sha256).digest()).decode())' "$sentinel_secret" "$1"
}
check "a revoke without the secret is refused" "$(sentinel DELETE /internal/devices/rig-phone)" 401
check "a signed revoke removes the key" \
  "$(sentinel DELETE /internal/devices/rig-phone -H "X-Signature: $(revoke_signature rig-phone)")" 204
check "the revoked device has no record" "$(sentinel GET /corvid/devices/rig-phone)" 404
check "the revoked key file is gone from the keys account" \
  "$(files_as admin FileNode/get '{"ids":null}' | node_names | tr ' ' '\n' | grep -c '^rig-phone.key$')" 0

echo "== Pocket-ID unreachable when Stalwart verifies a token"
token="$(mint "$mailuser_id" "openid profile")"
basic() { curl -s -u "mailuser@rig.test:$app_password" "$STALWART_URL/jmap/session" -o /dev/null -w '%{http_code}'; }
restart_mail() {
  "$engine" restart "$mail" >/dev/null
  wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart never restarted" >&2; exit 1; }
}
start_idp() {
  "$engine" start "$idp" >/dev/null
  wait_for "$issuer/.well-known/openid-configuration" || { echo "Pocket-ID never came back" >&2; exit 1; }
}
"$engine" stop "$idp" >/dev/null
restart_mail
check "a token is refused while the issuer is down" "$(session "$token" | cut -d' ' -f1)" 401
check "the admin API key still authenticates while the issuer is down" \
  "$(curl_authed -s "$STALWART_URL/api/account" -o /dev/null -w '%{http_code}')" 200
check "an app password still authenticates while the issuer is down" "$(basic)" 200
start_idp
check "the token stays refused after the issuer returns, until Stalwart restarts" "$(session "$token" | cut -d' ' -f1)" 401
restart_mail
check "the token is accepted after a restart with the issuer up" "$(session "$token")" 200

echo "== the bounded wait before Stalwart starts (the script the unit runs, not the unit)"
printf 'CORVID_IDP_HOSTNAME=localhost\n' > "$work/server.env"
wait_issuer() { CORVID_DIR="$work" CORVID_ISSUER_URL="$issuer" bash "$here/wait-issuer.sh" "$@" 2>/dev/null; }
"$engine" stop "$idp" >/dev/null
"$engine" stop "$mail" >/dev/null
SECONDS=0
wait_issuer 30 --if-oidc; status=$?
check "it does nothing until the OIDC plan is applied" "$status $((SECONDS < 3))" "0 1"
touch "$work/.oidc-active"
SECONDS=0
wait_issuer 4 --if-oidc; status=$?
check "it gives up at its bound with Pocket-ID down" "$status $((SECONDS >= 4 && SECONDS < 15))" "1 1"
"$engine" start "$mail" >/dev/null
wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart did not start with Pocket-ID down" >&2; exit 1; }
check "Stalwart is up and an app password works after the wait expired" "$(basic)" 200
check "the admin API key works after the wait expired" \
  "$(curl_authed -s "$STALWART_URL/api/account" -o /dev/null -w '%{http_code}')" 200
"$engine" stop "$mail" >/dev/null
( sleep 5; "$engine" start "$idp" >/dev/null ) &
wait_issuer 60 --if-oidc; status=$?
check "it returns as soon as Pocket-ID answers within the bound" "$status" 0
"$engine" start "$mail" >/dev/null
wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart did not start" >&2; exit 1; }
check "a token is accepted on Stalwart's first start after the wait, with no second restart" "$(session "$token")" 200

echo "== a refresh chain cannot renew an approval token"
wait "$chain_pid"
chained_token="$(json access_token < "$work/chained.json")"
check "a refreshed token keeps the issue time of the passkey authorization" \
  "$(jwt_claim "$chained_token" iat)" "$admin_iat"
check "while its expiry moves on" "$([[ "$(jwt_claim "$chained_token" exp)" -gt "$(jwt_claim "$admin_token" exp)" ]] && echo later)" later
check "a device asks for another key" "$(sentinel POST /corvid/devices/requests -d '{"name":"rig-tablet"}')" 201
request_id="$(json id < "$work/sbody")"
request_code="$(json code < "$work/sbody")"
check "the last token of the chain is refused five minutes after the passkey" \
  "$(approve "$chained_token" "$request_code")" 401
check "and the request is still pending" "$(sentinel GET "/corvid/devices/requests/$request_id" >/dev/null; json status < "$work/sbody")" pending

[[ $fail -eq 0 ]] || { [[ ! -s "$work/sentinel.log" ]] || tail -5 "$work/sentinel.log"; "$engine" logs "$mail" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'auth\.|http\.' | tail -10; }
exit "$fail"
