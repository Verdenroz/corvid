#!/usr/bin/env bash
# Unit tests for install.sh's idp hostname DNS helpers, against a fake Cloudflare (the same
# fake cloudflare.test.sh uses). No SSH, no real Cloudflare call.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../idp-dns-lib.sh
. "$here/idp-dns-lib.sh"

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
cloudflare() { local token="$1"; shift; echo "$token" | python3 "$here/cloudflare.py" "$@"; }
ok() { :; }
die() { echo "  DIED: $*" >&2; exit 1; }

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

check "no record is clear" '[[ "$(idp_a_record_status "" 203.0.113.9)" == clear ]]'
check "this server's own IP is current" '[[ "$(idp_a_record_status "203.0.113.9" 203.0.113.9)" == current ]]'
check "someone else's IP is foreign" '[[ "$(idp_a_record_status "198.51.100.1" 203.0.113.9)" == foreign ]]'
check "one of several matching this server is current" \
  '[[ "$(idp_a_record_status "$(printf "198.51.100.1\n203.0.113.9\n")" 203.0.113.9)" == current ]]'

zone=z1
idp_hostname=idp.example.com
server_ip=203.0.113.9

echo "== a clear record is created"
start_fake
idp_dns_preflight token "$zone" "$idp_hostname" "$server_ip"
check "idp_create_a is set" '[[ $idp_create_a -eq 1 ]]'
idp_dns_create token "$zone" "$idp_hostname" "$server_ip"
check "the A record now exists" '[[ "$(cloudflare token a-records "$zone" "$idp_hostname")" == "$server_ip" ]]'

echo "== a rerun is a no-op"
idp_dns_preflight token "$zone" "$idp_hostname" "$server_ip"
check "idp_create_a is clear" '[[ $idp_create_a -eq 0 ]]'

echo "== a foreign record stops the install before touching anything else"
kill "$server" 2>/dev/null
start_fake
cloudflare token create-a "$zone" "$idp_hostname" 198.51.100.1 >/dev/null
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( idp_dns_preflight token "$zone" "$idp_hostname" "$server_ip" ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'
check "no record for this server was created" '! cloudflare token a-records "$zone" "$idp_hostname" | grep -qx "$server_ip"'

[[ $fail -eq 0 ]] || echo "--- last output"
exit "$fail"
