#!/usr/bin/env bash
# Runs the pinned Stalwart, applies the plan's own mail account lines, and checks what a deploy
# does to aliases added at runtime: a second apply keeps them, enabled or not, and leaves
# postmaster and abuse at keys 0 and 1. Over SMTP, a disabled alias is refused at RCPT and a
# re-enabled one accepted, and a delivered copy's Delivered-To names the alias it came to.
# A catch-all, which verify.sh warns about, is shown to take mail for a disabled alias.
# Needs stalwart-cli, curl, python3, envsubst and docker or podman; port 8080 must be free.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
engine="$(command -v podman || command -v docker)"
mail_image="$(awk -F= '$1=="Image"{print $2}' "$here/corvid-mail.container")"
mail_port="${CORVID_RIG_MAIL_PORT:-18080}"
smtp_port="${CORVID_RIG_SMTP_PORT:-18125}"
recovery_pw=RigRecovery-93kQxz7pW
work="$(mktemp -d)"
mail="corvid-rig-aliases-$$"
volumes=("$mail-etc" "$mail-data")
trap '"$engine" rm -f "$mail" >/dev/null 2>&1; "$engine" volume rm "${volumes[@]}" >/dev/null 2>&1; rm -rf "$work"' EXIT

command -v stalwart-cli >/dev/null || { echo "stalwart-cli not found" >&2; exit 69; }
command -v envsubst >/dev/null || { echo "envsubst not found" >&2; exit 69; }
for port in 8080 "$mail_port" "$smtp_port"; do
  ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || { echo "port $port is in use" >&2; exit 69; }
done

"$engine" run -d --name "$mail" --network host -e STALWART_RECOVERY_ADMIN="recovery:$recovery_pw" \
  -v "$mail-etc:/etc/stalwart" -v "$mail-data:/var/lib/stalwart" "$mail_image" >/dev/null
wait_for() { for _ in $(seq 1 60); do curl -sf -o /dev/null --max-time 2 "$1" && return 0; sleep 1; done; return 1; }
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never came up" >&2; exit 1; }

cat > "$work/shell" <<'SH'
#!/bin/sh
printf 'MAIL_DOMAIN=rig.test\nMAIL_HOSTNAME=mail.rig.test\nMAIL_USER=mailuser\nACME_CONTACT=ops@rig.test\nWEB_PORT=4443\n'
SH
chmod +x "$work/shell"
export CORVID_DIR="$work"; . "$here/lib.sh"
printf 'STALWART_URL=http://127.0.0.1:8080\nRECOVERY_ADMIN_USER=recovery\nRECOVERY_ADMIN_PASSWORD=%s\n' "$recovery_pw" > "$work/corvid.env"
CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
  bash "$here/bootstrap-server.sh" >/dev/null || { echo "bootstrap-server.sh failed" >&2; exit 1; }
load_env "$work/corvid.env"
"$engine" restart "$mail" >/dev/null
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never restarted" >&2; exit 1; }
create_admin_api_key "$work/corvid.env" || { echo "the admin API key was not created" >&2; exit 1; }
load_env "$work/corvid.env"

domain_id="$(stalwart_id Domain name rig.test)"
# Spam filtering is off so a test sender with no SPF record is delivered.
cat > "$work/rig.ndjson" <<JSON
{"@type":"reconcile","object":"NetworkListener","matchOn":["name"],"value":{"l-http":{"name":"http","protocol":"http","useTls":false,"tlsImplicit":false,"bind":{"[::]:$mail_port":true}},"l-smtp":{"name":"smtp","protocol":"smtp","useTls":false,"tlsImplicit":false,"bind":{"[::]:$smtp_port":true}}}}
{"@type":"update","object":"MtaStageAuth","value":{"require":{"match":{},"else":"false"}}}
{"@type":"update","object":"SpamSettings","value":{"enable":false}}
JSON
# The plan's own account lines, unedited, after a Domain entry its #dom resolves to.
{
  echo '{"@type":"upsert","object":"Domain","matchOn":["name"],"value":{"dom":{"name":"rig.test"}}}'
  grep -E '"(acct|acctquota|acctaliases)":' "$here/stalwart.plan.ndjson" | MAIL_USER=mailuser envsubst '${MAIL_USER}'
} > "$work/account.ndjson"
cat "$work/rig.ndjson" "$work/account.ndjson" | stalwart-cli apply --stdin >/dev/null 2>&1 || { echo "the rig plan did not apply" >&2; exit 1; }
"$engine" restart "$mail" >/dev/null
export STALWART_URL="http://127.0.0.1:$mail_port"
wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart never listened on $mail_port" >&2; exit 1; }
account="$(MAIL_USER=mailuser MAIL_DOMAIN=rig.test account_id)"

