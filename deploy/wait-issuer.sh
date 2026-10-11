#!/usr/bin/env bash
# Waits up to SECONDS for the issuers Stalwart may trust to answer and exits 1 if they do not.
# Stalwart fetches the issuer when it first checks a token and refuses tokens until it restarts if
# that failed, so deploy.sh waits before restarting it and corvid-mail waits before starting. The
# unit ignores the exit status: an issuer being down delays mail by at most SECONDS and never
# stops it. Both are waited for, whichever directory is applied: Pocket-ID behind Caddy while the
# pod still runs it, and the sentinel on the pod's loopback, where a 503 means it is up with
# passkeys switched off.
set -uo pipefail

CORVID_DIR="${CORVID_DIR:-/opt/corvid}"
budget="${1:?usage: wait-issuer.sh SECONDS}"

. "$CORVID_DIR/server.env"
. "$(dirname "$0")/lib.sh"
# Pinned to the host's own loopback, where Caddy answers.
resolve=(--resolve "${APP_URL#*://}:127.0.0.1")

idp_answers() {
  curl -sf -o /dev/null --max-time 5 "${resolve[@]}" "$APP_URL/.well-known/openid-configuration"
}
sentinel_answers() {
  local code
  code="$(ISSUER_URL="$CORVID_ISSUER_URL" issuer_status)"
  [[ "$code" == 200 || "$code" == 503 ]]
}

deadline=$((SECONDS + budget))
until { unit_masked corvid-idp.service || idp_answers; } && { [[ -z "${CORVID_ISSUER_URL:-}" ]] || sentinel_answers; }; do
  (( SECONDS < deadline )) || { echo "the issuer does not answer within ${budget}s" >&2; exit 1; }
  sleep 1
done
