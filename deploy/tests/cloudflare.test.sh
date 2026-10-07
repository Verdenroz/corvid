#!/usr/bin/env bash
# Runs cloudflare.py against a fake Cloudflare, covering the MTA-STS Worker commands: nothing
# here calls the real API.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'kill "$server" 2>/dev/null; rm -rf "$work"' EXIT

start_fake() {
  python3 "$here/tests/fake-cloudflare.py" "$@" > "$work/port" &
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

echo "== SRV records"
kill "$server" 2>/dev/null
start_fake
check "no SRV record reads as empty" '[[ -z "$(cf srv-records z1 _corvid._tcp.example.com)" ]]'
cf create-srv z1 _corvid._tcp.example.com 4443 mail.example.com
check "the SRV record reads back as port and target" '[[ "$(cf srv-records z1 _corvid._tcp.example.com | cut -d" " -f2-)" == "4443 mail.example.com" ]]'
check "the record is priority 0 weight 5" \
  '[[ "$(curl -s "${CORVID_CLOUDFLARE_API}zones/z1/dns_records?type=SRV" | python3 -c "import json, sys; d = json.load(sys.stdin)[\"result\"][0][\"data\"]; print(d[\"priority\"], d[\"weight\"])")" == "0 5" ]]'
id="$(cf srv-records z1 _corvid._tcp.example.com | cut -d" " -f1)"
cf update-srv z1 "$id" 8443 mail.example.com
check "update-srv repoints the same record" '[[ "$(cf srv-records z1 _corvid._tcp.example.com)" == "$id 8443 mail.example.com" ]]'
check "update-srv keeps priority 0 weight 5" \
  '[[ "$(curl -s "${CORVID_CLOUDFLARE_API}zones/z1/dns_records?type=SRV" | python3 -c "import json, sys; d = json.load(sys.stdin)[\"result\"][0][\"data\"]; print(d[\"priority\"], d[\"weight\"])")" == "0 5" ]]'

[[ $fail -eq 0 ]] || echo "--- last output: $out"
exit "$fail"
