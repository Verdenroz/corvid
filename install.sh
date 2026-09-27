#!/usr/bin/env bash
# Installs or updates Corvid on a server, run from your own machine over SSH.
#
#   ./install.sh --ssh-key FILE [--image REF | --ship-local] [--replace] USER@SERVER
#
#   --ssh-key FILE   private key that logs in to SERVER (required; nothing is guessed)
#   --image REF      Corvid image to run (default: this checkout's release on GHCR)
#   --ship-local     build the image here and copy it to SERVER instead of pulling it
#   --replace        remove a container that already holds the mail ports
#   USER@SERVER      where to install: root, or a user with passwordless sudo
#
# On a fresh Debian or Ubuntu server it offers to install the packages Corvid needs and to
# turn on the firewall with SSH allowed. It then asks every question, checks the token and the
# server, and shows the answers for review before changing anything else. After that it
# installs, publishes DNS, waits for certificates and verifies with no further input. On a
# rerun each question offers the value on the server, and Enter keeps it, including the stored
# Cloudflare token and password.
# Unattended: set CORVID_DOMAIN, CORVID_HOSTNAME, CORVID_MAIL_USER, CORVID_ACME_CONTACT,
# CORVID_WEB_PORT, CORVID_CLIENT_ACCESS, CORVID_CLOUDFLARE_TOKEN and CORVID_MAIL_PASSWORD; a bad value then stops it,
# and on a rerun any left unset keep the value on the server.
set -euo pipefail
(( BASH_VERSINFO[0] >= 4 )) || { echo "install.sh needs bash 4 or newer (macOS: brew install bash, then run it with that bash)" >&2; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/deploy/mta-sts-lib.sh"
VERSION="$(sed -nE 's/^version = "([^"]+)"/\1/p' "$HERE/Cargo.toml" | head -1)"
IMAGE="ghcr.io/verdenroz/corvid:$VERSION"
CLI_VERSION=1.0.12
declare -A CLI_SHA256=(
  [x86_64]=76fcd7250a10c7bee704dc4a08000b3faca6b5a22895d41831c0c37efd95acce
  [aarch64]=59dff5d52180df669eec705eb3fe2b80cee9267291e88123bd72af855d4c5bc3
)
# Must match POD_PORTS in crates/corvid-config: every container shares one network namespace.
POD_PORTS=(25 80 465 587 993 1411 5335 8080 8081 8443 11332)
key=""
host=""
ship_local=0
replace=0

usage() { sed -n '4,11p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 64; }
die() { printf '\nFAIL: %s\n' "$*" >&2; exit 1; }
while (( $# )); do
  case "$1" in
    --ssh-key) [[ $# -ge 2 ]] || usage; key="$2"; shift 2 ;;
    --image) [[ $# -ge 2 ]] || usage; IMAGE="$2"; shift 2 ;;
    --ship-local) ship_local=1; IMAGE="localhost/corvid:$VERSION"; shift ;;
    --replace) replace=1; shift ;;
    -h|--help) usage ;;
    -*) printf 'unknown option %s\n\n' "$1" >&2; usage ;;
    *) [[ -z "$host" ]] || usage; host="$1"; shift ;;
  esac
done
[[ -n "$key" ]] || { printf 'missing --ssh-key: the private key that logs in to the server\n\n' >&2; usage; }
[[ -n "$host" ]] || { printf 'missing USER@SERVER\n\n' >&2; usage; }
[[ "$host" == ?*@?* ]] || die "the target names the login user, e.g. root@$host"
login="${host%%@*}"
server="${host#*@}"

step() { printf '\n== %s\n' "$*"; }
explain() { printf '  %s\n' "$@"; }
ok() { printf '  ok    %s\n' "$*"; }
warn() { printf '  warn  %s\n' "$*"; }
# Runs a command on the server as root; a non-root login goes through sudo.
on_host() {
  if [[ "$login" == root ]]; then ssh "${ssh_opts[@]}" "$host" "$@"
  else ssh "${ssh_opts[@]}" "$host" "sudo -n bash -c $(printf '%q' "$*")"; fi
}
# Pipes one of the deploy/ scripts to the server and runs it there with ARGS.
run_on_host() { local script="$1"; shift; on_host "bash -s -- $*" < "$HERE/deploy/$script"; }
interactive() { [[ -t 0 ]]; }
# Checks that PORT on the server answers with GREETING, then takes its leave with GOODBYE.
# Stalwart bans an address for one invalid command, so this speaks the protocol exactly.
greets() {
  local port="$1" greeting="$2" goodbye="$3" answer; shift 3
  answer="$({ sleep 3; printf '%s\r\n' "$goodbye"; sleep 2; } |
    with_timeout 15 openssl s_client -quiet -connect "$server_ip:$port" -servername "$hostname" "$@" 2>/dev/null)" || true
  grep -qE "$greeting" <<<"$answer"
}
with_timeout() {
  local seconds="$1"; shift
  if command -v timeout >/dev/null; then timeout "$seconds" "$@"
  else perl -e 'alarm shift; exec @ARGV' "$seconds" "$@"; fi
}
# Asks a yes/no question that defaults to yes; without a terminal the answer is yes.
confirm() {
  local answer
  interactive || return 0
  read -r -p "  $1 [Y/n] " answer
  [[ -z "$answer" || "$answer" =~ ^[yY] ]]
}

# Asks until the answer passes its check; Enter takes CURRENT, else SUGGESTED. A preset from the
# environment gets one try, and without a terminal the default is taken.
ask() {
  local prompt="$1" check="$2" preset="$3" current="${4:-${5:-}}" answer
  if [[ -n "$preset" ]]; then
    "$check" "$preset" || die "$prompt: \"$preset\" $(why_$check)"
    printf '%s' "$preset"; return
  fi
  if ! interactive; then
    [[ -n "$current" ]] || die "$prompt is needed; run this in a terminal or set its CORVID_ variable"
    printf '%s' "$current"; return
  fi
  while true; do
    read -r -p "  $prompt${current:+ [$current]}: " answer
    answer="${answer,,}"
    answer="${answer:-$current}"
    "$check" "$answer" && { printf '%s' "$answer"; return; }
    printf '    "%s" %s\n' "$answer" "$(why_$check)" >&2
  done
}
# Reads a secret one key at a time, echoing * per character; Backspace deletes the last one.
read_masked() {
  local prompt="$1" secret="" char
  printf '%s' "$prompt" >&2
  while IFS= read -r -s -n 1 char; do
    case "$char" in
      "") break ;;
      $'\x7f'|$'\b')
        [[ -n "$secret" ]] && { secret="${secret%?}"; printf '\b \b' >&2; } ;;
      *) secret+="$char"; printf '*' >&2 ;;
    esac
  done
  echo >&2
  printf '%s' "$secret"
}
# Prints the new secret, or nothing to keep the one already STORED on the server. CONFIRM
# set to "once" skips typing it twice, for a secret checked right after.
ask_secret() {
  local prompt="$1" preset="$2" stored="$3" confirm="${4:-}" first second
  [[ -n "$preset" ]] && { printf '%s' "$preset"; return; }
  if ! interactive; then
    [[ -n "$stored" ]] || die "$prompt is needed; run this in a terminal or set its CORVID_ variable"
    return
  fi
  while true; do
    first="$(read_masked "  $prompt${stored:+ (Enter keeps the current one)}: ")"
    [[ -z "$first" && -n "$stored" ]] && return
    [[ -n "$first" && "$confirm" == once ]] && { printf '%s' "$first"; return; }
    second="$(read_masked "  Again: ")"
    [[ -n "$first" && "$first" == "$second" ]] && { printf '%s' "$first"; return; }
    echo "    the two entries were empty or did not match; try again" >&2
  done
}
is_name() { [[ "$1" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]]; }
why_is_name() { echo "is not a domain name like example.com"; }
is_address() { [[ "$1" =~ ^[a-z0-9]([a-z0-9._+-]*[a-z0-9])?@(.+)$ ]] && is_name "${BASH_REMATCH[2]}"; }
why_is_address() { echo "is not an address like you@example.com (letters, digits and .-_+ before the @)"; }
is_contact() { [[ "$1" =~ ^[a-z0-9._+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$ && "$1" != *"@$domain" ]]; }
why_is_contact() { echo "is not an email address at a provider other than $domain"; }
is_port() { [[ "$1" =~ ^[0-9]+$ ]] && (( $1 >= 1 && $1 <= 65535 )) && [[ " ${POD_PORTS[*]} " != *" $1 "* ]]; }
# client_access: anywhere, host, or IPv4 networks given with or without brackets and commas.
access_list() { tr -d '[]' <<<"$1" | tr ',' ' ' | xargs; }
is_access() {
  [[ "$1" == anywhere || "$1" == host ]] && return
  local network octet list
  list="$(access_list "$1")"
  [[ -n "$list" ]] || return 1
  for network in $list; do
    [[ "$network" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}(/([0-9]|[12][0-9]|3[0-2]))?$ ]] || return 1
    for octet in $(tr '.' ' ' <<<"${network%/*}"); do (( 10#$octet <= 255 )) || return 1; done
  done
}
why_is_access() { echo "is not anywhere, host, or IPv4 networks such as 192.0.2.0/24"; }
access_yaml() {
  case "$1" in
    anywhere|host) printf '%s' "$1" ;;
    *) printf '[%s]' "$(access_list "$1" | sed 's/ /, /g')" ;;
  esac
}
why_is_port() { echo "is not a port Corvid can use (1-65535, and not one the pod already uses: ${POD_PORTS[*]})"; }

