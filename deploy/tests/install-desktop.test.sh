#!/usr/bin/env bash
# Runs install-desktop.sh against a fake tarball in a throwaway HOME: the layout, the symlinks,
# the desktop entry, and that a tarball without an executable launcher changes nothing.
set -uo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail=0
check() { if ! "$@"; then echo "FAIL: $*"; fail=1; fi; }

make_tarball() { # name mode
  mkdir -p "$work/src-$1/corvid/bin" "$work/src-$1/corvid/lib"
  printf '#!/bin/sh\necho %s\n' "$1" > "$work/src-$1/corvid/bin/corvid"
  chmod "$2" "$work/src-$1/corvid/bin/corvid"
  tar -czf "$work/$1.tar.gz" -C "$work/src-$1" corvid
}

export HOME="$work/home"
unset XDG_DATA_HOME
mkdir -p "$HOME"
make_tarball good 755
make_tarball bad 644

bash "$here/scripts/install-desktop.sh" --version-code 7 "$work/good.tar.gz" > /dev/null
check test -x "$HOME/.local/share/corvid/versions/7/corvid/bin/corvid"
check test "$(readlink "$HOME/.local/share/corvid/current")" = "versions/7"
check test "$("$HOME/.local/bin/corvid")" = "good"
check grep -q "^Exec=$HOME/.local/share/corvid/current/corvid/bin/corvid$" "$HOME/.local/share/applications/corvid.desktop"

bash "$here/scripts/install-desktop.sh" --version-code 8 "$work/bad.tar.gz" > /dev/null 2>&1
check test $? -ne 0
check test "$(readlink "$HOME/.local/share/corvid/current")" = "versions/7"
check test ! -e "$HOME/.local/share/corvid/versions/8"
check test ! -e "$HOME/.local/share/corvid/versions/.partial-8"

XDG_DATA_HOME="$work/xdg" bash "$here/scripts/install-desktop.sh" "$work/good.tar.gz" > /dev/null
check test -x "$work/xdg/corvid/versions/0/corvid/bin/corvid"

exit $fail
