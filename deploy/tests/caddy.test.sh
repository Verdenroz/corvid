#!/usr/bin/env bash
# Runs the real Caddyfile in the pinned Caddy image against stub upstreams on the loopback ports
# it names. Confirms it parses, and that /corvid/* reaches the sentinel only with a credential
# Stalwart accepts, and serves the
# discovery document without one. The stub answers a credential-less /jmap/session with 200, as v0.16.21 does.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
image="$(awk -F= '$1=="Image"{print $2}' "$here/corvid-caddy.container")"
engine="$(command -v podman || command -v docker)"
port=18443
work="$(mktemp -d)"
name="corvid-caddy-test-$$"
trap '"$engine" rm -f "$name" >/dev/null 2>&1; kill "$stubs" 2>/dev/null; rm -rf "$work"' EXIT

python3 -c '
import http.server, socketserver, threading
class Stalwart(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        auth = self.headers.get("Authorization")
        if self.path != "/jmap/session": code = 404
        elif auth is None or auth == "Basic Z29vZDpnb29k": code = 200
        else: code = 401
        self.send_response(code); self.end_headers(); self.wfile.write(b"stalwart")
    do_POST = do_GET
    def log_message(self, *a): pass
class Sentinel(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"sentinel")
    do_POST = do_GET
    def log_message(self, *a): pass
class Idp(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"idp")
    do_POST = do_GET
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
for port, handler in ((8080, Stalwart), (11333, Sentinel), (1411, Idp)):
    threading.Thread(target=socketserver.TCPServer(("127.0.0.1", port), handler).serve_forever).start()
' &
stubs=$!

mkdir -p "$work/apps/current"
printf 'manifest' > "$work/apps/current/manifest.json"
chmod -R a+rX "$work/apps"

# Rootless containers cannot bind 80, where Caddy puts its HTTP-to-HTTPS redirect.
{ printf '{\n\thttp_port 18080\n}\n\n'; cat "$here/Caddyfile"; } > "$work/Caddyfile"
"$engine" run -d --name "$name" --network host \
  -e CORVID_WEB_HOSTNAME=localhost -e CORVID_WEB_PORT="$port" -e CORVID_MTA_STS_SITE= \
  -e CORVID_MAIL_DOMAIN=localhost -e CORVID_IDP_HOSTNAME=idp.localhost \
  -v "$work/Caddyfile:/etc/caddy/Caddyfile:ro,z" -v "$work/apps:/srv/corvid-apps:ro,z" "$image" >/dev/null

fetch() {
  curl -sk --max-time 5 -o "$work/body" -w '%{http_code}' --resolve "localhost:$port:127.0.0.1" --resolve "idp.localhost:$port:127.0.0.1" \
    "$@" 2>/dev/null
  printf ' %s' "$(cat "$work/body" 2>/dev/null)"
}
for _ in $(seq 1 50); do [[ "$(fetch "https://localhost:$port/jmap/session")" == "200 stalwart" ]] && break; sleep 0.2; done

fail=0
check() { if [[ "$2" == "$3" ]]; then echo "  ok    $1"; else echo "  FAIL  $1 (got '$2')"; fail=1; fi; }
url="https://localhost:$port"
check "Caddy loads the Caddyfile" "$("$engine" inspect -f '{{.State.Running}}' "$name")" true
for path in /corvid/update /corvid/notices; do
  check "$path without a credential stays with Stalwart" "$(fetch "$url$path")" "404 stalwart"
  check "$path POST without a credential stays with Stalwart" "$(fetch -X POST -d '{}' "$url$path")" "404 stalwart"
  check "$path with a refused credential gets Stalwart's 401" "$(fetch -u bad:bad "$url$path")" "401 stalwart"
  check "$path with an accepted credential reaches the sentinel" "$(fetch -u good:good "$url$path")" "200 sentinel"
done
for path in /corvid/devices/requests /corvid/devices/requests/abc /corvid/devices/requests/abc/approve /corvid/devices/phone; do
  check "$path without a credential stays with Stalwart" "$(fetch -X POST -d '{}' "$url$path")" "404 stalwart"
  check "$path with a refused credential gets Stalwart's 401" "$(fetch -X POST -d '{}' -u bad:bad "$url$path")" "401 stalwart"
  check "$path with an accepted credential reaches the sentinel" "$(fetch -X POST -d '{}' -u good:good "$url$path")" "200 sentinel"
done
check "the host-only revoke route is never routed to the sentinel" \
  "$(fetch -X POST -d '{}' -u good:good "$url/internal/devices/phone")" "404 stalwart"
check "an app file without a credential stays with Stalwart" "$(fetch "$url/corvid/app/manifest.json")" "404 stalwart"
check "an app file with a refused credential gets Stalwart's 401" "$(fetch -u bad:bad "$url/corvid/app/manifest.json")" "401 stalwart"
check "an app file with an accepted credential is served" "$(fetch -u good:good "$url/corvid/app/manifest.json")" "200 manifest"
check "a missing app file is a 404" "$(fetch -u good:good "$url/corvid/app/absent.apk")" "404 "
doc='{"version":1,"server":"https://localhost:'"$port"'","issuer":"https://idp.localhost:'"$port"'"}'
check "the discovery document names the web tier and the issuer" "$(fetch "$url/.well-known/corvid")" "200 $doc"
check "the discovery document ignores a credential" "$(fetch -u bad:bad "$url/.well-known/corvid")" "200 $doc"
check "the discovery document is JSON" "$(curl -sk --max-time 5 -o /dev/null -w '%{content_type}' --resolve "localhost:$port:127.0.0.1" "$url/.well-known/corvid")" application/json
check "everything else goes to Stalwart" "$(fetch "$url/jmap/session")" "200 stalwart"
idp="https://idp.localhost:$port"
check "the identity provider's site reaches Pocket-ID" "$(fetch "$idp/api/users")" "200 idp"
for path in /setup /setup/ /api/signup/setup /api/signup/setup/ /login/alternative/device /login/alternative/device/x \
  /api/device-login/requests /api/device-login/requests/x/exchange /api/device-login/verification; do
  check "$path on the identity provider's site is a 404" "$(fetch "$idp$path")" "404 "
  check "$path POST on the identity provider's site is a 404" "$(fetch -X POST -d '{}' "$idp$path")" "404 "
done

[[ $fail -eq 0 ]] || "$engine" logs "$name" 2>&1 | tail -20
exit "$fail"
