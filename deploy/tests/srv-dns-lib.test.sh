#!/usr/bin/env bash
# Unit tests for install.sh's discovery SRV helpers, against a fake Cloudflare. No SSH, no real
# Cloudflare call.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../srv-dns-lib.sh
. "$here/srv-dns-lib.sh"

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
warn() { warned="$*"; }

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

check "no record is clear" '[[ "$(srv_dns_status "" mail.example.com 4443)" == clear ]]'
check "a record for this host and port is current" '[[ "$(srv_dns_status "r1 4443 mail.example.com" mail.example.com 4443)" == current ]]'
check "a record for another port is foreign" '[[ "$(srv_dns_status "r1 443 mail.example.com" mail.example.com 4443)" == foreign ]]'
check "a record for another host is foreign" '[[ "$(srv_dns_status "r1 4443 other.example.net" mail.example.com 4443)" == foreign ]]'
check "a second record beside the right one is foreign" \
  '[[ "$(srv_dns_status "$(printf "r1 4443 mail.example.com\nr2 4443 other.example.net")" mail.example.com 4443)" == foreign ]]'

zone=z1
domain=example.com
hostname=mail.example.com

echo "== a clear record is created"
start_fake
srv_dns_preflight token "$zone" "$domain" "$hostname" 4443
check "srv_create is set" '[[ $srv_create -eq 1 ]]'
srv_dns_create token "$zone" "$domain" "$hostname" 4443
check "the SRV record now exists" '[[ "$(cloudflare token srv-records "$zone" _corvid._tcp.example.com | cut -d" " -f2-)" == "4443 mail.example.com" ]]'

echo "== a rerun is a no-op"
srv_dns_preflight token "$zone" "$domain" "$hostname" 4443
check "srv_create is clear" '[[ $srv_create -eq 0 ]]'

echo "== a record pointing elsewhere stops the install"
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( srv_dns_preflight token "$zone" "$domain" "$hostname" 8443 ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'

echo "== a refused create prints the record to add by hand"
kill "$server" 2>/dev/null
export CORVID_CLOUDFLARE_API="http://127.0.0.1:1/client/v4/"
# shellcheck disable=SC2034  # read inside the eval'd check
warned=""
srv_dns_create token "$zone" "$domain" "$hostname" 4443
check "the warning carries the record" '[[ "$warned" == *"_corvid._tcp.example.com SRV 0 5 4443 mail.example.com"* ]]'

[[ $fail -eq 0 ]] || echo "--- last output"
exit "$fail"
