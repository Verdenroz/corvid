#!/usr/bin/env bash
# Removes Corvid from this host: pod, containers, units, masks and the firewall rules
# bootstrap.sh added. Mail, keys, config and images stay unless you pass --purge.
# Nothing else on the host is touched.
set -euo pipefail

QUADLET_DIR=/etc/containers/systemd
CORVID_DIR=/opt/corvid
CONTAINER_SUBNET=10.88.0.0/16
purge=0
case "${1:-}" in
  --purge) purge=1 ;;
  "") ;;
  *) echo "usage: $(basename "$0") [--purge]" >&2; exit 64 ;;
esac
[[ $EUID -eq 0 ]] || { echo "must run as root" >&2; exit 1; }

images="$(sed -n 's/^Image=//p' "$QUADLET_DIR"/corvid-*.container 2>/dev/null; cat "$CORVID_DIR/image.ref" 2>/dev/null)" || true

echo "== stopping"
systemctl stop corvid-pod.service 2>/dev/null || true
podman pod rm -f corvid >/dev/null 2>&1 || true
podman ps -aq --filter 'name=^corvid-' | xargs -r podman rm -f >/dev/null

echo "== units"
systemctl disable --now corvid-update.timer corvid-update.path 2>/dev/null || true
rm -f /etc/systemd/system/corvid-update.{service,timer,path}
rm -f "$QUADLET_DIR"/corvid.pod "$QUADLET_DIR"/corvid-*.container
# Masks are symlinks to /dev/null; removing them leaves nothing for the next install to trip on.
find /etc/systemd/system -maxdepth 1 -name 'corvid-*.service' -lname /dev/null -delete
systemctl daemon-reload

echo "== firewall"
if command -v ufw >/dev/null; then
  # Delete by number, highest first, so earlier numbers stay valid.
  # Without --purge the egress rule, which starts "Anywhere", stays for the next install.
  pattern="ALLOW FWD"
  [[ $purge -eq 1 ]] || pattern="^\[ *[0-9]+\] +${CONTAINER_SUBNET//./\\.} .*ALLOW FWD"
  ufw status numbered | grep -F "$CONTAINER_SUBNET" | grep -E "$pattern" |
    sed -nE 's/^\[ *([0-9]+)\].*/\1/p' | sort -rn |
    while read -r n; do ufw --force delete "$n" >/dev/null; done
  echo "  removed Corvid's route rules$([[ $purge -eq 1 ]] && echo ", including container egress")"
fi

if [[ $purge -eq 1 ]]; then
  echo "== purging data, images and tools"
  podman volume rm -f corvid-unbound-keys >/dev/null 2>&1 || true
  sort -u <<<"$images" | xargs -r podman rmi -f >/dev/null 2>&1 || true
  rm -rf "$CORVID_DIR"
  rm -f /usr/local/bin/stalwart-cli
  echo "  removed $CORVID_DIR, the unbound anchor volume, the images Corvid ran and stalwart-cli"
else
  echo "== kept $CORVID_DIR (mail, keys, config); rerun with --purge to delete it"
fi
