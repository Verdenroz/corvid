#!/usr/bin/env bash
# Unit tests for install.sh's MTA-STS decision helpers: pure functions, no SSH, no Cloudflare.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=../mta-sts-lib.sh
. "$here/mta-sts-lib.sh"

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

exit "$fail"
