#!/usr/bin/env bash
# Unit tests for install.sh's MTA-STS helpers: the pure decision functions, then the Cloudflare
# orchestration against a fake Cloudflare (the same fake cloudflare.test.sh uses). No SSH, no
# real Cloudflare call.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../mta-sts-lib.sh
. "$here/mta-sts-lib.sh"

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
# A CNAME cloudflare.py has no command to create, standing in for Stalwart's own DNS management.
seed_cname() {
  python3 -c '
import json, sys, urllib.request
url = sys.argv[1] + "zones/" + sys.argv[2] + "/dns_records"
body = json.dumps({"type": "CNAME", "name": sys.argv[3], "content": sys.argv[4]}).encode()
urllib.request.urlopen(urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}))
' "$CORVID_CLOUDFLARE_API" "$1" "$2" "$3"
}
step() { :; }
ok() { :; }
warn() { printf '  warn  %s\n' "$*"; }
die() { echo "  DIED: $*" >&2; exit 1; }

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

check "dots become hyphens" '[[ "$(mta_sts_worker_name nroz.dev)" == corvid-mta-sts-nroz-dev ]]'

check "no record is clear" '[[ "$(mta_sts_record_status "" mail.example.com 0)" == clear ]]'
check "Stalwart's own CNAME is stale" \
  '[[ "$(mta_sts_record_status "id1 mail.example.com" mail.example.com 0)" == stale ]]'
check "this run's Worker is owned" \
  '[[ "$(mta_sts_record_status "id1 elsewhere.example" mail.example.com 1)" == owned ]]'
check "someone else's CNAME is foreign" \
  '[[ "$(mta_sts_record_status "id1 elsewhere.example" mail.example.com 0)" == foreign ]]'

policy="version: STSv1
mode: testing
mx: mail.example.com
max_age: 604800"
# shellcheck disable=SC2034  # read inside the eval'd checks
script="$(mta_sts_worker_script "$policy")"
check "the worker script embeds the policy" '[[ "$script" == *"mx: mail.example.com"* ]]'
check "the worker script serves it as text/plain" '[[ "$script" == *"text/plain"* ]]'
check "the worker script answers only the well-known path" '[[ "$script" == *"/.well-known/mta-sts.txt"* ]]'

domain=example.com
hostname=mail.example.com
zone=z1

echo "== binding before removing a stale record fails"
start_fake
account="$(cloudflare token account "$zone")"
seed_cname "$zone" "mta-sts.$domain" "$hostname"
declare -a stale_mta_sts=()
mta_sts_preflight token "$domain" "$hostname" "$zone" 4443
check "the stale CNAME is queued for removal" '(( ${#stale_mta_sts[@]} > 0 ))'
worker_name="$(mta_sts_worker_name "$domain")"
status=0
( mta_sts_publish token "$domain" "$zone" "$account" "$worker_name" "$policy" id1 ) || status=$?
check "the Worker cannot bind while the stale record remains" '[[ $status -ne 0 ]]'

echo "== removing it first lets the Worker bind"
for id in "${stale_mta_sts[@]}"; do cloudflare token delete "$zone" "$id" >/dev/null; done
mta_sts_publish token "$domain" "$zone" "$account" "$worker_name" "$policy" id1
check "the worker is now bound" '[[ "$(cloudflare token worker-owner "$account" "mta-sts.$domain")" == "$worker_name" ]]'

echo "== a rerun is a no-op"
mta_sts_publish token "$domain" "$zone" "$account" "$worker_name" "$policy" id1
check "still exactly one binding" '[[ "$(cloudflare token worker-owner "$account" "mta-sts.$domain")" == "$worker_name" ]]'
check "still exactly one v=STSv1 TXT" \
  '[[ $(cloudflare token records "$zone" "_mta-sts.$domain" TXT | grep -c "v=STSv1") -eq 1 ]]'

echo "== back to 443 removes only the Worker's own TXT, leaving a Stalwart-owned one"
cloudflare token create-txt "$zone" "_mta-sts.$domain" 'v=STSv1; id=stalwart-owned' >/dev/null
mta_sts_remove_worker token "$domain" "$zone" "$account" "$worker_name"
check "the worker is gone" '[[ -z "$(cloudflare token worker-owner "$account" "mta-sts.$domain")" ]]'
check "the Stalwart TXT is untouched" \
  '[[ "$(cloudflare token records "$zone" "_mta-sts.$domain" TXT)" == *stalwart-owned* ]]'
check "only the Stalwart TXT remains" \
  '[[ $(cloudflare token records "$zone" "_mta-sts.$domain" TXT | wc -l) -eq 1 ]]'

echo "== the Worker must be removed before Stalwart's own record can be created"
kill "$server" 2>/dev/null
start_fake
account="$(cloudflare token account "$zone")"
mta_sts_publish token "$domain" "$zone" "$account" "$worker_name" "$policy" id1
status=0
( seed_cname "$zone" "mta-sts.$domain" "$hostname" ) 2>/dev/null || status=$?
check "Stalwart's record is blocked while the Worker still holds the hostname" '[[ $status -ne 0 ]]'
mta_sts_remove_worker token "$domain" "$zone" "$account" "$worker_name"
status=0
seed_cname "$zone" "mta-sts.$domain" "$hostname" 2>/dev/null || status=$?
check "removing the Worker first lets Stalwart's record be created" '[[ $status -eq 0 ]]'

echo "== the post-deploy check on 443 warns when Stalwart has not published yet"
kill "$server" 2>/dev/null
start_fake
out="$(mta_sts_check_published token "$domain" "$zone" 2>&1)"
check "it warns naming the CLAUDE.md trap" '[[ "$out" == *"MTA-STS trap"* ]]'
cloudflare token create-txt "$zone" "_mta-sts.$domain" 'v=STSv1; id=stalwart' >/dev/null
# shellcheck disable=SC2034  # read inside the eval'd check
out="$(mta_sts_check_published token "$domain" "$zone" 2>&1)"
check "it is silent once Stalwart's record exists" '[[ -z "$out" ]]'

echo "== a foreign record stops the install before touching Cloudflare"
kill "$server" 2>/dev/null
start_fake
seed_cname "$zone" "mta-sts.$domain" elsewhere.example
declare -a stale_mta_sts=()
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( mta_sts_preflight token "$domain" "$hostname" "$zone" 4443 ) || status=$?
check "it stops" '[[ $status -ne 0 ]]'

echo "== a first install off 443 with mta-sts clear passes under set -e"
declare -a stale_mta_sts=()
status=0
# shellcheck disable=SC2034  # read inside the eval'd check
( set -e; mta_sts_preflight token fresh.example.org "$hostname" "$zone" 4443 ) || status=$?
check "it returns 0" '[[ $status -eq 0 ]]'

[[ $fail -eq 0 ]] || echo "--- last output"
exit "$fail"
