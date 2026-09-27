#!/usr/bin/env python3
"""A fake Cloudflare, just enough of the API for cloudflare.py's own tests. Never the real one.

Prints its port on stdout, then serves until killed. --deny-scripts and --deny-domains make the
matching Workers endpoints 403, for the missing-permission tests.
"""
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
            kind = qs.get("type", [None])[0]
            found = [r for r in state["records"].values()
                     if (not name or r["name"] == name) and (not kind or r["type"] == kind)]
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
            # A plain DNS record at the hostname blocks a custom domain bind, same as the real API.
            if any(r["name"] == payload["hostname"] for r in state["records"].values()):
                return self.reply(409, {"success": False, "errors": [{"message": "a DNS record already exists for this hostname"}]})
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


if __name__ == "__main__":
    server = socketserver.TCPServer(("127.0.0.1", 0), H)
    print(server.server_address[1])
    sys.stdout.flush()
    server.serve_forever()