step "checking the SSH key"
[[ -f "$key" && -r "$key" ]] || die "$key is not a readable file"
mode="$(stat -c %a "$key" 2>/dev/null || stat -f %Lp "$key")"
[[ "$mode" =~ ^[46]00$ ]] || die "$key is readable by others; ssh refuses such keys (chmod 600 $key)"
fingerprint="$(ssh-keygen -l -f "$key" 2>/dev/null | awk '{print $2}')" || true
[[ -n "$fingerprint" ]] || die "$key is not an SSH private key"
if ! ssh-keygen -y -P '' -f "$key" >/dev/null 2>&1; then
  ssh-add -l 2>/dev/null | grep -qF "$fingerprint" ||
    die "$key has a passphrase; load it first with: ssh-add $key"
fi
ok "$key ($fingerprint)"
ssh_opts=(-i "$key" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15)

step "connecting to $server"
if ! ssh-keygen -F "$server" >/dev/null 2>&1; then
  scanned="$(ssh-keyscan -T 10 -t ed25519 "$server" 2>/dev/null)" || true
  [[ -n "$scanned" ]] || die "no SSH answer from $server on port 22"
  host_fp="$(ssh-keygen -l -f - <<<"$scanned" | awk '{print $2}')"
  echo "  first connection: $server presents host key $host_fp"
  if [[ "${CORVID_HOST_KEY:-}" == "$host_fp" ]]; then
    ok "matches CORVID_HOST_KEY"
  else
    interactive || die "unknown host key; compare it with your provider's console, then set CORVID_HOST_KEY=$host_fp"
    read -r -p "  Is this your server's key (check your provider's console)? [y/N] " yes
    [[ "$yes" =~ ^[yY] ]] || die "host key not confirmed"
  fi
  printf '%s\n' "$scanned" >> "$HOME/.ssh/known_hosts"
