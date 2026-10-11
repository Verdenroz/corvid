#!/usr/bin/env bash
# Runs the pinned Stalwart with the plan's mail WebHook and the sentinel built with cargo (or
# CORVID_SENTINEL_BIN), then delivers mail over SMTP and checks both transports: a device on the
# sentinel's own stream reads one encrypted preview per new Inbox message, and a device with an
# FCM endpoint gets the same through a stand-in fcm.googleapis.com (a TLS proxy the sentinel
# reaches through HTTPS_PROXY, trusting a throwaway CA) that verifies the VAPID signature. Nothing
# for a JMAP append, nothing once a device is revoked. Needs stalwart-cli, curl, openssl, python3
# with cryptography, and docker or podman; port 8080 and the sentinel's 11333 must be free.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
engine="$(command -v podman || command -v docker)"
mail_image="$(awk -F= '$1=="Image"{print $2}' "$here/corvid-mail.container")"
mail_port="${CORVID_RIG_MAIL_PORT:-18080}"
smtp_port="${CORVID_RIG_SMTP_PORT:-18125}"
proxy_port="${CORVID_RIG_PROXY_PORT:-18398}"
sentinel_port=11333
secret=rig-sentinel-secret-0123456789
recovery_pw=RigRecovery-93kQxz7pW
work="$(mktemp -d)"
mail="corvid-rig-push-$$"
volumes=("$mail-etc" "$mail-data")
proxy_pid= sentinel_pid= stream_pid=
trap '"$engine" rm -f "$mail" >/dev/null 2>&1; "$engine" volume rm "${volumes[@]}" >/dev/null 2>&1
  for pid in $proxy_pid $sentinel_pid $stream_pid; do kill "$pid" 2>/dev/null; done
  rm -rf "$work"' EXIT

command -v stalwart-cli >/dev/null || { echo "stalwart-cli not found" >&2; exit 69; }
command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 69; }
python3 -I -c 'import cryptography' 2>/dev/null || { echo "python3 cryptography not found" >&2; exit 69; }
for port in 8080 "$mail_port" "$smtp_port" "$proxy_port" "$sentinel_port"; do
  ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || { echo "port $port is in use" >&2; exit 69; }
done

sentinel="${CORVID_SENTINEL_BIN:-}"
if [[ -z "$sentinel" ]]; then
  (cd "$root" && cargo build -q -p corvid-sentinel) || { echo "the sentinel did not build" >&2; exit 1; }
  sentinel="$root/target/debug/corvid-sentinel"
fi

# A CA the sentinel trusts through SSL_CERT_FILE, and a certificate it signs for the FCM host.
mkdir -p "$work/fcm"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 1 -subj /CN=rig-ca \
  -addext basicConstraints=critical,CA:TRUE -keyout "$work/ca.key" -out "$work/ca.crt" 2>/dev/null
openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj /CN=fcm.googleapis.com \
  -keyout "$work/fcm.key" -out "$work/fcm.csr" 2>/dev/null
printf 'subjectAltName=DNS:fcm.googleapis.com\nbasicConstraints=CA:FALSE\n' > "$work/fcm.ext"
openssl x509 -req -in "$work/fcm.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial -days 1 \
  -extfile "$work/fcm.ext" -out "$work/fcm.crt" 2>/dev/null || { echo "openssl could not sign the FCM certificate" >&2; exit 1; }

