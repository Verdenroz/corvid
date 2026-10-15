#!/usr/bin/env bash
# Checks a running deployment from the outside: services, ports, firewall counters, DNS, TLS.
# A single test message can arrive while the firewall rules meant to carry it sit at zero
# packets, so this reads the counters instead of trusting one success.
set -uo pipefail

DOMAIN="${1:-}"
HOST="${2:-}"
[[ -n "$DOMAIN" ]] || { echo "usage: $(basename "$0") <mail-domain> [mail-hostname]" >&2; exit 64; }
HOST="${HOST:-$(dig +short MX "$DOMAIN" | awk '{print $2}' | sed 's/\.$//' | head -1)}"

ENV_FILE="${CORVID_ENV:-/opt/corvid/corvid.env}"
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/lib.sh"
[[ ! -r "$ENV_FILE" ]] || load_env "$ENV_FILE"
load_server
STALWART_CLI="${STALWART_CLI:-stalwart-cli}"

fail=0
stale=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*"; ((fail++)); }
warn() { echo "  warn  $*"; }
# A Worker install.sh deployed serves it, so no rollback can fix it; update.sh reads CORVID_DRIFT.
drift() {
  local line="$*; this host does not serve it, so run install.sh again to republish it"
  echo "  stale $line"
  ((stale++))
  [[ -z "${CORVID_DRIFT:-}" ]] || echo "$line" >> "$CORVID_DRIFT"
}
bad_if_here() { if [[ "$1" == true ]]; then bad "${*:2}"; else drift "${*:2}"; fi; }

echo "== services =="
services=(corvid-unbound corvid-mail corvid-milter corvid-imgproxy corvid-labeler corvid-scribe corvid-sentinel corvid-caddy)
# A service that started before the mail server exits 75 and sits "activating" through RestartSec.
deadline=$((SECONDS + 60))
while (( SECONDS < deadline )) && systemctl is-active "${services[@]/%/.service}" | grep -qx activating; do
  sleep 2
done
for s in "${services[@]}"; do
  if systemctl is-active --quiet "$s.service"; then
    ok "$s active"
  elif unit_masked "$s.service"; then
    warn "$s is masked, so off on purpose"
  else
    bad "$s is not running; check: journalctl -u $s"
  fi
done

echo "== rewriter =="
# A milter that accepts then closes passes a port check yet defers every message under
# tempFailOnError. Send the OPTNEG Stalwart offers and require an OPTNEG reply.
milter_negotiates() {
  exec 3<>/dev/tcp/127.0.0.1/11332 || return 1
  printf '\x00\x00\x00\x0dO\x00\x00\x00\x06\x00\x00\x00\xff\x00\x00\x00\x42' >&3
  local reply
  reply=$(timeout 4 head -c 17 <&3 | od -An -tx1 | tr -d ' \n')
  exec 3<&- 3>&-
  [[ "${reply:8:2}" == "4f" ]]
}
# The milter and image proxy listen on the pod's loopback, which the host cannot reach. A
# subshell keeps in_pod's exit from ending the report.
if (in_pod corvid-mail bash -c "$(declare -f milter_negotiates); milter_negotiates") 2>/dev/null; then
  ok "milter negotiates"
else
  bad "milter does not negotiate; every message will defer with 451"
fi
(in_pod corvid-mail curl -sf -o /dev/null --max-time 4 http://127.0.0.1:8081/healthz) 2>/dev/null \
  && ok "image proxy healthy" || bad "image proxy not responding"
if systemctl show -p Restart --value corvid-milter.service 2>/dev/null | grep -q always; then
  ok "milter restarts on failure"
else
  bad "corvid-milter is not set to Restart=always"
fi

echo "== web tier =="
# Pinned to the host's own loopback, where Caddy answers, as wait-issuer.sh does.
web_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 --resolve "$1:$WEB_PORT:127.0.0.1" "https://$1:$WEB_PORT$2"
}
code="$(web_status "$MAIL_HOSTNAME" /.well-known/corvid)"
[[ "$code" == 200 ]] \
  && ok "Caddy serves /.well-known/corvid on $MAIL_HOSTNAME:$WEB_PORT" \
  || bad "/.well-known/corvid on $MAIL_HOSTNAME:$WEB_PORT answers $code, not 200; check: journalctl -u corvid-caddy"

