#!/usr/bin/env bash
# Unit tests for install.sh's app links helpers: the pure decision function, the Worker script
# itself (run under node when there is one), then the Cloudflare orchestration against a fake
# Cloudflare. No SSH, no real Cloudflare call.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../assetlinks-lib.sh
. "$here/assetlinks-lib.sh"

work="$(mktemp -d)"
trap 'kill "$server" 2>/dev/null; rm -rf "$work"' EXIT

start_fake() {
  python3 "$here/tests/fake-cloudflare.py" > "$work/port" &
  server=$!
  for _ in $(seq 1 50); do [[ -s "$work/port" ]] && break; sleep 0.1; done
  local port
  port="$(cat "$work/port")"
  export CORVID_CLOUDFLARE_API="http://127.0.0.1:$port/client/v4/"
}
# Matches install.sh's own cloudflare(): CLOUDFLARE_STDIN, when set, rides after the token line.
cloudflare() {
  local token="$1"; shift
  { printf '%s\n' "$token"; printf '%s' "${CLOUDFLARE_STDIN:-}"; } | python3 "$here/cloudflare.py" "$@"
}
seed_record() {
  python3 -c '
import json, sys, urllib.request
url = sys.argv[1] + "zones/z1/dns_records"
body = json.dumps({"type": sys.argv[2], "name": sys.argv[3], "content": sys.argv[4], "proxied": sys.argv[5] == "proxied"}).encode()
urllib.request.urlopen(urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}))
' "$CORVID_CLOUDFLARE_API" "$@"
}
step() { :; }
ok() { :; }
die() { echo "  DIED: $*" >&2; exit 1; }

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

check "dots become hyphens" '[[ "$(worker_name corvid-assetlinks nroz.dev)" == corvid-assetlinks-nroz-dev ]]'

echo "== choosing the way"
ip=203.0.113.9
check "a free name gets a Worker custom domain" '[[ "$(assetlinks_mode "" "" 4443 $ip)" == worker-domain ]]'
check "a free name on 443 still gets the Worker, not a Caddy site" '[[ "$(assetlinks_mode "" "" 443 $ip)" == worker-domain ]]'
check "a proxied name gets a Worker route" \
  '[[ "$(assetlinks_mode "" "r1 A 198.51.100.7 proxied" 4443 $ip)" == worker-route ]]'
check "one proxied record among others is enough" \
  '[[ "$(assetlinks_mode "" "$(printf "r1 A %s dns-only\nr2 AAAA 2001:db8::1 proxied" $ip)" 443 $ip)" == worker-route ]]'
check "a name pointing here on 443 gets a Caddy site" \
  '[[ "$(assetlinks_mode "" "r1 A $ip dns-only" 443 $ip)" == caddy ]]'
check "a name pointing here off 443 is left to the operator" \
  '[[ "$(assetlinks_mode "" "r1 A $ip dns-only" 4443 $ip)" == manual ]]'
check "a name pointing elsewhere on 443 is left to the operator" \
  '[[ "$(assetlinks_mode "" "r1 A 198.51.100.7 dns-only" 443 $ip)" == manual ]]'
check "a name with an AAAA record besides is left to the operator" \
  '[[ "$(assetlinks_mode "" "$(printf "r1 A %s dns-only\nr2 AAAA 2001:db8::1 dns-only" $ip)" 443 $ip)" == manual ]]'
check "a CNAME is left to the operator" \
  '[[ "$(assetlinks_mode "" "r1 CNAME elsewhere.example dns-only" 443 $ip)" == manual ]]'
check "a Worker this script already bound as a custom domain keeps its way" \
  '[[ "$(assetlinks_mode domain "r1 AAAA 100:: proxied" 443 $ip)" == worker-domain ]]'
check "a Worker route this script already holds keeps its way" \
  '[[ "$(assetlinks_mode route "r1 A $ip dns-only" 443 $ip)" == worker-route ]]'

echo "== the Worker script"
document='[{"relation":["delegate_permission/common.handle_all_urls","delegate_permission/common.get_login_creds"],"target":{"namespace":"android_app","package_name":"org.example","sha256_cert_fingerprints":["AB:CD"]}}]'
script="$(static_worker_script "$ASSETLINKS_PATH" application/json "$document")"
check "it embeds the document" '[[ "$script" == *"org.example"* ]]'
check "it serves JSON" '[[ "$script" == *"application/json"* ]]'
if command -v node >/dev/null; then
  printf '%s\n' "$script" > "$work/worker.mjs"
  cat > "$work/run.mjs" <<JS