# fcm.googleapis.com, reduced to what the sentinel posts to it: a CONNECT proxy that terminates TLS
# for that one host and saves each request's headers and body in its own files.
python3 -I -c '
import http.server, json, os, socketserver, ssl, sys
out, port, cert, key = sys.argv[1:]
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
class H(http.server.BaseHTTPRequestHandler):
    def do_CONNECT(self):
        assert self.path == "fcm.googleapis.com:443", self.path
        self.send_response(200); self.end_headers(); self.wfile.flush()
        tls = context.wrap_socket(self.connection, server_side=True)
        f = tls.makefile("rb")
        line = f.readline().decode().split()
        headers = {}
        while (h := f.readline().decode().strip()):
            k, v = h.split(": ", 1); headers[k.lower()] = v
        body = f.read(int(headers.get("content-length", 0)))
        n = len(os.listdir(out)) // 2
        open(f"{out}/{n}.bin", "wb").write(body)
        open(f"{out}/{n}.meta", "w").write(json.dumps({"path": line[1], "headers": headers}))
        tls.sendall(b"HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        self.close_connection = True
    def log_message(self, *a): pass
socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(("127.0.0.1", int(port)), H).serve_forever()' \
  "$work/fcm" "$proxy_port" "$work/fcm.crt" "$work/fcm.key" &
proxy_pid=$!
for _ in $(seq 1 50); do (exec 3<>"/dev/tcp/127.0.0.1/$proxy_port") 2>/dev/null && break; sleep 0.1; done
(exec 3<>"/dev/tcp/127.0.0.1/$proxy_port") 2>/dev/null || { echo "the stand-in FCM never listened" >&2; exit 1; }

"$engine" run -d --name "$mail" --network host -e STALWART_RECOVERY_ADMIN="recovery:$recovery_pw" \
  -e CORVID_SENTINEL_SECRET="$secret" \
  -v "$mail-etc:/etc/stalwart" -v "$mail-data:/var/lib/stalwart" "$mail_image" >/dev/null
wait_for() { for _ in $(seq 1 60); do curl -sf -o /dev/null --max-time 2 "$1" && return 0; sleep 1; done; return 1; }
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never came up" >&2; exit 1; }

cat > "$work/shell" <<'SH'
#!/bin/sh
printf 'MAIL_DOMAIN=rig.test\nMAIL_HOSTNAME=mail.rig.test\nMAIL_USER=mailuser\nACME_CONTACT=ops@rig.test\nWEB_PORT=4443\nIDP_HOSTNAME=idp.rig.test\nADMIN_USER=mailuser-admin\n'
SH
chmod +x "$work/shell"
export CORVID_DIR="$work"; . "$here/lib.sh"
printf 'STALWART_URL=http://127.0.0.1:8080\nRECOVERY_ADMIN_USER=recovery\nRECOVERY_ADMIN_PASSWORD=%s\n' "$recovery_pw" > "$work/corvid.env"
CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
  bash "$here/bootstrap-server.sh" >/dev/null || { echo "bootstrap-server.sh failed" >&2; exit 1; }
load_env "$work/corvid.env"
"$engine" restart "$mail" >/dev/null
wait_for http://127.0.0.1:8080/healthz/live || { echo "Stalwart never restarted" >&2; exit 1; }
create_admin_api_key "$work/corvid.env" || { echo "the admin API key was not created" >&2; exit 1; }
load_env "$work/corvid.env"

domain_id="$(stalwart-cli query Domain --json | python3 -I -c '
import json, sys
print(next(o["id"] for o in map(json.loads, filter(str.strip, sys.stdin)) if o.get("name") == "rig.test"))')"
# Spam filtering is off so a test sender with no SPF record is delivered as ham, the event pushed.
{
  cat <<JSON
{"@type":"reconcile","object":"NetworkListener","matchOn":["name"],"value":{"l-http":{"name":"http","protocol":"http","useTls":false,"tlsImplicit":false,"bind":{"[::]:$mail_port":true}},"l-smtp":{"name":"smtp","protocol":"smtp","useTls":false,"tlsImplicit":false,"bind":{"[::]:$smtp_port":true}}}}
{"@type":"upsert","object":"Account","matchOn":["name"],"value":{"acct":{"@type":"User","name":"mailuser","description":"mailuser","domainId":"$domain_id","roles":{"@type":"User"}}}}
{"@type":"update","object":"MtaStageAuth","value":{"require":{"match":{},"else":"false"}}}
{"@type":"update","object":"SpamSettings","value":{"enable":false}}
JSON
  grep '/webhook/mail' "$here/stalwart.plan.ndjson"
} | stalwart-cli apply --stdin >/dev/null || { echo "the rig plan did not apply" >&2; exit 1; }
"$engine" restart "$mail" >/dev/null
export STALWART_URL="http://127.0.0.1:$mail_port"
wait_for "$STALWART_URL/healthz/live" || { echo "Stalwart never listened on $mail_port" >&2; exit 1; }

account="$(stalwart-cli query Account --json | python3 -I -c '
import json, sys
print(next(o["id"] for o in map(json.loads, filter(str.strip, sys.stdin)) if o.get("emailAddress") == "mailuser@rig.test"))')"
# Approved devices, as approvals would have recorded them, with key nodes already gone, which
# revoke counts as destroyed. Push registration needs no more.
python3 -I - "$work/devices.json" "$account" <<'PY'
import hashlib, json, sys
payload = json.dumps({"requests": {}, "approved": {name: {"accountId": sys.argv[2], "nodeId": "zzzzzz", "blobId": "x"} for name in ("phone", "tablet")}, "pushes": {}})
body = "2\n" + payload
open(sys.argv[1], "w").write(hashlib.sha256(body.encode()).hexdigest() + "\n" + body)
PY
cat > "$work/config.yaml" <<YAML
server:
  domain: rig.test
  hostname: mail.rig.test
  mail_user: mailuser
  acme_contact: ops@rig.test
  web_port: 4443
sentinel:
  bind: 127.0.0.1:$sentinel_port
  state: $work/sentinel.json
  devices:
    state: $work/devices.json
    stalwart_url: $STALWART_URL
  push: {}
YAML
HTTPS_PROXY="http://127.0.0.1:$proxy_port" SSL_CERT_FILE="$work/ca.crt" \
  CORVID_CONFIG="$work/config.yaml" CORVID_SENTINEL_SECRET="$secret" CORVID_STALWART_TOKEN="$STALWART_TOKEN" \
  RUST_LOG=corvid_sentinel=info "$sentinel" > "$work/sentinel.log" 2>&1 &
sentinel_pid=$!
wait_for "http://127.0.0.1:$sentinel_port/corvid/notices" || { cat "$work/sentinel.log" >&2; echo "the sentinel never listened" >&2; exit 1; }

# The devices' push keys, one pair for both, kept here to decrypt what arrives.
python3 -I - "$work" <<'PY'
import base64, json, os, sys
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
work = sys.argv[1]
key = ec.generate_private_key(ec.SECP256R1())
open(f"{work}/phone.pem", "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
auth = os.urandom(16)
open(f"{work}/phone.auth", "wb").write(auth)
b64 = lambda b: base64.urlsafe_b64encode(b).decode().rstrip("=")
point = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
keys = {"p256dh": b64(point), "auth": b64(auth)}
json.dump({"endpoint": "corvid-stream", **keys}, open(f"{work}/phone.json", "w"))
json.dump({"endpoint": "https://fcm.googleapis.com/fcm/send/rigtablet?up=1", **keys}, open(f"{work}/tablet.json", "w"))
PY

fail=0
check() { if [[ "$2" == "$3" ]]; then echo "  ok    $1"; else echo "  FAIL  $1 (got '$2')"; fail=1; fi; }
pushes() { find "$work/fcm" -name '*.bin' | wc -l; }
events() { grep -c '^data: ' "$work/stream.out"; }
wait_pushes() { for _ in $(seq 1 30); do (( $(pushes) >= $1 )) && return 0; sleep 0.5; done; return 1; }
wait_events() { for _ in $(seq 1 30); do (( $(events) >= $1 )) && return 0; sleep 0.5; done; return 1; }
smtp() {
  python3 -I - "$smtp_port" "$1" "$2" <<'PY'
import smtplib, sys
port, subject, body = sys.argv[1:]
msg = (f"From: Ada Lovelace <ada@outside.test>\r\nTo: mailuser@rig.test\r\nSubject: {subject}\r\n"
       f"Message-ID: <{subject.replace(' ', '-')}@outside.test>\r\nDate: Sat, 10 Oct 2026 12:00:00 +0000\r\n\r\n{body}\r\n")
with smtplib.SMTP("127.0.0.1", int(port)) as s:
    s.ehlo("outside.test"); s.sendmail("ada@outside.test", ["mailuser@rig.test"], msg); s.quit()
PY
}
# Decrypts the body in file $1 with the devices' keys and prints the preview JSON.
decrypt() {
  python3 -I - "$work" "$1" <<'PY'
import sys
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
work, path = sys.argv[1:]
key = serialization.load_pem_private_key(open(f"{work}/phone.pem", "rb").read(), None)
auth = open(f"{work}/phone.auth", "rb").read()
body = open(path, "rb").read()
salt, idlen = body[:16], body[20]
sender_bytes = body[21:21 + idlen]
sender = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), sender_bytes)
shared = key.exchange(ec.ECDH(), sender)
mine = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
ikm = HKDF(hashes.SHA256(), 32, auth, b"WebPush: info\0" + mine + sender_bytes).derive(shared)
cek = HKDF(hashes.SHA256(), 16, salt, b"Content-Encoding: aes128gcm\0").derive(ikm)
nonce = HKDF(hashes.SHA256(), 12, salt, b"Content-Encoding: nonce\0").derive(ikm)
record = AESGCM(cek).decrypt(nonce, body[21 + idlen:], None)
assert record[-1] == 2
print(record[:-1].decode())
PY
}
# The body of the Nth (from 0) event on the stream, written to the file $2.
stream_body() {
  python3 -I - "$work/stream.out" "$1" "$2" <<'PY'
import base64, sys
data = [l[6:].strip() for l in open(sys.argv[1]) if l.startswith("data: ")][int(sys.argv[2])]
open(sys.argv[3], "wb").write(base64.urlsafe_b64decode(data + "=" * (-len(data) % 4)))
PY
}
# Checks the VAPID Authorization of the Nth FCM request against the key the sentinel publishes.
vapid_ok() {
  python3 -I - "$work/fcm/$1.meta" "$2" <<'PY'
import base64, json, sys
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
from cryptography.hazmat.primitives import hashes
d = lambda t: base64.urlsafe_b64decode(t + "=" * (-len(t) % 4))
auth = json.load(open(sys.argv[1]))["headers"]["authorization"]
token, key = auth.removeprefix("vapid t=").split(", k=")
assert key == sys.argv[2], "the header key is not the published key"
signed, signature = token.rsplit(".", 1)
raw = d(signature)
public = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), d(key))
public.verify(encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big")),
              signed.encode(), ec.ECDSA(hashes.SHA256()))
