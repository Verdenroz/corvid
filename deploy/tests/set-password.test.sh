#!/usr/bin/env bash
# Runs set-password.sh against a stubbed stalwart-cli. The patch it sends must target one
# credential's secret, never the whole map or the whole entry, or it wipes app passwords or
# drops that credential's allowedIps/otpAuth.
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

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
  "update Account")
    cat > "$work/patch"
    if [ -e "$work/reject" ]; then
      echo 'error: invalidProperties'
      echo '  Password is too weak'
      echo '  Properties: credentials/0/secret'
      exit 1
    fi
    echo "Updated Account a1"
    ;;
esac
CLI
chmod +x "$work/shell" "$work/bin/stalwart-cli"
printf 'STALWART_URL=http://127.0.0.1\n' > "$work/corvid.env"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }
# run CREDENTIALS_JSON
run() {
  printf '%s' "$1" > "$work/credentials"
  rm -f "$work/patch"
  echo -n "TestPassphraseQx7Lp2Vn" | PATH="$work/bin:$PATH" CORVID_DIR="$work" \
    CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" bash "$here/set-password.sh" \
    > "$work/out" 2>&1
  # shellcheck disable=SC2034  # read inside the eval'd checks
  status=$?
}

echo "== a brand-new account, no credentials yet"
run '{"credentials":{}}'
check "exits 0" '[[ $status -eq 0 ]]'
check "creates the Password entry at key 0" 'grep -q "\"credentials/0/secret\":" "$work/patch"'
check "touches nothing else" '[[ $(python3 -c "import json;print(len(json.load(open(\"$work/patch\"))))") -eq 1 ]]'

echo "== an account with an app password already at key 0"
run '{"credentials":{"0":{"@type":"AppPassword","description":"phone"},"1":{"@type":"Password"}}}'
check "exits 0" '[[ $status -eq 0 ]]'
check "patches the Password entry's own key, not 0" 'grep -q "\"credentials/1/secret\":" "$work/patch"'
check "the app password entry is not named in the patch" '! grep -q "credentials/0" "$work/patch"'

echo "== two Password credentials (should never happen, but do not guess)"
run '{"credentials":{"0":{"@type":"Password"},"1":{"@type":"Password"}}}'
check "exits 1" '[[ $status -eq 1 ]]'
check "names what was found" 'grep -q "found 2" "$work/out"'

echo "== a password Stalwart refuses as too weak"
: > "$work/reject"
run '{"credentials":{"0":{"@type":"Password"}}}'
check "exits 65" '[[ $status -eq 65 ]]'
check "Stalwart's reason is shown" 'grep -q "too weak" "$work/out"'
check "the invalidProperties line itself is not shown" '! grep -q invalidProperties "$work/out"'

[[ $fail -eq 0 ]] || { echo "--- last output:"; cat "$work/out"; }
exit "$fail"
