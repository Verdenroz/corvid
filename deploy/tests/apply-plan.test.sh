#!/usr/bin/env bash
# Runs apply-plan.sh against a stubbed stalwart-cli and account endpoint, and checks the plan it
# sends: the host-zone Domain rides along only when server.host_zone is set.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'kill "$server" 2>/dev/null; rm -rf "$work"' EXIT

python3 -c '
import http.server, socketserver
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self): self.send_response(200); self.end_headers()
    def log_message(self, *a): pass
s = socketserver.TCPServer(("127.0.0.1", 0), H)
open("'"$work"'/port", "w").write(str(s.server_address[1]))
s.serve_forever()' &
server=$!
for _ in $(seq 1 50); do [[ -s "$work/port" ]] && break; sleep 0.1; done

cat > "$work/cli" <<SH
#!/bin/sh
case "\$1 \$2" in
  "apply --stdin") cat > "$work/applied" ;;
  "query Domain") echo '{"id":"b","name":"corvid.example"}' ;;
  "query DkimSignature") cat "$work/keys" ;;
  update*) echo "\$*" >> "$work/updates" ;;
esac
exit 0
SH
chmod +x "$work/cli"
# Answers the public-IP lookup offline, so the test covers the path that finds SERVER_IP.
mkdir -p "$work/bin"
cat > "$work/bin/curl" <<SH
#!/bin/sh
case "\$*" in *cdn-cgi/trace*) echo ip=203.0.113.9 ;; *) exec $(command -v curl) "\$@" ;; esac
SH
chmod +x "$work/bin/curl"
mkdir -p "$work/secrets" && echo token > "$work/secrets/cloudflare.token"
printf 'STALWART_URL=http://127.0.0.1:%s\nSTALWART_USER=admin\nSTALWART_PASSWORD=p\n' \
  "$(cat "$work/port")" > "$work/corvid.env"

cat > "$work/keys" <<'KEYS'
{"id":"e1","domainId":"b","selector":"v1-ed","publicKey":"PK","stage":"active","@type":"Dkim1Ed25519Sha256"}
{"id":"r1","domainId":"b","selector":"v1-rsa","publicKey":"PK","stage":"active","@type":"Dkim1RsaSha256"}
{"id":"e2","domainId":"b","selector":"v0-ed","publicKey":"PK","stage":"retiring","@type":"Dkim1Ed25519Sha256"}
{"id":"e3","domainId":"c","selector":"v1-ed","publicKey":"PK","stage":"active","@type":"Dkim1Ed25519Sha256"}
KEYS

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }
# run HOST_ZONE [PUBLISH_MTA_STS] [MTA_STS_MODE]
run() {
  printf '#!/bin/sh\nprintf "MAIL_DOMAIN=corvid.example\\nMAIL_HOSTNAME=n1.relay.example\\nMAIL_USER=alice\\nACME_CONTACT=ops@elsewhere.example\\nWEB_PORT=4443\\nHOST_ZONE=%s\\nPUBLISH_MTA_STS=%s\\nPUBLISH_TLSA=%s\\nMTA_STS_MODE=%s\\n"\n' \
    "$1" "${2:-false}" "$([[ -n "$1" ]] && echo false || echo true)" "${3:-testing}" > "$work/shell"
  chmod +x "$work/shell"; rm -f "$work/applied" "$work/updates"
  PATH="$work/bin:$PATH" CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
    STALWART_CLI="$work/cli" CORVID_SKIP_RELOAD=1 bash "$here/apply-plan.sh" > "$work/out" 2>&1
  # shellcheck disable=SC2034  # read inside the eval'd checks
  status=$?
}

mta_sts_mode() {
  python3 -c '
import json, sys
for line in open(sys.argv[1]):
    op = json.loads(line)
    if op.get("object") == "MtaSts":
        print(op["value"]["mode"])' "$work/applied"
}

dkim_algorithms() {
  python3 -c '
import json, sys
for line in open(sys.argv[1]):
    op = json.loads(line)
    for value in op["value"].values() if op.get("object") == "Domain" else []:
        if value.get("name") == "corvid.example":
            print(json.dumps(value["dkimManagement"]["algorithms"], sort_keys=True))' "$work/applied"
}

sign_domain() {
  python3 -c '
import json, sys
for line in open(sys.argv[1]):
    op = json.loads(line)
    if op.get("object") == "SenderAuth":
        print(json.dumps(op["value"]["dkimSignDomain"], sort_keys=True))' "$work/applied"
}

mail_records() {
  python3 -c '
import json, sys
for line in open(sys.argv[1]):
    op = json.loads(line)
    for value in op["value"].values() if op.get("object") == "Domain" else []:
        if value.get("name") == "corvid.example":
            print(json.dumps(value["dnsManagement"]["publishRecords"], sort_keys=True))' "$work/applied"
}

echo "== with a host zone"
run relay.example
check "exits 0" '[[ $status -eq 0 ]]'
check "the host-zone Domain is applied" 'grep -q "\"name\":\"relay.example\"" "$work/applied"'
check "the mail Domain is applied" 'grep -q "\"name\":\"corvid.example\"" "$work/applied"'
check "no template is left unfilled" '! grep -q "\${" "$work/applied"'
check "the server's own address is exempt from bans" 'grep -q "\"object\":\"AllowedIp\".*\"address\":\"203.0.113.9\"" "$work/applied"'

check "the mail domain publishes no TLSA, which the host zone holds" 'mail_records | grep -q "\"tlsa\": false"'
check "the mail domain publishes no MTA-STS off 443" 'mail_records | grep -q "\"mtaSts\": false"'
check "Stalwart's own MtaSts singleton is set to testing" '[[ "$(mta_sts_mode)" == testing ]]'

echo "== DKIM signing"
check "only RSA keys are generated" '[[ "$(dkim_algorithms)" == "{\"Dkim1RsaSha256\": true}" ]]'
check "only authenticated mail from a local domain is signed" '[[ "$(sign_domain)" == "{\"else\": \"false\", \"match\": {\"0\": {\"if\": \"is_local_domain(sender_domain) && !is_empty(authenticated_as)\", \"then\": \"sender_domain\"}}}" ]]'
check "the active ed25519 key is retired" 'grep -q "^update DkimSignature e1 .*\"stage\":\"retiring\"" "$work/updates"'
check "no RSA, other-domain or already-retiring key is touched" '[[ $(wc -l < "$work/updates") -eq 1 ]]'

echo "== without one"
run ""
check "exits 0" '[[ $status -eq 0 ]]'
check "no host-zone Domain" '[[ $(grep "\"object\":\"Domain\"" "$work/applied" | grep -vc "keys\.corvid\.example") -eq 1 ]]'
check "the keys account's domain is applied disabled" 'grep "keys.corvid.example" "$work/applied" | grep -q "\"isEnabled\":false"'
check "the mail domain publishes TLSA" 'mail_records | grep -q "\"tlsa\": true"'

echo "== with the web tier on 443"
run "" true
check "the mail domain publishes MTA-STS" 'mail_records | grep -q "\"mtaSts\": true"'

echo "== with mta_sts_mode: enforce"
run "" true enforce
check "Stalwart's own MtaSts singleton is set to enforce" '[[ "$(mta_sts_mode)" == enforce ]]'

[[ $fail -eq 0 ]] || { echo "--- last output:"; cat "$work/out"; }
exit "$fail"
