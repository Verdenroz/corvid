#!/usr/bin/env bash
# Checks whether an IP is fit to send mail from: blocklists, neighbours, VPN flags, port 25.
# Run it ON the candidate VPS, off any VPN, before paying for it. Any hard failure means
# get a different IP.
set -uo pipefail

readonly IP="${1:-}"

if [[ ! "$IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  echo "usage: $(basename "$0") <ipv4>" >&2
  exit 64
fi

readonly RED=$'\e[31m' GRN=$'\e[32m' YLW=$'\e[33m' DIM=$'\e[2m' RST=$'\e[0m'

fails=0 warns=0 unchecked=0 zen_usable=0

hard_fail() { echo "  ${RED}FAIL${RST}  $*"; ((fails++)); }
soft_warn() { echo "  ${YLW}WARN${RST}  $*"; ((warns++)); }
skipped()   { echo "  ${DIM}skip${RST}  $*"; ((unchecked++)); }
pass()      { echo "  ${GRN}ok${RST}    $*"; }
note()      { echo "  ${DIM}$*${RST}"; }

reverse_ip() { local IFS=.; read -ra o <<<"$1"; echo "${o[3]}.${o[2]}.${o[1]}.${o[0]}"; }

# Minimal VPS images often ship without dig, and this script is meant to run on one.
if command -v dig >/dev/null; then RESOLVER=dig
elif command -v host >/dev/null; then RESOLVER=host
elif command -v getent >/dev/null; then RESOLVER=getent
elif command -v python3 >/dev/null; then RESOLVER=python
else echo "no usable DNS lookup tool (dig, host, getent, python3)" >&2; exit 69
fi

resolve_a() {
  case "$RESOLVER" in
    dig)    dig +short +time=3 +tries=2 "$1" A 2>/dev/null ;;
    host)   host -W 3 -t A "$1" 2>/dev/null | awk '/has address/{print $NF}' ;;
    getent) getent hosts "$1" 2>/dev/null | awk '{print $1}' ;;
    python) python3 -c 'import socket,sys
try: print("\n".join(socket.gethostbyname_ex(sys.argv[1])[2]))
except Exception: pass' "$1" ;;
  esac
}

query() { resolve_a "${2}.${1}" | grep -E '^127\.' | head -1; }

# Every DNSBL lists 127.0.0.2 and never lists 127.0.0.1. A zone that fails this
# pair is refusing us (VPN/public resolver) and its answers mean nothing.
zone_usable() {
  local zone="$1"
  [[ "$(query "$zone" 2.0.0.127)" == 127.0.0.[0-9]* ]] || return 1
  [[ -z "$(query "$zone" 1.0.0.127)" ]] || return 1
}

declare -A ZONES=(
  [zen.spamhaus.org]="Spamhaus ZEN"
  [bl.spamcop.net]="SpamCop"
  [b.barracudacentral.org]="Barracuda"
  [dnsbl-1.uceprotect.net]="UCEPROTECT L1"
)

echo
echo "Vetting ${IP} as a mail-sending IP"
note "lookup tool: $RESOLVER"
  note "upstream resolver: $(resolvectl status 2>/dev/null | awk '/Current DNS Server:/{print $4; exit}' || true)$(grep -m1 ^nameserver /etc/resolv.conf 2>/dev/null | awk '{print $2}')"
echo

REVERSED="$(reverse_ip "$IP")"
readonly REVERSED

echo "[1/5] Blocklists"
for zone in zen.spamhaus.org bl.spamcop.net b.barracudacentral.org dnsbl-1.uceprotect.net; do
  name="${ZONES[$zone]}"
  if ! zone_usable "$zone"; then
    skipped "$name: zone refused our queries, result unknown"
    continue
  fi
  [[ "$zone" == zen.spamhaus.org ]] && zen_usable=1
  answer="$(query "$zone" "$REVERSED")"
  if [[ -z "$answer" ]]; then
    pass "$name"
  else
    hard_fail "$name LISTED ($answer)"
  fi