claims = json.loads(d(signed.split(".")[1]))
assert claims["aud"] == "https://fcm.googleapis.com" and claims["sub"] == "mailto:ops@rig.test", claims
print("ok")
PY
}
field() { python3 -I -c 'import json,sys; v=json.loads(sys.stdin.read()); [v:=v[int(k)] if isinstance(v, list) else v[k] for k in sys.argv[1:]]; print(v)' "$@"; }

put() {
  curl -s -w '\n%{http_code}' -X PUT -H 'content-type: application/json' --data-binary @"$work/$2.json" \
    "http://127.0.0.1:$sentinel_port/corvid/devices/$1/push"
}
echo "== registering the devices"
reply="$(put phone phone)"
check "a stream registration answers 200" "$(tail -n1 <<<"$reply")" 200
token="$(head -n1 <<<"$reply" | field token)"
check "with a 32-byte token" "$(python3 -I -c 'import base64,sys; t=sys.argv[1]; print(len(base64.urlsafe_b64decode(t+"="*(-len(t)%4))))' "$token")" 32
check "a FCM endpoint is stored" "$(tail -n1 <<<"$(put tablet tablet)")" 204
status="$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'content-type: application/json' \
  --data-binary '{"endpoint":"https://ntfy.sh/x","p256dh":"AA","auth":"AA"}' "http://127.0.0.1:$sentinel_port/corvid/devices/tablet/push")"
