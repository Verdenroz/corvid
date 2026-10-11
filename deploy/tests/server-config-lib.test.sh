#!/usr/bin/env bash
# install.sh owns the server: section of config.yaml and rewrites it on every run, so what an
# operator set there that it never asks about must survive the rewrite. Needs only bash and awk.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../server-config-lib.sh
. "$here/server-config-lib.sh"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

fork='  android_apps:
    - {package: org.example.fork, sha256: 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff}
    - {package: org.example.other, sha256: ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100}'
existing="# Written by install.sh
server:
  domain: example.com
  hostname: mail.example.com
  mail_user: alice
  acme_contact: you@elsewhere.example
  web_port: 4443
  client_access: anywhere
$fork

milter:
  bind: 127.0.0.1:11332
"

block() { server_block example.com mail.example.com alice you@elsewhere.example 4443 anywhere "$@"; }

echo "== reading a stated list back"
check "a stated list is read in full, key line included" '[[ "$(kept_android_apps "$existing")" == "$fork" ]]'
check "an unset list reads as nothing" '[[ -z "$(kept_android_apps "server:
  domain: example.com
milter:
  android_apps:
    - {package: not.in.server}")" ]]'
check "a list in another section is not taken" \
  '[[ -z "$(kept_android_apps "server:
  domain: example.com
labeler:
  android_apps:
    - {package: not.in.server}")" ]]'
check "only the list is taken, not the key after it" \
  '[[ "$(kept_android_apps "server:
  android_apps:
    - {package: a, sha256: b}
  client_access: anywhere")" == "  android_apps:
    - {package: a, sha256: b}" ]]'

echo "== the rewrite keeps it"
rewritten="$(block "" "" "$(kept_android_apps "$existing")")"
check "the rewritten block carries the list" '[[ "$rewritten" == *"$fork"* ]]'
check "rewriting again changes nothing" \
  '[[ "$(block "" "" "$(kept_android_apps "$rewritten")")" == "$rewritten" ]]'
check "the other settings are written as before" \
  '[[ "$rewritten" == *"  web_port: 4443"* && "$rewritten" == *"  client_access: anywhere"* ]]'

echo "== the rewrite without one"
# shellcheck disable=SC2034  # read inside the eval'd checks
plain="$(block "" "" "")"
check "no list is written when none was stated" '[[ "$plain" != *android_apps* ]]'
check "the Caddy flag is off unless chosen" '[[ "$plain" != *assetlinks_caddy* ]]'
check "a host zone is written when set" '[[ "$(server_block example.com n1.example.net alice you@elsewhere.example 443 host example.net "" "")" == *"  host_zone: example.net"* ]]'

echo "== the Caddy flag"
# shellcheck disable=SC2034  # read inside the eval'd checks
caddy="$(server_block example.com mail.example.com alice you@elsewhere.example 443 anywhere "" yes "")"
check "it is written as true when chosen" '[[ "$caddy" == *"  assetlinks_caddy: true"* ]]'
# shellcheck disable=SC2034  # read inside the eval'd checks
caddy_with="$(server_block example.com mail.example.com alice you@elsewhere.example 443 anywhere "" yes "$fork")"
check "it sits beside a kept list without breaking it" \
  '[[ "$(kept_android_apps "$caddy_with")" == "$fork" && "$caddy_with" == *"  assetlinks_caddy: true"* ]]'

[[ $fail -eq 0 ]] || { echo "--- rewritten:"; echo "$rewritten"; }
exit "$fail"