echo "== devices =="
# Through Caddy, the way the app reaches them. An unknown id answers 200 when the routes are on and
# 503 without sentinel.devices; a request Caddy does not route falls through to Stalwart's 404.
if unit_masked corvid-sentinel.service; then
  warn "corvid-sentinel is masked, so the device routes are not checked"
elif [[ -z "${STALWART_TOKEN:-}" ]]; then
  warn "STALWART_TOKEN is not set, so the device routes are not checked"
else
  code="$(curl_authed -s -o /dev/null -w '%{http_code}' --max-time 10 \
    --resolve "$MAIL_HOSTNAME:$WEB_PORT:127.0.0.1" "https://$MAIL_HOSTNAME:$WEB_PORT/corvid/devices/requests/probe")"
  case "$code" in
    200) ok "the device routes answer through Caddy" ;;
    503) bad "device approval is off: add sentinel.devices (state: /var/lib/corvid/devices.json) to config.yaml and restart corvid-sentinel" ;;
    *) bad "the device routes answer ${code:-no status} through Caddy, not 200; check: journalctl -u corvid-caddy and journalctl -u corvid-sentinel" ;;
  esac
fi

echo "== passkeys =="
# The issuer's documents are for Stalwart on loopback; Caddy must not route them, and a request it
# does not route falls through to Stalwart's 404.
if unit_masked corvid-sentinel.service; then
  warn "corvid-sentinel is masked, so the passkey issuer is not checked"
else
  for document in .well-known/openid-configuration jwks; do
    code="$(issuer_status "$document")"
    case "$code" in
      200) ok "the passkey issuer serves $document on loopback" ;;
      503) warn "passkey sign-in is off: add sentinel.auth (state: /var/lib/corvid/auth.json) to config.yaml and restart corvid-sentinel" ;;
      *) bad "the passkey issuer answers ${code:-no status} for $document on loopback, not 200; check: journalctl -u corvid-sentinel" ;;
    esac
  done
  for path in /internal/issuer/jwks /corvid/auth/jwks /internal/auth/status; do
    code="$(web_status "$MAIL_HOSTNAME" "$path")"
    [[ "$code" == 404 ]] \
      && ok "Caddy does not route $path" \
      || bad "$path answers $code through Caddy, not 404; only Stalwart on loopback may read the issuer"
  done
fi

echo "== app links =="
# Credential Manager reads this on 443 with no redirect, so it is fetched the way a phone would,
# through real DNS. Not served is a warning, since mail does not wait on the Android app; a
# document that differs, a redirect or the wrong type is a failure when the web tier serves it.
links_url="https://$MAIL_DOMAIN/.well-known/assetlinks.json"
expected="${ASSETLINKS:-}"
if [[ -z "$expected" ]]; then
  warn "corvid-config could not render the app links, so $links_url is not checked"
else
  verdict="$(assetlinks_verdict "$links_url" "$expected")"
  case "${verdict%% *}" in
    ok) ok "${verdict#* }" ;;
    warn) warn "${verdict#* }" ;;
    *) bad_if_here "$PUBLISH_ASSETLINKS" "${verdict#* }" ;;
  esac
fi

echo "== app release =="
if [[ ! -e "$CORVID_DIR/apps/current/manifest.json" ]]; then
  warn "no app release in this image"
elif [[ -z "${STALWART_TOKEN:-}" ]]; then
  warn "STALWART_TOKEN is not set, so the app release is not checked"