fi
ssh "${ssh_opts[@]}" "$host" true 2>/dev/null ||
  die "$key does not log in to $host; add its public key to ~$login/.ssh/authorized_keys there"
if [[ "$login" != root ]]; then
  ssh "${ssh_opts[@]}" "$host" 'sudo -n true' 2>/dev/null ||
    die "$login cannot use sudo on $server without a password, or sudo is not installed; allow it (NOPASSWD in sudoers) or log in as root"
fi
[[ "$(on_host id -u)" == 0 ]] || die "$host could not act as root"
arch="$(on_host uname -m)"
[[ -n "${CLI_SHA256[$arch]:-}" ]] || die "unsupported architecture $arch; stalwart-cli ships for x86_64 and aarch64 only"
via=""
[[ "$login" == root ]] || via=", using sudo"
ok "logged in as $login$via ($arch)"

step "preparing $server"
host_facts="$(run_on_host prepare-host.sh inspect)"
fact() { awk -v k="$1" '$1 == k {$1 = ""; sub(/^ /, ""); print}' <<<"$host_facts"; }
packages="$(fact missing | sort -u | tr '\n' ' ')"
if [[ -n "$packages" ]]; then
  [[ -n "$(fact apt)" ]] ||
    die "$server is missing: ${packages}and has no apt-get; install them with its package manager and rerun"
  explain "Corvid needs these packages, which $server does not have: $packages"
  confirm "Install them now with apt-get?" || die "install them and rerun; nothing has been changed"
  run_on_host prepare-host.sh packages "$packages" || die "apt-get could not install them; see the output above"
  host_facts="$(run_on_host prepare-host.sh inspect)"
  ok "installed $packages"
fi
podman_version="$(fact podman)"
[[ "$(printf '%s\n' 4.4 "$podman_version" | sort -V | head -1)" == 4.4 ]] ||
  die "podman $podman_version is too old: Corvid runs its services as quadlets, which need 4.4 or newer (Debian 13, Ubuntu 24.04)"
if [[ "$(fact ufw)" != active ]]; then
  ssh_ports="$( { fact ssh-port; ssh -G "$host" 2>/dev/null | awk '$1 == "port" {print $2}'; } | sort -un | tr '\n' ' ')"
  others="$(fact listening | awk -v ssh=" $ssh_ports" 'index(ssh, " " $1 " ") == 0 {print $1 " (" $2 ")"}' | sort -un | tr '\n' ' ')"
  explain "The firewall (ufw) is off. Corvid turns it on, allowing SSH on port ${ssh_ports% } and then" \
    "only the ports Corvid itself opens."
  [[ -z "$others" ]] ||
    explain "Other programs listening here would be blocked from outside: $others"
  confirm "Turn on the firewall now?" || die "enable ufw yourself, allowing SSH, and rerun; nothing has been changed"
  run_on_host prepare-host.sh firewall "$ssh_ports"
  ok "firewall on, SSH allowed on port ${ssh_ports% }"
fi
ok "the packages Corvid needs are installed, podman $podman_version, and ufw is on"
existing_config="$(on_host 'cat /opt/corvid/config.yaml 2>/dev/null' || true)"
stored_token="$(on_host 'test -s /opt/corvid/secrets/cloudflare.token && echo yes' || true)"
stored_password="$(on_host 'test -e /opt/corvid/.password-set && echo yes' || true)"
current() {
  awk -v k="$1:" '/^server:/ {s = 1; next} /^[^ ]/ {s = 0} s && $1 == k {sub(/^[^:]*: */, ""); gsub(/["\047]/, ""); print}' <<<"$existing_config"
}

