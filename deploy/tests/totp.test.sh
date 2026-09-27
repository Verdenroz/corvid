#!/usr/bin/env bash
# Runs totp.sh against a stubbed stalwart-cli. enable must never patch otpAuth on a wrong code,
# must touch only credentials/<key>/otpAuth, and must refuse without an app password unless
# --force is given.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'kill "$enable_pid" 2>/dev/null; rm -rf "$work"' EXIT

fail=0
enable_pid=""
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

echo "== totp.py against RFC 6238's published SHA1 test vectors"
# shellcheck disable=SC2034  # read inside the eval'd checks
rfc_secret="$(python3 -c "import base64; print(base64.b32encode(b'12345678901234567890').decode())")"
check "T=59 -> 94287082" '[[ "$(python3 "$here/totp.py" code "$rfc_secret" 59 8)" == 94287082 ]]'
check "T=1111111109 -> 07081804" '[[ "$(python3 "$here/totp.py" code "$rfc_secret" 1111111109 8)" == 07081804 ]]'
check "T=1111111111 -> 14050471" '[[ "$(python3 "$here/totp.py" code "$rfc_secret" 1111111111 8)" == 14050471 ]]'
check "T=1234567890 -> 89005924" '[[ "$(python3 "$here/totp.py" code "$rfc_secret" 1234567890 8)" == 89005924 ]]'
check "T=2000000000 -> 69279037" '[[ "$(python3 "$here/totp.py" code "$rfc_secret" 2000000000 8)" == 69279037 ]]'
check "verify accepts one step early" 'python3 "$here/totp.py" verify "$rfc_secret" 94287082 89 8'
check "verify rejects a code far outside the window" '! python3 "$here/totp.py" verify "$rfc_secret" 94287082 200 8'

cat > "$work/shell" <<'SH'
#!/bin/sh
printf 'MAIL_DOMAIN=example.com\nMAIL_HOSTNAME=mail.example.com\nMAIL_USER=alice\nACME_CONTACT=ops@elsewhere.example\nWEB_PORT=4443\n'
SH

mkdir -p "$work/bin"
cat > "$work/bin/stalwart-cli" <<CLI
#!/bin/sh
case "\$1 \$2" in
  "query Account") echo '{"id":"a1","emailAddress":"alice@example.com"}' ;;
  "get Account") cat "$work/credentials" ;;
  "update Account") cat > "$work/patch"; echo "Updated Account a1" ;;
esac
CLI
chmod +x "$work/shell" "$work/bin/stalwart-cli"
printf 'STALWART_URL=http://127.0.0.1\n' > "$work/corvid.env"

# run STDIN ARGS...
run() {
  local stdin="$1"; shift
  rm -f "$work/patch"
  printf '%s\n' "$stdin" | PATH="$work/bin:$PATH" CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" \
    CORVID_CONFIG_CMD="$work/shell" bash "$here/totp.sh" "$@" > "$work/out" 2> "$work/err"
  # shellcheck disable=SC2034  # read inside the eval'd checks
  status=$?
}

no_credentials='{"credentials":{}}'
with_app_password='{"credentials":{"0":{"@type":"Password"},"1":{"@type":"AppPassword","description":"phone"}}}'
with_otp='{"credentials":{"0":{"@type":"Password","otpAuth":"otpauth://totp/Corvid:alice@example.com?secret=ABC&issuer=Corvid"},"1":{"@type":"AppPassword","description":"phone"}}}'

echo "== status, disabled"
echo "$with_app_password" > "$work/credentials"
run "" status
check "exits 0" '[[ $status -eq 0 ]]'
check "reports disabled" 'grep -q "TOTP disabled for alice@example.com" "$work/out"'

echo "== status, enabled"
echo "$with_otp" > "$work/credentials"
run "" status
check "exits 0" '[[ $status -eq 0 ]]'
check "reports enabled" 'grep -q "TOTP enabled for alice@example.com" "$work/out"'
check "never prints the secret or URI" '! grep -q otpauth "$work/out"'

echo "== disable"
echo "$with_otp" > "$work/credentials"
run "" disable
check "exits 0" '[[ $status -eq 0 ]]'
check "patches only credentials/0/otpAuth, to null" \
  '[[ "$(cat "$work/patch")" == "{\"credentials/0/otpAuth\": null}" ]]'
check "reports what it did" 'grep -q "TOTP disabled for alice@example.com" "$work/out"'

echo "== enable refuses without an app password"
echo "$no_credentials" > "$work/credentials"
run "000000" enable
check "exits 65" '[[ $status -eq 65 ]]'
check "no patch was sent" '[[ ! -e "$work/patch" ]]'
check "points at device.sh add" 'grep -q "device.sh add" "$work/err"'

echo "== enable, --force skips the app-password guard, still needs the right code"
echo "$no_credentials" > "$work/credentials"
run "000000" enable --force
check "exits 65 on a wrong code" '[[ $status -eq 65 ]]'
check "no patch was sent" '[[ ! -e "$work/patch" ]]'
check "says the code was wrong" 'grep -q "wrong code" "$work/err"'

echo "== enable refuses when otpAuth is already set"
echo "$with_otp" > "$work/credentials"
run "000000" enable
check "exits 65" '[[ $status -eq 65 ]]'
check "no patch was sent" '[[ ! -e "$work/patch" ]]'
check "points at disable" 'grep -q "run disable first" "$work/err"'

echo "== enable with the right code patches only credentials/0/otpAuth"
echo "$with_app_password" > "$work/credentials"
rm -f "$work/patch"
mkfifo "$work/stdin"
PATH="$work/bin:$PATH" CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" \
  bash "$here/totp.sh" enable < "$work/stdin" > "$work/out" 2> "$work/err" &
enable_pid=$!
exec 4> "$work/stdin"
for _ in $(seq 1 50); do grep -q '^otpauth://' "$work/out" 2>/dev/null && break; sleep 0.1; done
secret="$(sed -n 's/.*secret=\([^&]*\)&.*/\1/p' "$work/out")"
code="$(python3 "$here/totp.py" code "$secret")"
echo "$code" >&4
exec 4>&-
wait "$enable_pid"
# shellcheck disable=SC2034  # read inside the eval'd checks
status=$?
enable_pid=""
check "exits 0" '[[ $status -eq 0 ]]'
check "the URI is shown exactly once" '[[ $(grep -c "^otpauth://" "$work/out") -eq 1 ]]'
check "patches only credentials/0/otpAuth" \
  '[[ "$(python3 -c "import json; print(list(json.load(open('"'"'$work/patch'"'"')).keys()))")" == "['"'"'credentials/0/otpAuth'"'"']" ]]'
check "the patched value matches the printed URI" \
  '[[ "$(python3 -c "import json; print(json.load(open('"'"'$work/patch'"'"'))['"'"'credentials/0/otpAuth'"'"'])")" == "$(grep '^otpauth://' "$work/out")" ]]'
check "reports enabled" 'grep -q "TOTP enabled for alice@example.com" "$work/out"'

[[ $fail -eq 0 ]] || { echo "--- last output:"; cat "$work/out"; echo "--- stderr:"; cat "$work/err"; }
exit "$fail"
