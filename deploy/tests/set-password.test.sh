#!/usr/bin/env bash
# Runs set-password.sh against a stubbed stalwart-cli. The patch it sends must target one
# credential entry, never replace the whole map, or every enrolled app password is wiped.
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
run() {
  rm -f "$work/patch"
  echo -n "TestPassphraseQx7Lp2Vn" | PATH="$work/bin:$PATH" CORVID_DIR="$work" \
    CORVID_ENV="$work/corvid.env" CORVID_CONFIG_CMD="$work/shell" bash "$here/set-password.sh" \
    > "$work/out" 2>&1
  # shellcheck disable=SC2034  # read inside the eval'd checks
  status=$?
}

echo "== a strong password"
run
check "exits 0" '[[ $status -eq 0 ]]'
check "patches one credential entry, not the whole map" \
  'grep -q "\"credentials/0\":" "$work/patch" && ! grep -q "\"credentials\":" "$work/patch"'
check "the password never reaches the patch as a bare property" \
  '! grep -q "TestPassphraseQx7Lp2Vn.*\"credentials\"" "$work/patch"'

echo "== a password Stalwart refuses as too weak"
: > "$work/reject"
run
check "exits 65" '[[ $status -eq 65 ]]'
check "Stalwart's reason is shown" 'grep -q "too weak" "$work/out"'
check "the invalidProperties line itself is not shown" '! grep -q invalidProperties "$work/out"'

[[ $fail -eq 0 ]] || { echo "--- last output:"; cat "$work/out"; }
exit "$fail"
