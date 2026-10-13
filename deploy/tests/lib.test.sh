#!/usr/bin/env bash
# Tests deploy/lib.sh's curl_authed, which must never put the password in curl's argv.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/curl" <<'SH'
#!/bin/sh
printf '%s\n' "$@" >> "$WORK/argv"
prev=""
for arg in "$@"; do
  [ "$prev" = "--config" ] && cat "$arg" >> "$WORK/config-content"
  prev="$arg"
done
SH
chmod +x "$work/bin/curl"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

STALWART_USER=admin@example.com STALWART_PASSWORD=SuperSecretQx7Lp2Vn WORK="$work" \
  PATH="$work/bin:$PATH" bash -c ". \"$here/lib.sh\"; curl_authed -fsS https://example.test/"

check "the password never appears in curl's argv" '! grep -qF SuperSecretQx7Lp2Vn "$work/argv"'
check "the flags passed through reached curl" 'grep -qx -- "-fsS" "$work/argv"'
check "the URL passed through reached curl" 'grep -qx "https://example.test/" "$work/argv"'
check "the password reaches curl through the config file instead" \
  'grep -qF SuperSecretQx7Lp2Vn "$work/config-content"'

echo "== a password carrying a quote and a backslash"
: > "$work/config-content"
tricky='p@ss"w\ord'
STALWART_USER=admin@example.com STALWART_PASSWORD="$tricky" WORK="$work" \
  PATH="$work/bin:$PATH" bash -c ". \"$here/lib.sh\"; curl_authed -fsS https://example.test/"
check "the quote and backslash are escaped for curl's config syntax" \
  'grep -qF '\''p@ss\"w\\ord'\'' "$work/config-content"'

echo "== a token set beside a password"
: > "$work/argv"; : > "$work/config-content"
STALWART_TOKEN=API_tokenQx7Lp2Vn STALWART_USER=admin@example.com STALWART_PASSWORD=SuperSecretQx7Lp2Vn WORK="$work" \
  PATH="$work/bin:$PATH" bash -c ". \"$here/lib.sh\"; curl_authed -fsS https://example.test/"
check "the token never appears in curl's argv" '! grep -qF API_tokenQx7Lp2Vn "$work/argv"'
check "the token reaches curl as a Bearer header through the config file" \
  'grep -qF "Authorization: Bearer API_tokenQx7Lp2Vn" "$work/config-content"'
check "the password is not sent" '! grep -qF SuperSecretQx7Lp2Vn "$work/config-content"'
printf 'STALWART_TOKEN=t\nSTALWART_USER=u\nSTALWART_PASSWORD=p\n' > "$work/corvid.env"
check "load_env drops the password pair when the file holds a token" \
  '[[ "$(bash -c ". \"$here/lib.sh\"; load_env \"$work/corvid.env\"; echo \"\$STALWART_TOKEN\${STALWART_USER:-}\${STALWART_PASSWORD:-}\"")" == t ]]'

echo "== server_set_up"
set_up() {
  printf '%s\n' "$@" > "$work/corvid.env"
  bash -c ". \"$here/lib.sh\"; load_env \"$work/corvid.env\"; server_set_up"
}
check "a token alone counts as set up" 'set_up STALWART_TOKEN=t'
check "a password alone counts as set up" 'set_up STALWART_USER=u STALWART_PASSWORD=p'
check "a token and a password count as set up" 'set_up STALWART_TOKEN=t STALWART_USER=u STALWART_PASSWORD=p'
check "neither counts as not set up" '! set_up RECOVERY_ADMIN_USER=r'