# Runs deploy/cloudflare.py on the server with TOKEN, or the stored token when TOKEN is empty.
# The program travels as base64 so no shell between here and python3 can alter it. CLOUDFLARE_STDIN,
# when set, rides after the token line: deploy-worker's script body has nowhere else to go.
cloudflare() {
  local token="$1"; shift
  { if [[ -n "$token" ]]; then printf '%s\n' "$token"; else on_host 'cat /opt/corvid/secrets/cloudflare.token'; echo; fi
    printf '%s' "${CLOUDFLARE_STDIN:-}"; } |
    on_host "python3 -c 'import base64; exec(base64.b64decode(\"$CLOUDFLARE_PY\"))' $*"
}
CLOUDFLARE_PY="$(base64 < "$HERE/deploy/cloudflare.py" | tr -d '\n')"
# Prints "ZONE ZONE_ID DOMAIN_ID": the editable zone holding the hostname, and the mail domain's
# zone id, once the token can edit both.
token_host_zone() {
  local report editable name id best="" best_id=""
  report="$(cloudflare "$1" zones 2>&1)" || { echo "    Cloudflare refused the token: $report" >&2; return 1; }
  editable="$(awk '$3 == "edit" {print $1, $2}' <<<"$report")"
  awk '{print $1}' <<<"$editable" | grep -qx "$domain" ||
    { echo "    the token cannot edit DNS in $domain; it sees: $(awk '{print $1, $3}' <<<"$report" | tr '\n' ' ')" >&2; return 1; }
  while read -r name id; do
    if [[ "$hostname" == "$name" || "$hostname" == *".$name" ]] && (( ${#name} > ${#best} )); then
      best="$name" best_id="$id"
    fi
  done <<<"$editable"
  [[ -n "$best" ]] || { echo "    the token cannot edit the zone holding $hostname, which its certificate needs" >&2; return 1; }
  printf '%s %s %s' "$best" "$best_id" "$(awk -v d="$domain" '$1 == d {print $2}' <<<"$editable")"
}

echo
explain "Corvid needs seven answers. Nothing on $server changes until you have seen them all" \
  "and said yes."
[[ -z "$existing_config" ]] ||
  explain "Corvid is already installed there, so each question shows the current value in" \
    "brackets. Press Enter to keep it."

step "1 of 7: your email address"
explain "The one mailbox this server will host. Its domain must be on Cloudflare."
current_address=""
[[ -n "$(current mail_user)" ]] && current_address="$(current mail_user)@$(current domain)"
preset_address=""
[[ -n "${CORVID_MAIL_USER:-}" && -n "${CORVID_DOMAIN:-}" ]] && preset_address="$CORVID_MAIL_USER@$CORVID_DOMAIN"
address="$(ask "Address" is_address "$preset_address" "$current_address")"
mail_user="${address%@*}"
domain="${address#*@}"
if [[ -n "$(current domain)" && "$domain" != "$(current domain)" ]]; then
  explain "This adds $domain alongside $(current domain). Stalwart keeps $(current domain) and its DNS" \
    "records stay at Cloudflare until you remove them."
fi

step "2 of 7: a password for $address"
explain "Mail apps log in with it. Weak passwords are refused; a few random words work well."
password="$(ask_secret "Password" "${CORVID_MAIL_PASSWORD:-}" "$stored_password")"

step "3 of 7: this server's name"
explain "Other mail servers see this name when yours connects to them, and it must be what the" \
  "reverse DNS (PTR) of the server's IP points to. It can be in a different domain from $domain."
# The address the internet sees, which differs from the interface's on a NAT'd host.
server_ip="$(on_host "curl -4 -s --max-time 10 https://1.1.1.1/cdn-cgi/trace | sed -n 's/^ip=//p'")"
[[ -n "$server_ip" ]] || die "$server could not find its public IPv4 address; it needs outbound HTTPS"
ptr="$(on_host "dig +short -x '$server_ip' | sed 's/\.$//'" || true)"
[[ -n "$ptr" ]] && explain "The reverse DNS of $server_ip points to $ptr."
hostname="$(ask "Name" is_name "${CORVID_HOSTNAME:-}" "$(current hostname)" "$ptr")"

step "4 of 7: the web port"
web_default="$(current web_port)"
if [[ -n "$web_default" ]]; then
  explain "Mail apps sync with Corvid over HTTPS on this port."
elif [[ "$(on_host 'ss -tlnH "sport = :443" | wc -l')" -gt 0 ]]; then
  explain "Mail apps sync with Corvid over HTTPS on this port. Something else on this server" \
    "already uses 443, the usual HTTPS port, so 4443 is suggested."
  web_default=4443
else
  explain "Mail apps sync with Corvid over HTTPS on this port. 443 is the usual one and is free."
  web_default=443
fi
web_port="$(ask "Port" is_port "${CORVID_WEB_PORT:-}" "" "$web_default")"

step "5 of 7: where your mail apps may connect from"
explain "Mail apps reach Corvid on ports 465, 587, 993 and $web_port. Answer anywhere; host, to allow" \
  "only programs on this server, such as a VPN it runs, so your devices must connect through" \
  "that VPN; or IPv4 networks such as 192.0.2.0/24. Incoming mail on port 25 is not affected."
client_access="$(access_yaml "$(ask "Allow" is_access "${CORVID_CLIENT_ACCESS:-}" "$(current client_access)" anywhere)")"

step "6 of 7: an email address for certificate notices"
explain "Let's Encrypt issues this server's certificates and writes here if one is about to expire." \
  "Use an address at another provider: if the certificate lapses, mail to $domain may not arrive."
acme_contact="$(ask "Email" is_contact "${CORVID_ACME_CONTACT:-}" "$(current acme_contact)")"

step "7 of 7: a Cloudflare API token"
explain "Corvid publishes your DNS records and proves domain ownership for certificates through" \
  "Cloudflare. Create a token at https://dash.cloudflare.com/profile/api-tokens with the" \
  "permissions Zone > DNS > Edit and Zone > Zone > Read, for $domain and for the zone that" \
  "holds $hostname. It is checked as soon as you enter it."
token="$(ask_secret "Token" "${CORVID_CLOUDFLARE_TOKEN:-}" "$stored_token" once)"
until zone_and_id="$(token_host_zone "$token")"; do
  if [[ -n "${CORVID_CLOUDFLARE_TOKEN:-}" ]] || ! interactive; then die "fix the token and rerun; nothing has been changed"; fi
  token="$(ask_secret "Token" "" "" once)"
done
read -r host_zone host_zone_id domain_zone_id <<<"$zone_and_id"
also=""
[[ "$host_zone" == "$domain" ]] || also=" and $host_zone, the zone that holds $hostname"
ok "the token can edit $domain$also"
a_records="$(cloudflare "$token" a-records "$host_zone_id" "$hostname")" || die "could not read $hostname's DNS records: $a_records"
create_a=0
if [[ -z "$a_records" ]]; then
  create_a=1
elif ! grep -qx "$server_ip" <<<"$a_records"; then
  die "$hostname points to $(tr '\n' ' ' <<<"$a_records")at Cloudflare, not to this server's $server_ip; fix that record and rerun"
fi

dnssec_off=()
declare -A zone_id=([$domain]="$domain_zone_id" [$host_zone]="$host_zone_id")
for zone in $(printf '%s\n' "$domain" "$host_zone" | sort -u); do
  status="$(cloudflare "$token" dnssec "${zone_id[$zone]}" | awk '{print $1}')" || die "could not read DNSSEC for $zone"
  case "$status" in
    active) ok "DNSSEC is on for $zone" ;;
    pending) ok "DNSSEC is on for $zone, waiting for its DS record at the registrar" ;;
    *) dnssec_off+=("$zone") ;;
  esac