import worker from "$work/worker.mjs";
const at = async (path) => {
  const response = await worker.fetch(new Request("https://example.com" + path));
  return [response.status, response.headers.get("content-type"), response.redirected, await response.text()];
};
console.log(JSON.stringify([await at("/.well-known/assetlinks.json"), await at("/"), await at("/.well-known/assetlinks.json/x")]));
JS
  # shellcheck disable=SC2034  # read inside the eval'd checks
  answers="$(node "$work/run.mjs")"
  check "run under node, the well-known path answers 200 with the document as application/json" \
    '[[ "$(python3 -c "import json, sys; a = json.load(sys.stdin)[0]; print(a[0], a[1], a[2], a[3].strip() == sys.argv[1])" "$document" <<<"$answers")" == "200 application/json False True" ]]'
  check "run under node, every other path is a 404" \
    '[[ "$(python3 -c "import json, sys; print([a[0] for a in json.load(sys.stdin)[1:]])" <<<"$answers")" == "[404, 404]" ]]'
else
  echo "  skip  no node here, so the Worker script was not run"
fi

echo "== a free name: preflight, then a custom domain"
domain=example.com
zone=z1
start_fake
account="$(cloudflare token account "$zone")"
assetlinks_preflight token "$domain" "$zone" "$account" "$ip" 4443
check "preflight chooses a custom domain" '[[ "$assetlinks_choice" == worker-domain ]]'
check "preflight changed nothing" '[[ -z "$(cloudflare token worker-owner "$account" "$domain")" ]]'
# shellcheck disable=SC2154  # set by assetlinks_preflight
assetlinks_publish token "$domain" "$zone" "$account" "$assetlinks_worker" worker-domain "$document"
check "the Worker is bound to the name" '[[ "$(cloudflare token worker-owner "$account" "$domain")" == corvid-assetlinks-example-com ]]'
assetlinks_publish token "$domain" "$zone" "$account" "$assetlinks_worker" worker-domain "$document"
check "a rerun keeps one binding" '[[ "$(cloudflare token worker-owner "$account" "$domain")" == corvid-assetlinks-example-com ]]'
assetlinks_preflight token "$domain" "$zone" "$account" "$ip" 443
check "the next preflight sees its own Worker, even on 443" '[[ "$assetlinks_choice" == worker-domain ]]'

echo "== a proxied name: a route for the one path"
kill "$server" 2>/dev/null
start_fake
seed_record A "$domain" 198.51.100.7 proxied
assetlinks_preflight token "$domain" "$zone" "$account" "$ip" 4443
check "preflight chooses a route" '[[ "$assetlinks_choice" == worker-route ]]'
assetlinks_publish token "$domain" "$zone" "$account" "$assetlinks_worker" worker-route "$document"
check "the route runs the Worker" \
  '[[ "$(cloudflare token worker-route "$zone" "$domain$ASSETLINKS_PATH")" == corvid-assetlinks-example-com ]]'
check "the apex is not bound as a custom domain" '[[ -z "$(cloudflare token worker-owner "$account" "$domain")" ]]'
assetlinks_preflight token "$domain" "$zone" "$account" "$ip" 443
check "the next preflight sees its own route, even on 443" '[[ "$assetlinks_choice" == worker-route ]]'

echo "== a name pointing here on 443: nothing is published at Cloudflare"
kill "$server" 2>/dev/null
start_fake
seed_record A "$domain" "$ip" dns-only
assetlinks_preflight token "$domain" "$zone" "$account" "$ip" 443
check "preflight chooses Caddy" '[[ "$assetlinks_choice" == caddy ]]'

echo "== someone else's name: left alone"
kill "$server" 2>/dev/null
start_fake
seed_record A "$domain" 198.51.100.7 dns-only
assetlinks_preflight token "$domain" "$zone" "$account" "$ip" 443
check "preflight leaves it to the operator" '[[ "$assetlinks_choice" == manual ]]'

[[ $fail -eq 0 ]] || echo "--- last output"
exit "$fail"
