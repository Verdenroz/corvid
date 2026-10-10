#!/usr/bin/env bash
# Runs bootstrap-server.sh against a stubbed stalwart-cli and health endpoint. The secret must
# reach corvid.env and never the terminal, which is where it would outlive the session.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'kill "$server" 2>/dev/null; rm -rf "$work"' EXIT
secret=TestSecretQx7Lp2Vn9Wk4

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

cat > "$work/shell" <<'SH'
#!/bin/sh
printf 'MAIL_DOMAIN=example.com\nMAIL_HOSTNAME=mail.example.com\nMAIL_USER=alice\nACME_CONTACT=ops@elsewhere.example\nWEB_PORT=4443\n'
SH

fail=0
run() {
  local response="$1"
  printf '#!/bin/sh\ntouch "%s/cli-called"\nprintf %%s "%s"\n' "$work" "$response" > "$work/cli"
  chmod +x "$work/cli" "$work/shell"
  rm -f "$work/bootstrap-response" "$work/cli-called"
  CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
    STALWART_CLI="$work/cli" bash "$here/bootstrap-server.sh" > "$work/out" 2>&1
  # shellcheck disable=SC2034  # read inside the eval'd checks
  status=$?
}
fresh_env() {
  printf 'STALWART_URL=http://127.0.0.1:%s\nRECOVERY_ADMIN_USER=recovery\nRECOVERY_ADMIN_PASSWORD=r\n' \
    "$(cat "$work/port")" > "$work/corvid.env"
}
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

echo "== a parseable response"
fresh_env
run "Updated Bootstrap singleton\n\n  username: \"admin@example.com\"\n  secret: \"$secret\"\n"
check "exits 0" '[[ $status -eq 0 ]]'
check "the secret is not printed" '! grep -q "$secret" "$work/out"'
check "the secret reaches corvid.env" 'grep -qx "STALWART_PASSWORD=$secret" "$work/corvid.env"'

echo "== an unparseable response"
fresh_env
run "Updated Bootstrap singleton\n\n  unexpected: \"$secret\"\n"
check "exits 1" '[[ $status -eq 1 ]]'
check "the response is not printed" '! grep -q "$secret" "$work/out"'
check "the response is kept, readable only by its owner" \
  '[[ "$(stat -c %a "$work/bootstrap-response" 2>/dev/null)" == 600 ]] && grep -q "$secret" "$work/bootstrap-response"'
check "the message names the file" 'grep -q "$work/bootstrap-response" "$work/out"'

echo "== a token and no password"
fresh_env
echo STALWART_TOKEN=t >> "$work/corvid.env"
run "unused"
check "exits 0" '[[ $status -eq 0 ]]'
check "never calls the CLI" '[[ ! -e "$work/cli-called" ]]'

echo "== a second run after a bootstrap and an API key"
fresh_env
run "Updated Bootstrap singleton\n\n  username: \"admin@example.com\"\n  secret: \"$secret\"\n"
echo STALWART_TOKEN=t >> "$work/corvid.env"
cp "$work/corvid.env" "$work/before"
run "unused"
check "exits 0" '[[ $status -eq 0 ]]'
check "never calls the CLI" '[[ ! -e "$work/cli-called" ]]'
check "leaves corvid.env byte for byte unchanged" 'cmp -s "$work/before" "$work/corvid.env"'

[[ $fail -eq 0 ]] || { echo "--- last output:"; cat "$work/out"; }
exit "$fail"