done
# Stalwart leaves records in place when it stops publishing them. Its MTA-STS pair points
# mta-sts at this server, so a pair pointing anywhere else is someone's own policy, left alone.
# A Corvid Worker is identified by its custom domain's service name, not by record content,
# since its own "_mta-sts" TXT looks identical to Stalwart's.
worker_name="$(mta_sts_worker_name "$domain")"
account_id="$(cloudflare "$token" account "$domain_zone_id")" || die "could not read the account that owns $domain: $account_id"
owner="$(cloudflare "$token" worker-owner "$account_id" "mta-sts.$domain")" || die "could not check mta-sts.$domain: $owner"
worker_owned=0
[[ "$owner" == "$worker_name" ]] && worker_owned=1

stale_mta_sts=()
if [[ "$web_port" != 443 ]]; then
  cname="$(cloudflare "$token" records "$domain_zone_id" "mta-sts.$domain" CNAME)" || die "could not read mta-sts.$domain: $cname"
  case "$(mta_sts_record_status "$cname" "$hostname" "$worker_owned")" in
    stale)
      stale_mta_sts+=("${cname%% *}")
      while read -r id content; do
        [[ "$content" == *v=STSv1* ]] && stale_mta_sts+=("$id")
      done < <(cloudflare "$token" records "$domain_zone_id" "_mta-sts.$domain" TXT)
      ;;
    foreign)
      die "mta-sts.$domain already points to ${cname#* }, not $hostname or a Corvid Worker; remove it before installing off 443"
      ;;
  esac
fi

enable_dnssec=0
off_zones="$(printf '%s and ' "${dnssec_off[@]}")"
off_zones="${off_zones% and }"
if (( ${#dnssec_off[@]} )); then
  explain "DNSSEC is off for $off_zones. With it on, the TLSA records Corvid publishes tell" \
    "senders such as Microsoft to deliver to you only over TLS with your real certificate (DANE)." \
    "It signs every record in the zone, not only mail's; turning it off later needs care."
  confirm "Turn on DNSSEC for $off_zones after your final yes?" && enable_dnssec=1
fi
[[ "$host_zone" == "$domain" ]] && host_zone=""

step "checking $server against your answers"
run_on_host preflight.sh "$hostname" "$server_ip" "$web_port" "$replace" "$create_a" ||
  die "fix the items above and rerun; nothing has been changed"
# A listed IP never stops the install: the remedy is at the provider, and some lists are noisy.
listing=0
blocklists="$(on_host "bash -s -- $server_ip" < "$HERE/scripts/vet-ip.sh" 2>&1 | sed 's/\x1b\[[0-9;]*m//g')" || listing=$?
case "$listing" in
  0) ok "$server_ip is on none of the blocklists checked" ;;
  1) grep -E '^  (FAIL|WARN)' <<<"$blocklists" | sed -E 's/^  (FAIL|WARN) +/  warn  /'
     explain "Mail from a listed address is often refused or sent to spam. Providers will usually" \
       "give you a different IP if you ask; the install carries on either way." ;;
  *) warn "some blocklists did not answer, so $server_ip could not be fully checked" ;;
