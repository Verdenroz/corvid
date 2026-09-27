#!/usr/bin/env bash
# Runs device.sh's add, list and revoke against a stubbed stalwart-cli and JMAP endpoint.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'kill "$server" 2>/dev/null; rm -rf "$work"' EXIT

# A canned response per POST to /jmap, one JSON object per line in "$work/responses", served
# in order. Each request received is appended to "$work/requests" for the tests to inspect.
python3 -c '
import http.server, os, socketserver
work = "'"$work"'"
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        open(work + "/requests", "a").write(body.decode() + "\n")
        served = int(open(work + "/served").read()) if os.path.exists(work + "/served") else 0
        responses = open(work + "/responses").read().splitlines()
        reply = responses[served] if served < len(responses) else "{}"
        open(work + "/served", "w").write(str(served + 1))
        data = reply.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def do_GET(self):
        status = int(open(work + "/download_status").read()) if os.path.exists(work + "/download_status") else 404
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()
    def log_message(self, *a): pass
s = socketserver.TCPServer(("127.0.0.1", 0), H)
open(work + "/port", "w").write(str(s.server_address[1]))
s.serve_forever()' &
server=$!
for _ in $(seq 1 50); do [[ -s "$work/port" ]] && break; sleep 0.1; done

mkdir -p "$work/bin"
cat > "$work/bin/stalwart-cli" <<CLI
#!/bin/sh
case "\$1 \$2" in
  "query Account") echo '{"id":"c1","emailAddress":"verde@example.com"}' ;;
  "get Account") cat "$work/credentials" ;;
esac
CLI
# The public-IP lookup, answered offline, so a "host" test does not need the internet.
cat > "$work/bin/curl" <<SH
#!/bin/sh
case "\$*" in *cdn-cgi/trace*) echo ip=203.0.113.9 ;; *) exec $(command -v curl) "\$@" ;; esac
SH
chmod +x "$work/bin/stalwart-cli" "$work/bin/curl"

cat > "$work/shell" <<'SH'
#!/bin/sh
printf 'MAIL_DOMAIN=example.com\nMAIL_HOSTNAME=mail.example.com\nMAIL_USER=verde\nACME_CONTACT=ops@elsewhere.example\nWEB_PORT=4443\n'
SH
chmod +x "$work/shell"
printf 'STALWART_URL=http://127.0.0.1:%s\nSTALWART_USER=admin\nSTALWART_PASSWORD=p\n' "$(cat "$work/port")" > "$work/corvid.env"
echo '{"credentials":{}}' > "$work/credentials"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }
# run CLIENT_SOURCES ARGS...
run() {
  local sources="$1"; shift
  rm -f "$work/requests" "$work/served"
  PATH="$work/bin:$PATH" CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
    CLIENT_SOURCES="$sources" bash "$here/device.sh" "$@" > "$work/out" 2> "$work/err"
  # shellcheck disable=SC2034  # read inside the eval'd checks
  status=$?
}
request() { sed -n "${1}p" "$work/requests"; }

echo "== add, client_access: anywhere"
printf '{"methodResponses":[["x:AppPassword/set",{"accountId":"c1","created":{"n0":{"id":"a1","secret":"app_test1"}}},"c0"]]}\n' \
  > "$work/responses"
run any add openair
check "exits 0" '[[ $status -eq 0 ]]'
check "the secret is the first line of stdout" '[[ "$(head -1 "$work/out")" == app_test1 ]]'
check "stdout carries nothing but the secret" '[[ $(wc -l < "$work/out") -eq 1 ]]'
check "the request carries no source restriction" 'request 1 | grep -q "\"allowedIps\": *{}"'
check "the request names the device" 'request 1 | grep -q "\"description\": *\"openair\""'
check "the note says unrestricted" 'grep -q "no source restriction" "$work/err"'

echo "== add, client_access: host"
run "" add tablet
check "exits 0" '[[ $status -eq 0 ]]'
check "the request restricts to the looked-up server IP" 'request 1 | grep -q "\"203.0.113.9\": *true"'
check "the note names the IP" 'grep -q "203.0.113.9" "$work/err"'

echo "== add, client_access: a network list"
run "192.0.2.0/24 198.51.100.7" add lan
check "exits 0" '[[ $status -eq 0 ]]'
check "both networks are in the request" \
  'request 1 | grep -q "192.0.2.0/24" && request 1 | grep -q "198.51.100.7"'

echo "== add rejects a name already in use"
echo '{"credentials":{"0":{"@type":"AppPassword","credentialId":"x","description":"phone","createdAt":"2024-01-01T00:00:00Z","allowedIps":{}}}}' \
  > "$work/credentials"
run any add phone
check "exits 65" '[[ $status -eq 65 ]]'
check "no request was sent" '[[ ! -s "$work/requests" ]]'
echo '{"credentials":{}}' > "$work/credentials"