echo "== auth_decision"
decide() {
  AUTH_STATUS_TRIES=2 AUTH_STATUS_WAIT=0 bash -c ". \"$here/lib.sh\"; sentinel_signed() { printf '%b' \"\$REPLY_FROM_SENTINEL\"; }; auth_decision"
}
export REPLY_FROM_SENTINEL
REPLY_FROM_SENTINEL='200\n{"approvers":1,"codes":[],"passkeys":[{"id":"a"},{"id":"b"}]}\n'
check "an approver switches to passkeys" '[[ "$(decide)" == "passkeys 2 1 0" ]]'
REPLY_FROM_SENTINEL='200\n{"approvers":0,"codes":[{"approves":true,"expiresAt":"x"},{"approves":false,"expiresAt":"x"}],"passkeys":[{"id":"a"}]}\n'
check "passkeys without an approver stay with Pocket-ID and count the approver codes waiting" '[[ "$(decide)" == "pocket-id 1 0 1" ]]'
REPLY_FROM_SENTINEL='200\n{"approvers":0,"codes":[],"passkeys":[]}\n'
check "a fresh sentinel stays with Pocket-ID with no code waiting" '[[ "$(decide)" == "pocket-id 0 0 0" ]]'
REPLY_FROM_SENTINEL='503\n{"error":"auth_off"}\n'
check "passkeys switched off in the sentinel is off, not an error" '[[ "$(decide)" == "off 0 0 0" ]]'
REPLY_FROM_SENTINEL='000\n\n'
check "no answer from the sentinel is a failure that prints nothing" 'out="$(decide)"; [[ $? -ne 0 && -z "$out" ]]'
REPLY_FROM_SENTINEL='502\nbad gateway\n'
check "any other status is a failure too" 'out="$(decide)"; [[ $? -ne 0 && -z "$out" ]]'

echo "== assetlinks_verdict"
python3 -c '
import http.server, socketserver
DOC = b"[{\"relation\": []}]"
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/ok": self.send_response(200); self.send_header("Content-Type", "application/json"); body = DOC
        elif self.path == "/other": self.send_response(200); self.send_header("Content-Type", "application/json"); body = b"[]"
        elif self.path == "/text": self.send_response(200); self.send_header("Content-Type", "text/plain"); body = DOC
        elif self.path == "/moved": self.send_response(301); self.send_header("Location", "/ok"); body = b""
        else: self.send_response(404); body = b""
        self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
s = socketserver.TCPServer(("127.0.0.1", 0), H)
open("'"$work"'/port", "w").write(str(s.server_address[1]))
s.serve_forever()' &
server=$!
trap 'kill "$server" 2>/dev/null; rm -rf "$work"' EXIT
for _ in $(seq 1 50); do [[ -s "$work/port" ]] && break; sleep 0.1; done
doc='[{"relation": []}]'
verdict() { bash -c ". \"$here/lib.sh\"; assetlinks_verdict \"\$1\" \"\$2\"" _ "http://127.0.0.1:$(cat "$work/port")$1" "$doc"; }
check "the expected document as JSON is ok" '[[ "$(verdict /ok)" == ok* ]]'
check "a different document is bad" '[[ "$(verdict /other)" == bad* ]]'
check "the right document with the wrong type is bad" '[[ "$(verdict /text)" == bad*"not application/json"* ]]'
check "a redirect is bad even though it leads to the document" '[[ "$(verdict /moved)" == bad*redirect* ]]'
check "not served is only a warning" '[[ "$(verdict /absent)" == warn*404* ]]'
check "an address nothing listens on is only a warning" \
  '[[ "$(bash -c ". \"$here/lib.sh\"; assetlinks_verdict http://127.0.0.1:1/x \"\$1\"" _ "$doc")" == warn* ]]'

echo "== no other script embeds the password in curl's argv"
hits="$(grep -l 'curl.*-u.*STALWART_PASSWORD' "$here"/*.sh 2>/dev/null | grep -v '/lib\.sh$' || true)"
check "grep finds no such call site" '[[ -z "$hits" ]]'

echo "== every deploy script that is run rather than sourced is executable"
unexecutable="$(find "$here" -maxdepth 1 -name '*.sh' ! -name '*lib.sh' ! -perm -u+x)"
check "find lists no such script" '[[ -z "$unexecutable" ]]'

[[ $fail -eq 0 ]] || { echo "--- argv:"; cat "$work/argv"; echo "--- hits: $hits"; echo "--- not executable: $unexecutable"; }
exit "$fail"
