#!/usr/bin/env bash
# Confirms Pocket-ID's APP_URL (lib.sh's render_units) and the OIDC issuer (apply-plan.sh
# rendering oidc.plan.ndjson) come out byte-identical from the same IDP_HOSTNAME and WEB_PORT.
# A mismatch fails login only at the first attempt (see RUNBOOK.md), so nothing else catches it.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../lib.sh
. "$here/lib.sh"
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
[ "\$1" = apply ] && cat > "$work/applied"
exit 0
SH
chmod +x "$work/cli"
mkdir -p "$work/bin"
cat > "$work/bin/curl" <<SH
#!/bin/sh
case "\$*" in *cdn-cgi/trace*) echo ip=203.0.113.9 ;; *) exec $(command -v curl) "\$@" ;; esac
SH
chmod +x "$work/bin/curl"
mkdir -p "$work/secrets" && echo token > "$work/secrets/cloudflare.token"
printf 'STALWART_URL=http://127.0.0.1:%s\nSTALWART_USER=admin\nSTALWART_PASSWORD=p\n' \
  "$(cat "$work/port")" > "$work/corvid.env"

printf '#!/bin/sh\nprintf "MAIL_DOMAIN=corvid.example\\nMAIL_HOSTNAME=n1.relay.example\\nMAIL_USER=alice\\nACME_CONTACT=ops@elsewhere.example\\nWEB_PORT=4443\\nHOST_ZONE=\\nIDP_HOSTNAME=idp.corvid.example\\nPUBLISH_MTA_STS=false\\nPUBLISH_TLSA=true\\nMTA_STS_MODE=testing\\n"\n' \
  > "$work/shell"
chmod +x "$work/shell"

PATH="$work/bin:$PATH" CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
  STALWART_CLI="$work/cli" CORVID_SKIP_RELOAD=1 PLAN="$here/oidc.plan.ndjson" \
  bash "$here/apply-plan.sh" > "$work/out" 2>&1
# shellcheck disable=SC2034  # read inside the eval'd checks
status=$?

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }
check "apply-plan.sh exits 0" '[[ $status -eq 0 ]]'

# shellcheck disable=SC2034  # read inside the eval'd checks
issuer="$(grep -o '"issuerUrl":"[^"]*"' "$work/applied" | cut -d'"' -f4)"

MAIL_HOSTNAME=n1.relay.example MAIL_DOMAIN=corvid.example WEB_PORT=4443 IDP_HOSTNAME=idp.corvid.example \
  PUBLISH_MTA_STS=false PUBLISH_ASSETLINKS=false ISSUER_URL=http://127.0.0.1:11333/internal/issuer CORVID_DIR="$work" CORVID_IMAGE=localhost/corvid:latest \
  render_units "$work/units" "$here"
# shellcheck disable=SC2034  # read inside the eval'd checks
app_url="$(awk -F= '$1=="APP_URL"{print $2}' "$work/server.env")"

check "server.env's APP_URL equals the plan's issuerUrl" '[[ -n "$app_url" && "$app_url" == "$issuer" ]]'
check "neither carries a path" '[[ "$app_url" != */idp && "$issuer" != */idp ]]'

[[ $fail -eq 0 ]] || { echo "--- applied:"; cat "$work/applied" 2>/dev/null; echo "--- server.env:"; cat "$work/server.env" 2>/dev/null; echo "--- out:"; cat "$work/out"; }
exit "$fail"