echo "== add rejects a malformed name"
run any add "Not Valid"
check "exits 64" '[[ $status -eq 64 ]]'
check "no request was sent" '[[ ! -s "$work/requests" ]]'

echo "== add surfaces the server's reason and prints no secret"
printf '{"methodResponses":[["x:AppPassword/set",{"accountId":"c1","notCreated":{"n0":{"type":"overQuota","description":"You have exceeded your quota of 5 app passwords."}}}, "c0"]]}\n' \
  > "$work/responses"
run any add phone2
check "exits 1" '[[ $status -eq 1 ]]'
check "nothing was printed to stdout" '[[ ! -s "$work/out" ]]'
check "the reason reaches stderr" 'grep -q overQuota "$work/err"'

echo "== add refuses a response for the wrong account"
printf '{"methodResponses":[["x:AppPassword/set",{"accountId":"wrong","created":{"n0":{"id":"a1","secret":"app_leak"}}},"c0"]]}\n' \
  > "$work/responses"
run any add spoofed
check "exits nonzero" '[[ $status -ne 0 ]]'
check "the secret never reaches stdout" '! grep -q app_leak "$work/out"'
check "the mismatch is named" 'grep -q "does not match" "$work/err"'

echo "== list"
echo '{"credentials":{"0":{"@type":"Password"},"1":{"@type":"AppPassword","credentialId":"x","description":"phone","createdAt":"2024-01-01T00:00:00Z","allowedIps":{}},"2":{"@type":"AppPassword","credentialId":"y","description":"laptop","createdAt":"2024-01-02T00:00:00Z","allowedIps":{"198.51.100.9":true}}}}' \
  > "$work/credentials"
run any list
check "exits 0" '[[ $status -eq 0 ]]'
check "the Password entry is not listed" '! grep -q Password "$work/out"'
check "an unrestricted device shows any" 'grep -qP "^phone\t2024-01-01T00:00:00Z\tany$" "$work/out"'
check "a restricted device shows its address" 'grep -qP "^laptop\t2024-01-02T00:00:00Z\t198.51.100.9$" "$work/out"'
check "no secret ever appears" '! grep -q app_test1 "$work/out"'

echo '{"credentials":{"0":{"@type":"AppPassword","credentialId":"cred1","description":"phone","createdAt":"2024-01-01T00:00:00Z","allowedIps":{}}}}' \
  > "$work/credentials"

echo "== revoke an unknown device"
run any revoke ghost
check "exits 65" '[[ $status -eq 65 ]]'
check "no request was sent" '[[ ! -s "$work/requests" ]]'

echo "== revoke a device with no storage key file"
printf '%s\n' \
  '{"methodResponses":[["x:AppPassword/set",{"accountId":"c1","destroyed":["cred1"]},"d0"]]}' \
  '{"methodResponses":[["FileNode/query",{"accountId":"c1","ids":[]},"q0"]]}' \
  > "$work/responses"
run any revoke phone
check "exits 0" '[[ $status -eq 0 ]]'
check "only the credential and folder lookup were sent" '[[ $(wc -l < "$work/requests") -eq 2 ]]'
check "reports the credential revoked" 'grep -q "credential revoked" "$work/out"'
check "reports no key file to remove" 'grep -q "no storage key file found for phone" "$work/out"'
check "names how to revoke the VPN identity" 'grep -q rotate-vless-identity.sh "$work/out"'

echo "== revoke a device with a storage key file, blob confirmed gone"
printf '%s\n' \
  '{"methodResponses":[["x:AppPassword/set",{"accountId":"c1","destroyed":["cred1"]},"d0"]]}' \
  '{"methodResponses":[["FileNode/query",{"accountId":"c1","ids":["folder1"]},"q0"]]}' \
  '{"methodResponses":[["FileNode/query",{"accountId":"c1","ids":["file1"]},"q0"],["FileNode/get",{"accountId":"c1","list":[{"id":"file1","blobId":"blob1"}]},"g0"]]}' \
  '{"methodResponses":[["FileNode/set",{"accountId":"c1","destroyed":["file1"]},"d0"]]}' \
  > "$work/responses"
echo 404 > "$work/download_status"
run any revoke phone
check "exits 0" '[[ $status -eq 0 ]]'
check "all four lookups and destroys were sent" '[[ $(wc -l < "$work/requests") -eq 4 ]]'
check "reports the key file revoked and gone" \
  'grep -q "storage key file revoked, blob no longer downloads (status 404)" "$work/out"'

echo "== revoke refuses to call it done if the blob still downloads"
echo 200 > "$work/download_status"
run any revoke phone
check "exits 1" '[[ $status -eq 1 ]]'
check "says so rather than reporting success" 'grep -q "still downloads" "$work/err"'

[[ $fail -eq 0 ]] || { echo "--- last output:"; cat "$work/out"; echo "--- stderr:"; cat "$work/err"; }
exit "$fail"
