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
# cloudflare.py has no command to create an AAAA or CNAME, standing in for a record from
# somewhere other than install.sh.
seed_record() {
  python3 -c '
import json, sys, urllib.request
url = sys.argv[1] + "zones/" + sys.argv[2] + "/dns_records"
body = json.dumps({"type": sys.argv[3], "name": sys.argv[4], "content": sys.argv[5]}).encode()
urllib.request.urlopen(urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}))
' "$CORVID_CLOUDFLARE_API" "$1" "$2" "$3" "$4"
}
ok() { :; }
die() { echo "  DIED: $*" >&2; exit 1; }

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

check "no record is clear" '[[ "$(idp_dns_status "" "" "" 203.0.113.9)" == clear ]]'
check "this server's own A record is current" '[[ "$(idp_dns_status "203.0.113.9" "" "" 203.0.113.9)" == current ]]'
check "someone else's A record is foreign" '[[ "$(idp_dns_status "198.51.100.1" "" "" 203.0.113.9)" == foreign ]]'
check "an AAAA record is foreign even beside this server's A record" \
  '[[ "$(idp_dns_status "203.0.113.9" "2001:db8::1" "" 203.0.113.9)" == foreign ]]'
check "a CNAME record is foreign" '[[ "$(idp_dns_status "" "" "somewhere.example" 203.0.113.9)" == foreign ]]'

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

echo "== a foreign A record stops the install before touching anything else"
kill "$server" 2>/dev/null
start_fake
cloudflare token create-a "$zone" "$idp_hostname" 198.51.100.1 >/dev/null
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( idp_dns_preflight token "$zone" "$idp_hostname" "$server_ip" ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'
check "no record for this server was created" '! cloudflare token a-records "$zone" "$idp_hostname" | grep -qx "$server_ip"'

echo "== an existing AAAA record stops the install"
kill "$server" 2>/dev/null
start_fake
seed_record "$zone" AAAA "$idp_hostname" 2001:db8::1
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( idp_dns_preflight token "$zone" "$idp_hostname" "$server_ip" ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'

echo "== an existing CNAME record stops the install"
kill "$server" 2>/dev/null
start_fake
seed_record "$zone" CNAME "$idp_hostname" somewhere.example
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( idp_dns_preflight token "$zone" "$idp_hostname" "$server_ip" ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'

[[ $fail -eq 0 ]] || echo "--- last output"
exit "$fail"
