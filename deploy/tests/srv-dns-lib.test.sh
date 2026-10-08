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

check "no record is a create" '[[ "$(srv_dns_status "" mail.example.com 4443)" == create ]]'
check "a record for this host and port needs nothing" '[[ "$(srv_dns_status "r1 4443 mail.example.com" mail.example.com 4443)" == none ]]'
check "a record for this host on another port is an update" '[[ "$(srv_dns_status "r1 443 mail.example.com" mail.example.com 4443)" == update ]]'
check "a record for another host is foreign" '[[ "$(srv_dns_status "r1 4443 other.example.net" mail.example.com 4443)" == foreign ]]'
check "a second record beside the right one is foreign" \
  '[[ "$(srv_dns_status "$(printf "r1 4443 mail.example.com\nr2 4443 other.example.net")" mail.example.com 4443)" == foreign ]]'

zone=z1
domain=example.com
hostname=mail.example.com

echo "== a clear record is created"
start_fake
srv_dns_preflight token "$zone" "$domain" "$hostname" 4443
check "the action is create" '[[ $srv_action == create ]]'
srv_dns_apply create token "$zone" "$domain" "$hostname" 4443
check "the SRV record now exists" '[[ "$(cloudflare token srv-records "$zone" _corvid._tcp.example.com | cut -d" " -f2-)" == "4443 mail.example.com" ]]'

echo "== a rerun is a no-op"
srv_dns_preflight token "$zone" "$domain" "$hostname" 4443
check "the action is none" '[[ $srv_action == none ]]'

echo "== a changed port is updated in place"
srv_dns_preflight token "$zone" "$domain" "$hostname" 8443
check "the action is update" '[[ $srv_action == update ]]'
check "the old port is reported" '[[ $srv_old_port == 4443 ]]'
# shellcheck disable=SC2154  # set by srv_dns_preflight
srv_dns_apply update token "$zone" "$domain" "$hostname" 8443 "$srv_record_id"
check "the record now holds the new port, and only one record exists" \
  '[[ "$(cloudflare token srv-records "$zone" _corvid._tcp.example.com | cut -d" " -f2-)" == "8443 mail.example.com" ]]'

echo "== a record for another host stops the install"
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( srv_dns_preflight token "$zone" "$domain" other.example.net 8443 ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'

echo "== two records stop the install"
cloudflare token create-srv "$zone" _corvid._tcp.example.com 9443 mail.example.com
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( srv_dns_preflight token "$zone" "$domain" "$hostname" 8443 ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'

echo "== a refused create prints the record to add by hand"
kill "$server" 2>/dev/null
export CORVID_CLOUDFLARE_API="http://127.0.0.1:1/client/v4/"
# shellcheck disable=SC2034  # read inside the eval'd check
warned=""
srv_dns_apply create token "$zone" "$domain" "$hostname" 4443
check "the warning carries the record" '[[ "$warned" == *"_corvid._tcp.example.com SRV 0 5 4443 mail.example.com"* ]]'
# shellcheck disable=SC2034  # read inside the eval'd check
warned=""
srv_dns_apply update token "$zone" "$domain" "$hostname" 8443 r1
check "a refused update warns with the record too" '[[ "$warned" == *"SRV 0 5 8443 mail.example.com"* ]]'

[[ $fail -eq 0 ]] || echo "--- last output"
exit "$fail"
