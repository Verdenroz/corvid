#!/usr/bin/env bash
# Tests deploy/lib.sh's curl_authed, which must never put the password in curl's argv.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/curl" <<'SH'
#!/bin/sh
printf '%s\n' "$@" >> "$WORK/argv"
prev=""
for arg in "$@"; do
  [ "$prev" = "--config" ] && cat "$arg" >> "$WORK/config-content"
  prev="$arg"
done
SH
chmod +x "$work/bin/curl"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

STALWART_USER=admin@example.com STALWART_PASSWORD=SuperSecretQx7Lp2Vn WORK="$work" \
  PATH="$work/bin:$PATH" bash -c ". \"$here/lib.sh\"; curl_authed -fsS https://example.test/"

check "the password never appears in curl's argv" '! grep -qF SuperSecretQx7Lp2Vn "$work/argv"'
check "the flags passed through reached curl" 'grep -qx -- "-fsS" "$work/argv"'
check "the URL passed through reached curl" 'grep -qx "https://example.test/" "$work/argv"'
check "the password reaches curl through the config file instead" \
  'grep -qF SuperSecretQx7Lp2Vn "$work/config-content"'

echo "== a password carrying a quote and a backslash"
: > "$work/config-content"
tricky='p@ss"w\ord'
STALWART_USER=admin@example.com STALWART_PASSWORD="$tricky" WORK="$work" \
  PATH="$work/bin:$PATH" bash -c ". \"$here/lib.sh\"; curl_authed -fsS https://example.test/"
check "the quote and backslash are escaped for curl's config syntax" \
  'grep -qF '\''p@ss\"w\\ord'\'' "$work/config-content"'

echo "== a token set beside a password"
: > "$work/argv"; : > "$work/config-content"
STALWART_TOKEN=API_tokenQx7Lp2Vn STALWART_USER=admin@example.com STALWART_PASSWORD=SuperSecretQx7Lp2Vn WORK="$work" \
  PATH="$work/bin:$PATH" bash -c ". \"$here/lib.sh\"; curl_authed -fsS https://example.test/"
check "the token never appears in curl's argv" '! grep -qF API_tokenQx7Lp2Vn "$work/argv"'
check "the token reaches curl as a Bearer header through the config file" \
  'grep -qF "Authorization: Bearer API_tokenQx7Lp2Vn" "$work/config-content"'
check "the password is not sent" '! grep -qF SuperSecretQx7Lp2Vn "$work/config-content"'
printf 'STALWART_TOKEN=t\nSTALWART_USER=u\nSTALWART_PASSWORD=p\n' > "$work/corvid.env"
check "load_env drops the password pair when the file holds a token" \
  '[[ "$(bash -c ". \"$here/lib.sh\"; load_env \"$work/corvid.env\"; echo \"\$STALWART_TOKEN\${STALWART_USER:-}\${STALWART_PASSWORD:-}\"")" == t ]]'

echo "== server_set_up"
set_up() {
  printf '%s\n' "$@" > "$work/corvid.env"
  bash -c ". \"$here/lib.sh\"; load_env \"$work/corvid.env\"; server_set_up"
}
check "a token alone counts as set up" 'set_up STALWART_TOKEN=t'
check "a password alone counts as set up" 'set_up STALWART_USER=u STALWART_PASSWORD=p'
check "a token and a password count as set up" 'set_up STALWART_TOKEN=t STALWART_USER=u STALWART_PASSWORD=p'
check "neither counts as not set up" '! set_up RECOVERY_ADMIN_USER=r'

echo "== auth_decision"
decide() {
  AUTH_STATUS_TRIES=2 AUTH_STATUS_WAIT=0 bash -c ". \"$here/lib.sh\"; sentinel_signed() { printf '%b' \"\$REPLY_FROM_SENTINEL\"; }; auth_decision"
}
export REPLY_FROM_SENTINEL
REPLY_FROM_SENTINEL='200\n{"daily":1,"master":1,"codes":[],"passkeys":[]}\n'
check "both roles enrolled switches to passkeys" '[[ "$(decide)" == "passkeys 1 1" ]]'
REPLY_FROM_SENTINEL='200\n{"daily":1,"master":0,"codes":["master"],"passkeys":[]}\n'
check "one role enrolled stays with Pocket-ID and reports the waiting code" '[[ "$(decide)" == "pocket-id 1 0 master" ]]'
REPLY_FROM_SENTINEL='503\n{"error":"auth_off"}\n'
check "passkeys switched off in the sentinel is off, not an error" '[[ "$(decide)" == "off 0 0" ]]'
REPLY_FROM_SENTINEL='000\n\n'
check "no answer from the sentinel is a failure that prints nothing" 'out="$(decide)"; [[ $? -ne 0 && -z "$out" ]]'
REPLY_FROM_SENTINEL='502\nbad gateway\n'
check "any other status is a failure too" 'out="$(decide)"; [[ $? -ne 0 && -z "$out" ]]'

echo "== no other script embeds the password in curl's argv"
hits="$(grep -l 'curl.*-u.*STALWART_PASSWORD' "$here"/*.sh 2>/dev/null | grep -v '/lib\.sh$' || true)"
check "grep finds no such call site" '[[ -z "$hits" ]]'

echo "== every deploy script that is run rather than sourced is executable"
unexecutable="$(find "$here" -maxdepth 1 -name '*.sh' ! -name '*lib.sh' ! -perm -u+x)"
check "find lists no such script" '[[ -z "$unexecutable" ]]'

[[ $fail -eq 0 ]] || { echo "--- argv:"; cat "$work/argv"; echo "--- hits: $hits"; echo "--- not executable: $unexecutable"; }
exit "$fail"
