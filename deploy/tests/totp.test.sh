#!/usr/bin/env bash
# Runs totp.sh's status and disable against a stubbed stalwart-cli, plus totp.py's RFC 6238
# implementation against RFC 6238's own published SHA1 test vectors.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail=0
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

# run ARGS...
run() {
  rm -f "$work/patch"
  PATH="$work/bin:$PATH" CORVID_DIR="$work" CORVID_ENV="$work/corvid.env" \
    CORVID_CONFIG_CMD="$work/shell" bash "$here/totp.sh" "$@" > "$work/out" 2> "$work/err"
  # shellcheck disable=SC2034  # read inside the eval'd checks
  status=$?
}

with_app_password='{"credentials":{"0":{"@type":"Password"},"1":{"@type":"AppPassword","description":"phone"}}}'
with_otp='{"credentials":{"0":{"@type":"Password","otpAuth":"otpauth://totp/Corvid:alice@example.com?secret=ABC&issuer=Corvid"},"1":{"@type":"AppPassword","description":"phone"}}}'

echo "== status, disabled"
echo "$with_app_password" > "$work/credentials"
run status
check "exits 0" '[[ $status -eq 0 ]]'
check "reports disabled" 'grep -q "TOTP disabled for alice@example.com" "$work/out"'

echo "== status, enabled"
echo "$with_otp" > "$work/credentials"
run status
check "exits 0" '[[ $status -eq 0 ]]'
check "reports enabled" 'grep -q "TOTP enabled for alice@example.com" "$work/out"'
check "never prints the secret or URI" '! grep -q otpauth "$work/out"'

echo "== disable"
echo "$with_otp" > "$work/credentials"
run disable
check "exits 0" '[[ $status -eq 0 ]]'
check "patches only credentials/0/otpAuth, to null" \
  '[[ "$(cat "$work/patch")" == "{\"credentials/0/otpAuth\": null}" ]]'
check "reports what it did" 'grep -q "TOTP disabled for alice@example.com" "$work/out"'

[[ $fail -eq 0 ]] || { echo "--- last output:"; cat "$work/out"; echo "--- stderr:"; cat "$work/err"; }
exit "$fail"
