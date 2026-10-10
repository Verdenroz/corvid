#!/usr/bin/env bash
# Waits up to SECONDS for Pocket-ID to answer through Caddy and exits 1 if it does not. Stalwart
# fetches the issuer when it first checks a token and refuses tokens until it restarts if that
# failed, so deploy.sh waits before restarting it and corvid-mail waits before starting. The unit
# ignores the exit status: Pocket-ID being down delays mail by at most SECONDS and never stops it.
set -uo pipefail

CORVID_DIR="${CORVID_DIR:-/opt/corvid}"
budget="${1:?usage: wait-issuer.sh SECONDS}"

. "$CORVID_DIR/server.env"
# Pinned to the host's own loopback, where Caddy answers.
resolve=(--resolve "${APP_URL#*://}:127.0.0.1")

deadline=$((SECONDS + budget))
until curl -sf -o /dev/null --max-time 5 "${resolve[@]}" "$APP_URL/.well-known/openid-configuration"; do
  (( SECONDS < deadline )) || { echo "$APP_URL does not answer within ${budget}s" >&2; exit 1; }
  sleep 1
done