check "an endpoint that is neither is refused" "$status" 400
vapid_key="$(curl -s "http://127.0.0.1:$sentinel_port/corvid/push/vapid" | field publicKey)"
check "the VAPID public key is published" "$(wc -c <<<"$vapid_key" | tr -d ' ')" 88
check "the stream refuses a missing token" "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$sentinel_port/corvid/push/stream")" 401
check "the stream refuses a wrong token" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer nope' "http://127.0.0.1:$sentinel_port/corvid/push/stream")" 401
curl -sN -H "Authorization: Bearer $token" "http://127.0.0.1:$sentinel_port/corvid/push/stream" > "$work/stream.out" &
stream_pid=$!
sleep 2

echo "== mail delivered over SMTP"
smtp "Notes on the engine" "I have annotated the translation with seven notes. Note G is the long one."
wait_pushes 1; wait_events 1
check "one push reaches FCM" "$(pushes)" 1
check "one event reaches the stream" "$(events)" 1
check "the FCM post goes to the endpoint's own path" "$(field path < "$work/fcm/0.meta")" "/fcm/send/rigtablet?up=1"
check "aes128gcm-encoded" "$(field headers content-encoding < "$work/fcm/0.meta")" aes128gcm
check "with a day's TTL" "$(field headers ttl < "$work/fcm/0.meta")" 86400
check "and high urgency" "$(field headers urgency < "$work/fcm/0.meta")" high
check "the VAPID signature verifies under the published key" "$(vapid_ok 0 "$vapid_key")" ok
check "the FCM body carries no plaintext" "$(grep -c 'Lovelace' "$work/fcm/0.bin")" 0
stream_body 0 "$work/stream0.bin"
check "the stream body carries no plaintext" "$(grep -c 'Lovelace' "$work/stream0.bin")" 0
shown="$(decrypt "$work/fcm/0.bin")"
check "the device decrypts the sender" "$(field from name <<<"$shown")" "Ada Lovelace"
check "and the subject" "$(field subject <<<"$shown")" "Notes on the engine"
check "and the preview" "$(field preview <<<"$shown" | head -c 30)" "I have annotated the translati"
streamed="$(decrypt "$work/stream0.bin")"
check "the stream carries the same message" "$(field id <<<"$streamed")" "$(field id <<<"$shown")"

