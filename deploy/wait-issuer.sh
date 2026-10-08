#!/usr/bin/env bash
# Waits up to SECONDS for Pocket-ID to answer through Caddy and exits 1 if it does not. Stalwart
# fetches the issuer when it first checks a token and refuses tokens until it restarts if that
# failed, so deploy.sh waits before restarting it and corvid-mail waits before starting. With
# --if-oidc it exits 0 at once unless the OIDC plan has been applied. The unit ignores the exit
# status: Pocket-ID being down delays mail by at most SECONDS and never stops it.
set -uo pipefail

CORVID_DIR="${CORVID_DIR:-/opt/corvid}"
budget="${1:?usage: wait-issuer.sh SECONDS [--if-oidc]}"
[[ "${2:-}" != --if-oidc || -e "$CORVID_DIR/.oidc-active" ]] || exit 0

. "$CORVID_DIR/server.env"
issuer="${CORVID_ISSUER_URL:-https://$CORVID_IDP_HOSTNAME:$CORVID_WEB_PORT}"
resolve=()
[[ -n "${CORVID_ISSUER_URL:-}" ]] || resolve=(--resolve "$CORVID_IDP_HOSTNAME:$CORVID_WEB_PORT:127.0.0.1")

deadline=$((SECONDS + budget))
until curl -sf -o /dev/null --max-time 5 "${resolve[@]}" "$issuer/.well-known/openid-configuration"; do
  (( SECONDS < deadline )) || { echo "$issuer does not answer within ${budget}s" >&2; exit 1; }
  sleep 2
done
