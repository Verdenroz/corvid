#!/usr/bin/env bash
# Tests scripts/app-manifest.py against a directory of stand-in artifacts.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

printf 'apk bytes' > "$work/corvid.apk"
printf 'tarball bytes, a different length' > "$work/corvid-linux-x64.tar.gz"

fail=0
check() { if eval "$2"; then echo "  ok    $1"; else echo "  FAIL  $1"; fail=1; fi; }

"$here/app-manifest.py" "$work" 1234
status=$?
check "the script exits 0" '[ "$status" -eq 0 ]'

apk_sha="$(sha256sum "$work/corvid.apk" | cut -d' ' -f1)"
tar_sha="$(sha256sum "$work/corvid-linux-x64.tar.gz" | cut -d' ' -f1)"
expected="$(printf '{"version":1,"versionCode":1234,"versionName":"0.1.1234","android":{"path":"corvid.apk","sha256":"%s","size":9},"linux":{"path":"corvid-linux-x64.tar.gz","sha256":"%s","size":33}}' "$apk_sha" "$tar_sha")"
actual="$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1])), separators=(",", ":")))' "$work/manifest.json")"

check "the manifest has exactly the documented shape and values" '[ "$actual" = "$expected" ]'

"$here/app-manifest.py" "$work/missing" 1 2> /dev/null
check "a directory without the artifacts fails" '[ $? -ne 0 ]'

exit "$fail"