esac

step "your answers"
cat <<EOF
  Address                $address
  Password               $([[ -n "$password" ]] && echo "new" || echo "unchanged")
  Server name            $hostname$([[ $create_a == 1 ]] && echo " (A record to $server_ip will be created)")
  Web address            https://$hostname:$web_port
  Mail apps from         $(case "$client_access" in anywhere) echo "anywhere" ;; host) echo "this server only, such as its VPN" ;; *) echo "$client_access" ;; esac)
  Certificate notices    $acme_contact
  Cloudflare token       $([[ -n "$token" ]] && echo "new" || echo "unchanged")
  DNSSEC                 $(if (( ${#dnssec_off[@]} == 0 )); then echo "on"; elif (( enable_dnssec )); then echo "will be turned on for $off_zones"; else echo "stays off for $off_zones"; fi)
  MTA-STS                $(if [[ "$web_port" == 443 ]]; then echo "published, served on 443"; else echo "published via a Cloudflare Worker on mta-sts.$domain"; fi)$( (( ${#stale_mta_sts[@]} )) && echo "; the records published earlier will be removed")$( [[ "$web_port" == 443 ]] && (( worker_owned )) && echo "; the Worker created earlier will be removed")
  Corvid image           $IMAGE
EOF
if interactive; then
  echo
  read -r -p "Install Corvid on $server with these answers? [Y/n] " yes
  [[ -z "$yes" || "$yes" =~ ^[yY] ]] || { printf '\nNothing has been changed. Rerun to change an answer.\n'; exit 0; }
fi

if (( create_a )); then
  step "creating the A record"
  out="$(cloudflare "$token" create-a "$host_zone_id" "$hostname" "$server_ip" 2>&1)" || die "Cloudflare did not create it: $out"
  ok "$hostname now points to $server_ip"
fi

if (( enable_dnssec )); then
  step "turning on DNSSEC"
  for zone in "${dnssec_off[@]}"; do
    state="$(cloudflare "$token" enable-dnssec "${zone_id[$zone]}" 2>&1)" || die "Cloudflare did not turn on DNSSEC for $zone: $state"
    if [[ "${state%% *}" == active ]]; then ok "DNSSEC on for $zone"
    else ok "DNSSEC on for $zone, pending until its DS record reaches the registry"; fi
    explain "If $zone is registered with Cloudflare, it publishes the DS record itself. Otherwise" \
      "add this DS record at your registrar, or DANE stays inactive: ${state#* }"
  done
fi

step "getting the Corvid image"
if (( ship_local )); then
  engine="$(command -v podman || command -v docker)" || die "--ship-local needs podman or docker on this machine"
  "$engine" build -q -f "$HERE/deploy/Containerfile" -t "$IMAGE" "$HERE" >/dev/null || die "the image did not build"
  ok "$IMAGE built here"
else
  on_host "podman pull -q '$IMAGE' >/dev/null 2>&1" || die "$IMAGE cannot be pulled on $server; pass --image REF or --ship-local"
  ok "$IMAGE pulled"
fi

step "installing"
on_host 'install -d -m 755 /opt/corvid /opt/corvid/deploy && rm -rf /opt/corvid/deploy/*'
tar -C "$HERE/deploy" --exclude=__pycache__ -cf - . | on_host 'tar -C /opt/corvid/deploy -xf -'
echo "  deploy files in /opt/corvid/deploy"
on_host "ARCH=$arch VERSION=$CLI_VERSION SHA=${CLI_SHA256[$arch]} bash -s" <<'EOF'
set -e
stalwart-cli --version 2>/dev/null | grep -q " $VERSION$" && { echo "  stalwart-cli $VERSION present"; exit 0; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
name="stalwart-cli-$ARCH-unknown-linux-musl"
curl -fsSL -o "$tmp/cli.tar.xz" "https://github.com/stalwartlabs/cli/releases/download/v$VERSION/$name.tar.xz"
echo "$SHA  $tmp/cli.tar.xz" | sha256sum -c --quiet
tar -C "$tmp" -xJf "$tmp/cli.tar.xz"
install -m 755 "$tmp/$name/stalwart-cli" /usr/local/bin/stalwart-cli
echo "  stalwart-cli $VERSION installed, checksum verified"
EOF
if (( ship_local )); then
  "$engine" save "$IMAGE" | on_host 'podman load -q' >/dev/null
  echo "  $IMAGE copied to $server"
fi
on_host "printf '%s\n' '$IMAGE' > /opt/corvid/image.ref"
others="$(awk '/^# Written by install.sh/ {next} /^server:/ {s = 1; next} /^[^ ]/ {s = 0} !s' <<<"$existing_config" | sed '/./,$!d')"
{
  cat <<EOF
# Written by install.sh, which rewrites server: on every run. No secrets here; see deploy/config.yaml.
server:
  domain: $domain
  hostname: $hostname
  mail_user: $mail_user
  acme_contact: $acme_contact
  web_port: $web_port
  client_access: $client_access${host_zone:+
  host_zone: $host_zone}

EOF
  if [[ -n "$others" ]]; then printf '%s\n' "$others"
  else printf 'milter:\n  bind: 127.0.0.1:11332\n\nimgproxy:\n  bind: 127.0.0.1:8081\n'; fi
} | on_host 'cat > /opt/corvid/config.yaml && chmod 644 /opt/corvid/config.yaml'
echo "  config.yaml written"
if [[ -n "$token" ]]; then
  printf '%s' "$token" | on_host 'install -d -m 700 /opt/corvid/secrets && umask 077 && cat > /opt/corvid/secrets/cloudflare.token && chown 2000:2000 /opt/corvid/secrets/cloudflare.token && chmod 400 /opt/corvid/secrets/cloudflare.token'
  echo "  Cloudflare token stored"
fi
on_host 'bash -s' <<'EOF'
set -e
umask 077
keep() {
  if grep -qs "^$2=." "$1"; then echo "  kept $2"
  else sed -i "/^$2=/d" "$1" 2>/dev/null || true; printf '%s=%s\n' "$2" "$3" >> "$1"; echo "  generated $2"; fi
}
keep /opt/corvid/rewriter.env CORVID_IMGPROXY_SECRET "$(openssl rand -hex 32)"
keep /opt/corvid/idp.env ENCRYPTION_KEY "$(openssl rand -base64 32)"
EOF

step "preparing the host"
on_host 'cd /opt/corvid/deploy && CORVID_INSTALLER=1 bash bootstrap.sh'
# The labeler runs once config.yaml has its section, and Pocket-ID once the passkey plan has
# given Stalwart an OIDC directory. Until then each stays masked, on every run.
on_host 'bash -s' <<'EOF'
grep -q "^labeler:" /opt/corvid/config.yaml || systemctl mask corvid-labeler.service >/dev/null 2>&1
set -a; . /opt/corvid/corvid.env 2>/dev/null; set +a
stalwart-cli query Directory --json 2>/dev/null | grep -q . || systemctl mask corvid-idp.service >/dev/null 2>&1
true
EOF
held="$(on_host "podman ps --format '{{.Names}}' | grep -vx 'corvid-.*' || true")"
if on_host 'systemctl is-enabled corvid-pod.service 2>/dev/null' | grep -q masked; then
  (( replace )) || die "another container holds the mail ports (${held:-see above}); rerun with --replace to remove it"
  on_host "podman ps --format '{{.Names}}|{{.Ports}}' | grep -E ':(25|465|587|993)->' | cut -d'|' -f1 | xargs -r podman rm -f >/dev/null; systemctl unmask corvid-pod.service >/dev/null"
  echo "  replaced the container holding the mail ports"
fi

step "deploying"
on_host 'cd /opt/corvid/deploy && CORVID_INSTALLER=1 bash deploy.sh' || echo "  deploy.sh reported problems; see its output above"

if (( ${#stale_mta_sts[@]} )); then
  step "removing the MTA-STS records"
  for id in "${stale_mta_sts[@]}"; do
    out="$(cloudflare "$token" delete "$domain_zone_id" "$id" 2>&1)" || die "Cloudflare did not remove record $id: $out"
  done
  ok "mta-sts.$domain and _mta-sts.$domain removed"
fi

if [[ "$web_port" != 443 ]]; then
  step "publishing the MTA-STS policy"
  cfg_cmd="podman run --rm --network=none -v /opt/corvid/config.yaml:/etc/corvid/config.yaml:ro '$IMAGE' corvid-config"
  policy="$(on_host "$cfg_cmd mta-sts-policy")" || die "could not render the MTA-STS policy: $policy"
  policy_id="$(on_host "$cfg_cmd shell" | awk -F= '$1=="MTA_STS_ID"{print $2}')"
  [[ -n "$policy_id" ]] || die "corvid-config did not report MTA_STS_ID; rebuild the image"
  CLOUDFLARE_STDIN="$(mta_sts_worker_script "$policy")"
  out="$(cloudflare "$token" deploy-worker "$account_id" "$domain_zone_id" "$worker_name" "mta-sts.$domain" 2>&1)" ||
    die "Cloudflare did not deploy the Worker: $out"
  unset CLOUDFLARE_STDIN
  existing="$(cloudflare "$token" records "$domain_zone_id" "_mta-sts.$domain" TXT)" || die "could not read _mta-sts.$domain: $existing"
  content="v=STSv1; id=$policy_id"
  if [[ -z "$existing" ]]; then
    out="$(cloudflare "$token" create-txt "$domain_zone_id" "_mta-sts.$domain" "$content" 2>&1)" || die "Cloudflare did not publish _mta-sts.$domain: $out"
  else
    out="$(cloudflare "$token" update-record "$domain_zone_id" "${existing%% *}" "$content" 2>&1)" || die "Cloudflare did not update _mta-sts.$domain: $out"
  fi
  ok "mta-sts.$domain serves the policy; _mta-sts.$domain publishes id $policy_id"
elif (( worker_owned )); then
  step "removing the MTA-STS Worker"
  out="$(cloudflare "$token" remove-worker "$account_id" "$worker_name" "mta-sts.$domain" 2>&1)" || die "Cloudflare did not remove the Worker: $out"
  existing="$(cloudflare "$token" records "$domain_zone_id" "_mta-sts.$domain" TXT)" || die "could not read _mta-sts.$domain: $existing"
  if [[ -n "$existing" ]]; then
    out="$(cloudflare "$token" delete "$domain_zone_id" "${existing%% *}" 2>&1)" || die "Cloudflare did not remove _mta-sts.$domain: $out"
  fi
  ok "the Worker and its records are removed; Stalwart now serves MTA-STS on 443"
fi

# Stalwart names its keys v1-TYPE-DATE and never deletes one it has replaced, so every reinstall
# leaves a pair behind. Only records in that form that Stalwart no longer holds are removed.
current_dkim="$(on_host 'set -a; . /opt/corvid/corvid.env; set +a; stalwart-cli query DkimSignature --json' |
  grep -o '"selector":"[^"]*"' | cut -d'"' -f4)" || true
if [[ -n "$current_dkim" ]]; then
  stale_dkim=()
  while read -r id selector; do
    [[ "$selector" =~ ^v1-(rsa|ed25519)-[0-9]{8}$ ]] && ! grep -qx "$selector" <<<"$current_dkim" && stale_dkim+=("$id:$selector")
  done < <(cloudflare "$token" dkim "$domain_zone_id" "$domain")
  if (( ${#stale_dkim[@]} )); then
    step "removing DKIM keys from earlier installs"
    for entry in "${stale_dkim[@]}"; do
      out="$(cloudflare "$token" delete "$domain_zone_id" "${entry%%:*}" 2>&1)" || die "Cloudflare did not remove ${entry#*:}: $out"
      ok "removed ${entry#*:}._domainkey.$domain"
    done
  fi
fi

if [[ -n "$password" ]]; then
  step "setting your password"
  while true; do
    printf '%s' "$password" | on_host 'bash /opt/corvid/deploy/set-password.sh && touch /opt/corvid/.password-set' && break
    (( $? == 65 )) || die "the password could not be set; see the error above"
    [[ -z "${CORVID_MAIL_PASSWORD:-}" ]] || die "Stalwart refused CORVID_MAIL_PASSWORD for the reason above"
    echo "  Stalwart refused that password for the reason above; choose another"
    password="$(ask_secret "Password" "" "")"
  done
fi

step "waiting for certificates (Let's Encrypt, up to 10 minutes)"
on_host 'set -a; . /opt/corvid/corvid.env; set +a; for _ in $(seq 1 60); do n=$(stalwart-cli query Certificate 2>/dev/null | awk "NR>1" | wc -l); [ "$n" -gt 0 ] && { echo "  $n certificate(s) issued"; exit 0; }; sleep 10; done; echo "  none yet; Stalwart keeps retrying, and the checks below report it"'

step "verifying"
on_host "cd /opt/corvid/deploy && bash verify.sh '$domain' '$hostname'" || true

step "checking the ports from this machine"
# Only an outside connection finds a cloud firewall, and a bare TCP connect proves nothing
# behind a VPN client that accepts any connection itself, so each port must greet us.
closed=()
for port in 465 993 587; do
  case $port in
    465) greets "$port" '^220' QUIT || closed+=("$port") ;;
    993) greets "$port" '^\* OK' 'a LOGOUT' || closed+=("$port") ;;
    587) greets "$port" '^221' QUIT -starttls smtp || closed+=("$port") ;;
  esac
done
handshake="$(curl -sk -o /dev/null -w '%{time_appconnect}' --connect-timeout 5 --max-time 10 \
  --resolve "$hostname:$web_port:$server_ip" "https://$hostname:$web_port/" 2>/dev/null)" || true
[[ "$handshake" =~ ^0*\.?0*$ ]] && closed+=("$web_port")
if [[ "$client_access" == host ]]; then
  if (( ${#closed[@]} )); then ok "the mail ports are closed to this machine, as client_access: host intends"
  else ok "the mail ports answer from here, so this machine reaches the server through it, such as its VPN"; fi
elif (( ${#closed[@]} )); then
  warn "$server_ip does not answer from here on port ${closed[*]}"
  explain "      If your provider has a firewall or security group in front of the server, open" \
    "      these ports there. On a network that blocks them, check again from another one."
  [[ "$client_access" == anywhere ]] || explain "      client_access admits only $client_access, which may not include this machine."
else
  ok "465, 587, 993 and $web_port answer from here"
fi

cat <<EOF

Done. Your address is $mail_user@$domain.
  IMAP  $hostname:993 (TLS)      SMTP  $hostname:465 (TLS) or :587 (STARTTLS)
Send yourself a message from another provider and reply to it, then read deploy/RUNBOOK.md
for app passwords, the labeler and backups. New domains land in spam at first; replies fix it.
EOF
