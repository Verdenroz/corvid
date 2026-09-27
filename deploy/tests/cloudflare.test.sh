#!/usr/bin/env bash
# Runs cloudflare.py against a fake Cloudflare, covering the MTA-STS Worker commands: nothing
# here calls the real API.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'kill "$server" 2>/dev/null; rm -rf "$work"' EXIT

cat > "$work/fake.py" <<'PY'
import http.server, json, socketserver, sys, urllib.parse

state = {"scripts": {}, "domains": {}, "records": {}, "next_id": 1,
         "deny_scripts": "--deny-scripts" in sys.argv, "deny_domains": "--deny-domains" in sys.argv}
DENIED = {"success": False, "errors": [{"code": 10000, "message": "Authentication error"}]}

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def reply(self, code, body):
        self.send_response(code)
        self.end_headers()
        self.wfile.write(json.dumps(body).encode())

    def do_GET(self):
        path, _, query = self.path.partition("?")
        qs = urllib.parse.parse_qs(query)
        if path == "/client/v4/zones/z1":
            return self.reply(200, {"success": True, "result": {"account": {"id": "acct1"}}})
        if path == "/client/v4/accounts/acct1/workers/scripts":
            if state["deny_scripts"]:
                return self.reply(403, DENIED)
            return self.reply(200, {"success": True, "result": [{"id": n} for n in state["scripts"]]})
        if path == "/client/v4/accounts/acct1/workers/domains":
            if state["deny_domains"]:
                return self.reply(403, DENIED)
            hostname = qs.get("hostname", [None])[0]
            found = [dict(v, id=k) for k, v in state["domains"].items() if not hostname or v["hostname"] == hostname]
            return self.reply(200, {"success": True, "result": found})
        if path.startswith("/client/v4/zones/z1/dns_records"):
            name = qs.get("name", [None])[0]
            found = [r for r in state["records"].values() if not name or r["name"] == name]
            return self.reply(200, {"success": True, "result": found})
        return self.reply(404, {"success": False, "errors": [{"message": "not found"}]})

    def do_PUT(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path.startswith("/client/v4/accounts/acct1/workers/scripts/"):
            if state["deny_scripts"]:
                return self.reply(403, DENIED)
            state["scripts"][self.path.rsplit("/", 1)[-1]] = body.decode()
            return self.reply(200, {"success": True, "result": {}})
        if self.path == "/client/v4/accounts/acct1/workers/domains":
            if state["deny_domains"]:
                return self.reply(403, DENIED)
            payload = json.loads(body)
            did = f"d{state['next_id']}"
            state["next_id"] += 1
            state["domains"][did] = payload
            return self.reply(200, {"success": True, "result": dict(payload, id=did)})
        return self.reply(404, {"success": False, "errors": [{"message": "not found"}]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        rid = f"r{state['next_id']}"
        state["next_id"] += 1
        state["records"][rid] = dict(body, id=rid)
        return self.reply(200, {"success": True, "result": state["records"][rid]})

    def do_PATCH(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        rid = self.path.rsplit("/", 1)[-1]
        state["records"].setdefault(rid, {"id": rid}).update(body)
        return self.reply(200, {"success": True, "result": state["records"][rid]})

    def do_DELETE(self):
        rid = self.path.rsplit("/", 1)[-1]
        state["domains"].pop(rid, None)
        state["scripts"].pop(rid, None)
        state["records"].pop(rid, None)
        return self.reply(200, {"success": True, "result": {}})

server = socketserver.TCPServer(("127.0.0.1", 0), H)
print(server.server_address[1])
sys.stdout.flush()
server.serve_forever()
PY

start_fake() {
  python3 "$work/fake.py" "$@" > "$work/port" &
  server=$!
  for _ in $(seq 1 50); do [[ -s "$work/port" ]] && break; sleep 0.1; done
  local port
  port="$(cat "$work/port")"
  export CORVID_CLOUDFLARE_API="http://127.0.0.1:$port/client/v4/"
}
cf() { echo token | python3 "$here/cloudflare.py" "$@"; }

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

start_fake
account="$(cf account z1)"

echo "== publishing off 443"
printf 'token\nconsole.log(1)' | python3 "$here/cloudflare.py" deploy-worker "$account" z1 corvid-mta-sts-example z1.example.com
check "the worker is bound to the hostname" '[[ "$(cf worker-owner "$account" z1.example.com)" == corvid-mta-sts-example ]]'
cf create-txt z1 _mta-sts.example.com 'v=STSv1; id=aaa' >/dev/null
check "the TXT record was created" '[[ "$(cf records z1 _mta-sts.example.com TXT)" == *aaa* ]]'

echo "== a rerun is a no-op"
printf 'token\nconsole.log(1)' | python3 "$here/cloudflare.py" deploy-worker "$account" z1 corvid-mta-sts-example z1.example.com
check "the worker is still the only one bound" '[[ $(cf worker-owner "$account" z1.example.com | wc -l) -eq 1 ]]'

echo "== a policy change changes the id"
record="$(cf records z1 _mta-sts.example.com TXT)"
cf update-record z1 "${record%% *}" 'v=STSv1; id=bbb' >/dev/null
check "the TXT content changed" '[[ "$(cf records z1 _mta-sts.example.com TXT)" == *bbb* ]]'

echo "== removing when back on 443"
cf remove-worker "$account" corvid-mta-sts-example z1.example.com >/dev/null
check "the worker is gone" '[[ -z "$(cf worker-owner "$account" z1.example.com)" ]]'

echo "== a missing Workers permission fails naming it"
kill "$server" 2>/dev/null
start_fake --deny-scripts
out="$(cf worker-permission "$account" z1 2>&1)"
# shellcheck disable=SC2034  # read inside the eval'd check
status=$?
check "it fails" '[[ $status -ne 0 ]]'
check "it names the permission" '[[ "$out" == *"Workers Scripts:Edit"* ]]'

[[ $fail -eq 0 ]] || echo "--- last output: $out"
exit "$fail"
