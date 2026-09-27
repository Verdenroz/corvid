#!/usr/bin/env bash
# Inspects and prepares a server before Corvid is installed. install.sh pipes it over SSH and
# asks before anything here changes the server.
#
#   prepare-host.sh inspect           what is missing, one "key value" line each
#   prepare-host.sh packages PKG...   installs PKGs with apt-get
#   prepare-host.sh firewall PORT...  allows SSH on each PORT, then enables ufw
set -euo pipefail

declare -A PACKAGE=(
  [podman]=podman [ufw]=ufw [envsubst]=gettext-base [dig]=bind9-dnsutils [curl]=curl
  [openssl]=openssl [python3]=python3 [nft]=nftables [ss]=iproute2
)

inspect() {
  local cmd
  for cmd in "${!PACKAGE[@]}"; do
    command -v "$cmd" >/dev/null || echo "missing ${PACKAGE[$cmd]}"
  done
  command -v apt-get >/dev/null && echo "apt yes"
  command -v podman >/dev/null && echo "podman $(podman --version | awk '{print $3}')"
  if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
    echo "ufw active"
    return
  fi
  sshd -T 2>/dev/null | awk '$1 == "port" {print "ssh-port", $2}'
  command -v ss >/dev/null || return 0
  ss -tlnpH | awk '{
    n = split($4, part, ":"); port = part[n]; addr = substr($4, 1, length($4) - length(port) - 1)
    if (addr ~ /^(127\.|\[::1\]|::1)/) next
    name = "unknown"; if (match($0, /users:\(\("[^"]+/)) name = substr($0, RSTART + 9, RLENGTH - 9)
    print "listening", port, name
  }' | sort -u
}

case "${1:-}" in
  inspect) inspect ;;
  packages)
    shift
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq "$@" >/dev/null
    ;;
  firewall)
    shift
    for port in "$@"; do ufw allow "$port/tcp" >/dev/null; done
    ufw --force enable >/dev/null
    ;;
  *) echo "usage: $(basename "$0") inspect | packages PKG... | firewall PORT..." >&2; exit 64 ;;
esac
