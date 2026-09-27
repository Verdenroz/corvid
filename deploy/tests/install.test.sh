#!/usr/bin/env bash
# Checks install.sh refuses bad arguments before it contacts any server.
set -uo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
ssh-keygen -q -t ed25519 -N '' -f "$work/key"
cp "$work/key" "$work/open-key"; chmod 644 "$work/open-key"
echo "not a key" > "$work/junk"; chmod 600 "$work/junk"

fail=0
expect() {
  local what="$1" status="$2" text="$3"; shift 3
  out="$(bash "$here/install.sh" "$@" 2>&1 </dev/null)"; got=$?
  if [[ $got -eq $status && "$out" == *"$text"* ]]; then echo "  ok    $what"
  else echo "  FAIL  $what (exit $got): $out"; fail=1; fi
}
expect "no arguments ask for the key" 64 "missing --ssh-key"
expect "a key without a target asks for it" 64 "missing USER@SERVER" --ssh-key "$work/key"
expect "--help shows the options" 64 "--ship-local" --help
expect "an unknown option is named" 64 "unknown option --frobnicate" --frobnicate
expect "a target without a login user is refused" 1 "names the login user" --ssh-key "$work/key" example.com
expect "a missing key file is refused" 1 "is not a readable file" --ssh-key "$work/none" root@example.com
expect "a key others can read is refused" 1 "readable by others" --ssh-key "$work/open-key" root@example.com
expect "a file that is not a key is refused" 1 "is not an SSH private key" --ssh-key "$work/junk" root@example.com
exit "$fail"
