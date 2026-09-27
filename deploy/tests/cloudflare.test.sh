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

def multipart_parts(content_type, body):
    """Matches cloudflare.py's own encoder: enough to read back what it wrote."""
    boundary = b"--" + content_type.split("boundary=")[1].encode()
    parts = {}
    for chunk in body.split(boundary)[1:-1]:
        head, _, content = chunk.strip(b"\r\n").partition(b"\r\n\r\n")
        headers = dict(line.split(b": ", 1) for line in head.split(b"\r\n"))
        name = headers[b"Content-Disposition"].split(b'name="')[1].split(b'"')[0].decode()
        parts[name] = (headers[b"Content-Type"].decode(), content)
    return parts

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
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        if self.path.startswith("/client/v4/accounts/acct1/workers/scripts/"):
            if state["deny_scripts"]:
                return self.reply(403, DENIED)
            content_type = self.headers.get("Content-Type", "")
            if not content_type.startswith("multipart/form-data"):
                return self.reply(400, {"success": False, "errors": [{"message": "expected multipart/form-data"}]})
            parts = multipart_parts(content_type, body)
            metadata = json.loads(parts["metadata"][1])
            module_type, module_content = parts[metadata["main_module"]]
            if module_type != "application/javascript+module":
                return self.reply(400, {"success": False, "errors": [{"message": "expected an ES module part"}]})
            state["scripts"][self.path.rsplit("/", 1)[-1]] = module_content.decode()
            return self.reply(200, {"success": True, "result": {}})
        if self.path == "/client/v4/accounts/acct1/workers/domains":
            if state["deny_domains"]:
                return self.reply(403, DENIED)
            payload = json.loads(body)
            for existing_id, existing in list(state["domains"].items()):
                if existing["hostname"] == payload["hostname"]:
                    del state["domains"][existing_id]
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
deploy() { printf 'token\nexport default { fetch() {} };' | python3 "$here/cloudflare.py" deploy-worker "$@"; }
# worker-owner prints only the first match, so a duplicate binding needs the raw list.
domain_count() {
  curl -s "${CORVID_CLOUDFLARE_API}accounts/$account/workers/domains?hostname=$1" |
    python3 -c 'import json, sys; print(len(json.load(sys.stdin)["result"]))'
}

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

start_fake
account="$(cf account z1)"

echo "== publishing off 443"
deploy "$account" z1 corvid-mta-sts-example z1.example.com
check "the worker is bound to the hostname" '[[ "$(cf worker-owner "$account" z1.example.com)" == corvid-mta-sts-example ]]'
cf create-txt z1 _mta-sts.example.com 'v=STSv1; id=aaa' >/dev/null
check "the TXT record was created" '[[ "$(cf records z1 _mta-sts.example.com TXT)" == *aaa* ]]'

echo "== a rerun is a no-op"
deploy "$account" z1 corvid-mta-sts-example z1.example.com
check "still exactly one binding for the hostname" '[[ "$(domain_count z1.example.com)" == 1 ]]'

echo "== a TXT record's content can be replaced"
record="$(cf records z1 _mta-sts.example.com TXT)"
cf update-record z1 "${record%% *}" 'v=STSv1; id=bbb' >/dev/null
check "the TXT content changed" '[[ "$(cf records z1 _mta-sts.example.com TXT)" == *bbb* ]]'

echo "== removing when back on 443"
cf remove-worker "$account" corvid-mta-sts-example z1.example.com >/dev/null
check "the worker is gone" '[[ -z "$(cf worker-owner "$account" z1.example.com)" ]]'

echo "== a missing Workers permission fails naming it"
kill "$server" 2>/dev/null
start_fake --deny-scripts
out="$(deploy "$account" z1 corvid-mta-sts-example z1.example.com 2>&1)"
# shellcheck disable=SC2034  # read inside the eval'd check
status=$?
check "it fails" '[[ $status -ne 0 ]]'
check "it names the permission" '[[ "$out" == *"Workers Scripts:Edit"* ]]'

echo "== a missing Workers Routes permission fails naming it"
kill "$server" 2>/dev/null
start_fake --deny-domains
out="$(deploy "$account" z1 corvid-mta-sts-example z1.example.com 2>&1)"
# shellcheck disable=SC2034  # read inside the eval'd check
status=$?
check "it fails" '[[ $status -ne 0 ]]'
check "it names the permission" '[[ "$out" == *"Workers Routes:Edit"* ]]'

[[ $fail -eq 0 ]] || echo "--- last output: $out"
exit "$fail"
