#!/usr/bin/env bash
# Sets up the managed Linux desktop install from a corvid-linux-x64.tar.gz you already fetched:
#   scripts/install-desktop.sh [--version-code N] corvid-linux-x64.tar.gz
# The app updates itself from there; see "Desktop app" in deploy/RUNBOOK.md.
set -euo pipefail

code=0
if [ "${1:-}" = "--version-code" ]; then
  code="${2:?--version-code needs a number}"
  shift 2
fi
tarball="${1:?usage: install-desktop.sh [--version-code N] TARBALL}"
case "$code" in '' | *[!0-9]*) echo "version code must be a number" >&2; exit 2 ;; esac

data="${XDG_DATA_HOME:-$HOME/.local/share}/corvid"
versions="$data/versions"
partial="$versions/.partial-$code"

rm -rf "$partial"
mkdir -p "$partial" "$HOME/.local/bin" "${XDG_DATA_HOME:-$HOME/.local/share}/applications"
trap 'rm -rf "$partial"' EXIT
tar -xzf "$tarball" -C "$partial"
[ -x "$partial/corvid/bin/corvid" ] || { echo "the tarball has no corvid/bin/corvid launcher" >&2; exit 1; }

rm -rf "${versions:?}/$code"
mv "$partial" "$versions/$code"
ln -sfn "versions/$code" "$data/.current-tmp"
mv -T "$data/.current-tmp" "$data/current"
ln -sfn "$data/current/corvid/bin/corvid" "$HOME/.local/bin/corvid"

cat > "${XDG_DATA_HOME:-$HOME/.local/share}/applications/corvid.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Corvid
Exec=$data/current/corvid/bin/corvid
Icon=$data/current/corvid/lib/corvid.png
Categories=Network;Email;
EOF

echo "installed version $code; run corvid (make sure ~/.local/bin is on PATH)"

if ! { PATH="$PATH:/sbin:/usr/sbin" ldconfig -p 2>/dev/null | grep -q 'libfido2\.so'; }; then
  echo "warning: libfido2 not found; security-key sign-in needs it (libfido2-1) and udev rules that let your user open the key (libu2f-udev)" >&2
fi