echo "== a JMAP append and a revoked device"
inbox="$(printf '{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Mailbox/query",{"accountId":"%s","filter":{"role":"inbox"}},"m"]]}' "$account" |
  curl_authed -s -H 'content-type: application/json' --data-binary @- "$STALWART_URL/jmap/" | field methodResponses 0 1 ids 0)"
printf '{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Email/set",{"accountId":"%s","create":{"d":{"mailboxIds":{"%s":true},"from":[{"email":"mailuser@rig.test"}],"subject":"appended","receivedAt":"2020-01-01T00:00:00Z","bodyValues":{"b":{"value":"x"}},"textBody":[{"partId":"b","type":"text/plain"}]}}},"s"]]}' \
  "$account" "$inbox" | curl_authed -s -H 'content-type: application/json' --data-binary @- "$STALWART_URL/jmap/" > "$work/append.json"
check "the JMAP append created a message" "$(field methodResponses 0 1 created d id < "$work/append.json" | grep -c .)" 1
sleep 4
check "a message appended over JMAP is not pushed to FCM" "$(pushes)" 1
check "nor to the stream" "$(events)" 1
signature="$(printf 'revoke phone' | python3 -I -c 'import base64,hashlib,hmac,sys; print(base64.b64encode(hmac.new(sys.argv[1].encode(), sys.stdin.buffer.read(), hashlib.sha256).digest()).decode())' "$secret")"
status="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "X-Signature: $signature" "http://127.0.0.1:$sentinel_port/internal/devices/phone")"
check "revoking the stream device succeeds" "$status" 204
for _ in $(seq 1 20); do kill -0 "$stream_pid" 2>/dev/null || break; sleep 0.5; done
check "the open stream was closed" "$(kill -0 "$stream_pid" 2>/dev/null && echo open || echo closed)" closed
check "its token is refused" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "http://127.0.0.1:$sentinel_port/corvid/push/stream")" 401
smtp "After revocation" "Only the tablet is still pushed."
wait_pushes 2
sleep 2
check "mail after revocation still reaches the other device" "$(pushes)" 2
check "the old appended message was not pushed with it, and nothing more reaches the revoked device" "$(events)" 1

[[ "$fail" == 0 ]] || { echo "--- sentinel log"; cat "$work/sentinel.log"; }
exit "$fail"