else
  code="$(curl_authed -s -o /dev/null -w '%{http_code}' --max-time 10 \
    --resolve "$MAIL_HOSTNAME:$WEB_PORT:127.0.0.1" "https://$MAIL_HOSTNAME:$WEB_PORT/corvid/app/manifest.json")"
  [[ "$code" == 200 ]] \
    && ok "the app release is served through Caddy" \
    || bad "the app release answers ${code:-no status} through Caddy, not 200; check: journalctl -u corvid-caddy"
fi

echo "== published ports =="
for p in 25 465 993; do
  ss -tlnH "sport = :$p" 2>/dev/null | grep -q . && ok "host publishes $p" || bad "nothing listens on $p"
done

echo "== firewall counters =="
# Under bridge networking mail rules live in the forward chain. Zero packets on one means
# traffic takes another path and breaks when rule ordering changes.
# Only the route rule bootstrap.sh adds for the container subnet carries Corvid's mail; copies
# of a port in other tables never see it.
counters=$(nft -a list ruleset 2>/dev/null | grep -E 'daddr 10\.88\.0\.0/16 tcp dport (25|465|993) .*counter' || true)
if [[ -z "$counters" ]]; then
  bad "no firewall route rules for the mail ports; rerun bootstrap.sh"
else
  for port in 25 465 993; do
    rules=$(grep -E "dport $port " <<<"$counters")
    if [[ $port != 25 && -z "$CLIENT_SOURCES" ]]; then
      [[ -z "$rules" ]] && ok "port $port is closed to the internet (client_access: host)" ||
        bad "port $port is open to the internet although client_access is host"
      continue
    fi
    [[ -n "$rules" ]] || { bad "port $port has no route rule, so nothing outside reaches it"; continue; }
    pkts=$(grep -oE 'packets [0-9]+' <<<"$rules" | awk '{s += $2} END {print s + 0}')
    if (( pkts > 0 )); then ok "port $port carried $pkts packets"
    else warn "port $port has carried no traffic yet (expected until something connects)"; fi
  done
fi

echo "== dns =="
[[ -n "$(dig +short MX "$DOMAIN")" ]]                  && ok "MX"    || bad "MX missing"
dig +short TXT "$DOMAIN" | grep -q 'v=spf1'            && ok "SPF"   || bad "SPF missing"
dig +short TXT "_dmarc.$DOMAIN" | grep -q 'v=DMARC1'   && ok "DMARC" || bad "DMARC missing"
echo "== dkim =="
if keys=$(active_dkim_keys "$DOMAIN"); then
  grep -q ' Dkim1RsaSha256 ' <<<"$keys" && ok "an active RSA key signs outbound mail" || bad "no active RSA DKIM key"
  if grep -q ' Dkim1Ed25519Sha256 ' <<<"$keys"; then
    bad "an active ed25519 key signs outbound mail; Gmail does not validate it"
  else
    ok "no active ed25519 key"
  fi
  while read -r id _ selector; do
    [[ -n "$id" ]] || continue
    key=$(dkim_public_key "$id") || { bad "DKIM $selector: could not read its public key from Stalwart"; continue; }
    published=$(dig +short TXT "$selector._domainkey.$DOMAIN" | tr -d '" ' | sed -n 's/.*p=\([^;]*\).*/\1/p')
    [[ "$published" == "$key" ]] && ok "DKIM $selector matches the key Stalwart signs with" ||
      bad "DKIM $selector: DNS does not publish the key Stalwart signs with"
  done <<<"$keys"
else
  bad "could not read the DKIM keys from Stalwart"