done
(( unchecked > 0 )) && note "unchecked lists are usually a VPN or public resolver; rerun on the VPS"

echo
echo "[2/5] Network reputation (UCEPROTECT L2/L3)"
for zone in dnsbl-2.uceprotect.net dnsbl-3.uceprotect.net; do
  if ! zone_usable "$zone"; then skipped "$zone"; continue; fi
  answer="$(query "$zone" "$REVERSED")"
  [[ -z "$answer" ]] && pass "$zone" || soft_warn "$zone listed ($answer)"
done
note "L2/L3 list whole networks and ASNs and are widely considered aggressive;"
note "a listing is a signal about the neighbourhood, not a verdict on this IP."

echo
echo "[3/5] Neighbourhood: PBL across the /24"
if (( zen_usable == 0 )); then
  skipped "needs Spamhaus, which is not answering us"
else
  prefix="${IP%.*}"; hits=0; checked=0
  for last in 1 20 40 60 80 100 120 140 160 180 200 220 240 254; do
    [[ -n "$(query zen.spamhaus.org "$(reverse_ip "${prefix}.${last}")")" ]] && ((hits++))
    ((checked++))
  done
  if (( hits == 0 )); then
    pass "0/${checked} sampled neighbours listed"
  elif (( hits * 3 >= checked )); then
    hard_fail "${hits}/${checked} sampled neighbours listed; the /24 is dirty"
    note "Gmail and Microsoft score the neighbourhood, not just your address"
  else
    soft_warn "${hits}/${checked} sampled neighbours listed"
  fi
fi

echo
echo "[4/5] VPN/proxy classification"
geo="$(curl -s --max-time 10 "https://ipinfo.io/${IP}/json" 2>/dev/null)"
if [[ -z "$geo" ]]; then
  skipped "ipinfo unreachable"
else
  org="$(sed -n 's/.*"org"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$geo")"
  [[ -n "$org" ]] && note "org: $org"
  if grep -qiE '"(vpn|proxy|tor)"[[:space:]]*:[[:space:]]*true' <<<"$geo"; then
    hard_fail "flagged as VPN/proxy/tor"
  else
    pass "not flagged as VPN or proxy"
  fi
  note "ipinfo's privacy fields need a paid token; cross-check at scamalytics.com"
fi

echo
echo "[5/5] Outbound port 25"
port25_open() {
  if command -v nc >/dev/null; then
    nc -w 5 -z "$1" 25 2>/dev/null
  else
    timeout 8 bash -c "exec 3<>/dev/tcp/$1/25" 2>/dev/null
  fi
}
if ! command -v nc >/dev/null && ! (exec 3<>/dev/null) 2>/dev/null; then
  skipped "no way to test TCP connect on this host"
elif port25_open gmail-smtp-in.l.google.com || port25_open outlook-com.olc.protection.outlook.com; then
  pass "port 25 egress open"
else
  soft_warn "port 25 egress blocked"
fi
note "only meaningful when run ON the candidate VPS"

echo
echo "Manual steps this script cannot do:"
echo "  - Confirm the provider grants PTR/rDNS control on this IP"
echo "  - Read the provider AUP for mail-server clauses"
echo "  - Register the IP with Microsoft SNDS and check for adverse history"
echo "  - swaks a test to Gmail, read Authentication-Results, check placement"
echo "  - Cross-check at multirbl.valli.org (~100 lists)"

echo
if (( fails > 0 )); then
  echo "${RED}REJECT${RST}  ${fails} hard failure(s). Reprovision to roll a different IP."
  exit 1
fi
if (( unchecked > 0 )); then
  echo "${YLW}INCONCLUSIVE${RST}  ${unchecked} list(s) unchecked, ${warns} warning(s)."
  echo "Do not treat this as a pass. Rerun from the VPS, off any VPN."
  exit 2
fi
echo "${GRN}PASS${RST}  0 hard failures, ${warns} warning(s). Now complete the manual steps."
