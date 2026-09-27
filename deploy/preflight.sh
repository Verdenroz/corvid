#!/usr/bin/env bash
# Checks a server against the installer's answers before anything is installed. install.sh
# pipes it over SSH; it changes nothing and exits 1 if any check fails.
#
#   preflight.sh HOSTNAME PUBLIC_IP WEB_PORT REPLACE CREATE_A
#
# REPLACE is 1 when --replace was given, and CREATE_A is 1 when install.sh will create
# HOSTNAME's A record itself.
set -uo pipefail
hostname="$1" ip="$2" web_port="$3" replace="$4" create_a="$5"

fail=0
bad() { echo "  FAIL  $*"; fail=1; }
ok() { echo "  ok    $*"; }

if [[ "$create_a" == 1 ]]; then
  ok "$hostname has no A record yet; one pointing to $ip will be created"
elif [[ "$(dig +short A "$hostname" | tail -1)" == "$ip" ]]; then
  ok "$hostname resolves to $ip"
else
  bad "$hostname does not resolve to this server's $ip"
fi
if [[ "$(dig +short -x "$ip" | sed 's/\.$//')" == "$hostname" ]]; then
  ok "the reverse DNS (PTR) of $ip is $hostname"
else
  bad "the reverse DNS (PTR) of $ip is not $hostname; set it in your provider's control panel"
fi
if timeout 6 bash -c '</dev/tcp/gmail-smtp-in.l.google.com/25' 2>/dev/null; then
  ok "outbound port 25 is open"
else
  bad "outbound port 25 is blocked; ask your provider to open it (some never do)"
fi

failed_before_ports=$fail
fail=0
for port in 25 465 587 993 80 "$web_port" 8080 8443; do
  ss -tlnH "sport = :$port" | grep -q . || continue
  owner=$(podman ps --format '{{.Names}}|{{.PodName}}|{{.Ports}}' 2>/dev/null |
    awk -F'|' -v w=":$port->" 'index($3, w) { print ($2 == "corvid" ? "corvid" : $1); exit }')
  case "$owner" in
    corvid) ;;
    "") bad "port $port is used by: $(ss -tlnpH "sport = :$port" | grep -o 'users:(("[^"]*' | cut -d'"' -f2 | head -1)" ;;
    *)
      if [[ "$replace" == 1 ]]; then ok "port $port: container $owner will be replaced"
      else bad "port $port is held by container $owner; rerun with --replace to remove it"; fi ;;
  esac
done
[[ $fail == 1 ]] || ok "ports 25 465 587 993 80 $web_port are free for Corvid"
exit $(( fail | failed_before_ports ))