fail=0
check() { if [[ "$2" == "$3" ]]; then echo "  ok    $1"; else echo "  FAIL  $1 (got '$2')"; fail=1; fi; }
# Each alias as key=name:enabled, in key order.
aliases() {
  stalwart-cli get Account "$account" --fields aliases --json | python3 -c '
import json, sys
a = json.load(sys.stdin)["aliases"]
print(" ".join("%s=%s:%s" % (k, a[k]["name"], str(a[k]["enabled"]).lower()) for k in sorted(a, key=int)))'
}
patch_aliases() {
  stalwart-cli update Account "$account" --json "$1" >/dev/null || { echo "Stalwart refused the alias patch $1" >&2; exit 1; }
}
# The reply code to RCPT TO for each address, space-separated, from one polite session.
rcpt() {
  python3 -I - "$smtp_port" "$@" <<'PY'
import smtplib, sys
port, *addresses = sys.argv[1:]
with smtplib.SMTP("127.0.0.1", int(port)) as s:
    s.ehlo("outside.test"); s.mail("ada@outside.test")
    print(" ".join(str(s.rcpt(a)[0]) for a in addresses))
    s.rset(); s.quit()
PY
}
deliver() {
  python3 -I - "$smtp_port" "$1" "$2" <<'PY'
import smtplib, sys
port, to, subject = sys.argv[1:]
msg = (f"From: Ada Lovelace <ada@outside.test>\r\nTo: {to}\r\nSubject: {subject}\r\n"
       f"Message-ID: <{subject.replace(' ', '-')}@outside.test>\r\nDate: Sat, 10 Oct 2026 12:00:00 +0000\r\n\r\nhello\r\n")
with smtplib.SMTP("127.0.0.1", int(port)) as s:
    s.ehlo("outside.test"); s.sendmail("ada@outside.test", [to], msg); s.quit()
PY
}
# The first Delivered-To of the message with this subject, once it has arrived.
delivered_to() {
  local request header
  request="$(python3 -I -c '
import json, sys
account, subject = sys.argv[1:]
print(json.dumps({"using": ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"], "methodCalls": [
    ["Email/query", {"accountId": account, "filter": {"subject": subject}}, "q"],
    ["Email/get", {"accountId": account, "#ids": {"resultOf": "q", "name": "Email/query", "path": "/ids"},
                   "properties": ["header:Delivered-To:asText:all"]}, "g"]]}))' "$account" "$1")"
  for _ in $(seq 1 20); do
    header="$(jmap_call "$account" <<<"$request" | python3 -c '
import json, sys
emails = json.load(sys.stdin)["methodResponses"][1][1]["list"]
print(emails[0]["header:Delivered-To:asText:all"][0] if emails else "")')"
    [[ -n "$header" ]] && { echo "$header"; return; }
    sleep 0.5
  done
}

echo "== a fresh install"
check "the plan creates postmaster and abuse at keys 0 and 1" "$(aliases)" "0=postmaster:true 1=abuse:true"
check "both take mail" "$(rcpt postmaster@rig.test abuse@rig.test)" "250 250"

echo "== aliases added at runtime, then a redeploy"
patch_aliases "{\"aliases/2\":{\"name\":\"shop.k2mq7x4p\",\"domainId\":\"$domain_id\",\"description\":\"shop\"},\"aliases/3\":{\"name\":\"gone.a7b3c5d2\",\"domainId\":\"$domain_id\",\"enabled\":false}}"
stalwart-cli apply --file "$work/account.ndjson" >/dev/null 2>&1 || { echo "the second apply failed" >&2; exit 1; }
check "a second apply keeps runtime aliases, the disabled one disabled" "$(aliases)" \
  "0=postmaster:true 1=abuse:true 2=shop.k2mq7x4p:true 3=gone.a7b3c5d2:false"
check "an enabled alias takes mail and a disabled one is refused like an unknown address" \
  "$(rcpt shop.k2mq7x4p@rig.test gone.a7b3c5d2@rig.test nobody@rig.test)" "250 550 550"

echo "== delivery"
deliver shop.k2mq7x4p@rig.test "to the shop alias"
check "the delivered copy's Delivered-To names the alias" "$(delivered_to "to the shop alias")" "shop.k2mq7x4p@rig.test"

echo "== disabling and re-enabling"
patch_aliases '{"aliases/2/enabled":false}'
check "a disabled alias is refused at once, with no restart" "$(rcpt shop.k2mq7x4p@rig.test)" 550
patch_aliases '{"aliases/2/enabled":true}'
check "and taken again once re-enabled" "$(rcpt shop.k2mq7x4p@rig.test)" 250
check "disabling a key past the end is refused rather than creating a half alias" \
  "$( (patch_aliases '{"aliases/9/enabled":false}') 2>/dev/null && echo applied || echo refused)" refused
check "and nothing was added" "$(aliases)" "0=postmaster:true 1=abuse:true 2=shop.k2mq7x4p:true 3=gone.a7b3c5d2:false"

echo "== the catch-all verify.sh warns about"
check "a freshly bootstrapped domain has no catch-all" "$(catch_all_address rig.test)" ""
stalwart-cli update Domain "$domain_id" --json '{"catchAllAddress":"mailuser@rig.test"}' >/dev/null 2>&1
check "one set by hand is seen" "$(catch_all_address rig.test)" "mailuser@rig.test"
patch_aliases '{"aliases/2/enabled":false}'
check "and it takes mail for a disabled alias" "$(rcpt shop.k2mq7x4p@rig.test)" 250

exit "$fail"
