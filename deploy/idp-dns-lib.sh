# shellcheck shell=bash
# install.sh's idp hostname DNS step: the pure decision function, then the Cloudflare
# orchestration, kept separate so both can be unit tested against a fake Cloudflare without SSH.

# Whether the idp hostname's existing records let install.sh proceed: clear (nothing there, so
# install.sh creates an A record), current (an A record already points here, nothing else) or
# foreign (an AAAA, a CNAME, or an A pointing elsewhere). install.sh has no server IPv6 address
# to check an AAAA against, so any AAAA or CNAME found there counts as foreign too.
idp_dns_status() {
  local a_records="$1" aaaa_records="$2" cname_records="$3" server_ip="$4"
  if [[ -n "$aaaa_records" || -n "$cname_records" ]]; then
    echo foreign
  elif [[ -z "$a_records" ]]; then
    echo clear
  elif grep -qx "$server_ip" <<<"$a_records"; then
    echo current
  else
    echo foreign
  fi
}

# Checks the idp hostname before anything is installed, the same way install.sh checks its own
# hostname. Sets idp_create_a. Dies on anything foreign. Needs `cloudflare` and `die` in scope.
idp_dns_preflight() {
  local token="$1" zone_id="$2" idp_hostname="$3" server_ip="$4"
  local a_records aaaa_records cname_records status
  a_records="$(cloudflare "$token" a-records "$zone_id" "$idp_hostname")" ||
    die "could not read $idp_hostname's DNS records: $a_records"
  aaaa_records="$(cloudflare "$token" records "$zone_id" "$idp_hostname" AAAA)" ||
    die "could not read $idp_hostname's DNS records: $aaaa_records"
  cname_records="$(cloudflare "$token" records "$zone_id" "$idp_hostname" CNAME)" ||
    die "could not read $idp_hostname's DNS records: $cname_records"
  status="$(idp_dns_status "$a_records" "$aaaa_records" "$cname_records" "$server_ip")"
  [[ "$status" == foreign ]] &&
    die "$idp_hostname already has a DNS record at Cloudflare that does not point to this server's $server_ip; fix or remove it and rerun"
  # shellcheck disable=SC2034  # read by install.sh
  idp_create_a=$([[ "$status" == clear ]] && echo 1 || echo 0)
}

# Creates the idp hostname's A record, called only after idp_dns_preflight found it clear.
# Needs `cloudflare`, `die` and `ok` in scope.
idp_dns_create() {
  local token="$1" zone_id="$2" idp_hostname="$3" server_ip="$4" out
  out="$(cloudflare "$token" create-a "$zone_id" "$idp_hostname" "$server_ip" 2>&1)" ||
    die "Cloudflare did not create $idp_hostname: $out"
  ok "$idp_hostname now points to $server_ip"
}