fi
if reports=$($STALWART_CLI query DmarcExternalReport --json); then
  report=$(python3 -c '
import json, sys
google = [r for r in map(json.loads, filter(str.strip, sys.stdin)) if "google.com" in r["from"]]
if google:
    print(max(google, key=lambda r: r["receivedAt"])["id"])' <<<"$reports") || report=error
else
  report=error
fi
if [[ "$report" == error ]]; then
  bad "could not read the DMARC reports from Stalwart"
elif [[ -z "$report" ]]; then
  echo "  info  no Google DMARC report yet"
elif summary=$($STALWART_CLI get DmarcExternalReport "$report" --json | SERVER_IP="$(server_ip)" python3 -c '
import json, os, sys
r = json.load(sys.stdin)["report"]
ours = [x for x in r["records"].values() if x["sourceIp"] == os.environ["SERVER_IP"]]
failed = sum(1 for x in ours if "pass" not in (x["evaluatedDkim"], x["evaluatedSpf"]))
print(r["dateRangeBegin"], len(ours), failed)') && read -r begin total failed <<<"$summary"; then
  echo "  info  newest Google DMARC report starts $begin: $total record(s) from this server"
  (( failed == 0 )) && ok "no DMARC failure in that report" || bad "$failed record(s) from this server failed DMARC in that report"
else
  bad "could not read DMARC report $report"
fi
echo "== dnssec-validating resolver (required for DANE) =="
out=$(dig @127.0.0.1 -p 5335 +dnssec +noall +comments cloudflare.com A 2>/dev/null)
if grep -q 'status: NOERROR' <<<"$out" && grep -qE 'flags:[^;]* ad' <<<"$out"; then
  ok "unbound resolves and sets the AD flag"
else
  bad "unbound is not validating DNSSEC, so Stalwart keeps DANE off"
fi

echo "== inbound tls: dane and mta-sts =="
tlsa=$(dig @127.0.0.1 -p 5335 +dnssec +noall +comments +answer TLSA "_25._tcp.$HOST" 2>/dev/null)
if ! grep -q 'TLSA' <<<"$(grep -v '^;' <<<"$tlsa")"; then
  warn "no TLSA records for $HOST, so DANE is off"
elif grep -qE 'flags:[^;]* ad' <<<"$tlsa"; then
  ok "DANE: the TLSA records for $HOST validate"
else
  warn "$HOST has TLSA records, but senders ignore them until its zone is signed (DNSSEC on, DS record at the registrar)"
fi
# Asked of the zone's own nameserver: a resolver can hold a removed record until its TTL runs out.
nameserver=$(dig +short NS "$DOMAIN" | head -1)
if dig +short TXT "_mta-sts.$DOMAIN" "@${nameserver:-1.1.1.1}" | grep -q 'v=STSv1'; then
  policy=$(curl -s --max-time 10 "https://mta-sts.$DOMAIN/.well-known/mta-sts.txt")
  grep -q '^version: STSv1' <<<"$policy" && ok "MTA-STS policy served at mta-sts.$DOMAIN" ||
    bad_if_here "$PUBLISH_MTA_STS" "MTA-STS is published, but https://mta-sts.$DOMAIN/.well-known/mta-sts.txt does not serve the policy"
else
  ok "MTA-STS not published (Corvid publishes it only when the web tier holds 443)"
fi

echo "== ptr / fcrdns =="
if [[ -n "$HOST" ]]; then
  ip=$(dig +short A "$HOST" | head -1)
  ptr=$(dig +short -x "$ip" 2>/dev/null | sed 's/\.$//' | head -1)
  [[ "$ptr" == "$HOST" ]] && ok "PTR matches $HOST" || bad "PTR is '${ptr:-none}', expected $HOST"
fi

echo "== outbound port 25 =="
if timeout 8 bash -c 'exec 3<>/dev/tcp/gmail-smtp-in.l.google.com/25' 2>/dev/null; then
  ok "port 25 egress open"
else
  bad "outbound port 25 blocked; ask your provider to open it"
fi

echo "== tls =="
if [[ -n "$HOST" ]]; then
  out=$(timeout 12 openssl s_client -connect "$HOST:465" -servername "$HOST" -verify_return_error </dev/null 2>&1)
  grep -q "Verify return code: 0" <<<"$out" && ok "certificate chain verifies" || bad "certificate does not verify against trusted roots"
else
  warn "no mail hostname resolved; skipping TLS check"
fi

echo
[[ $fail -eq 0 ]] || { echo "$fail check(s) failed"; exit 1; }
[[ $stale -eq 0 ]] && echo "PASS" || echo "PASS, but $stale thing(s) published off this host are out of date"
