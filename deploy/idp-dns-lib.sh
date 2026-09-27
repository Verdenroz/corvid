# shellcheck shell=bash
# install.sh's idp hostname DNS step: the pure decision function, then the Cloudflare
# orchestration, kept separate so both can be unit tested against a fake Cloudflare without SSH.

# Whether an idp hostname's existing A records (one per line, or empty) let install.sh proceed:
# clear (create one), current (already points here) or foreign (points elsewhere, so the
# install must stop rather than overwrite it).
idp_a_record_status() {
  local a_records="$1" server_ip="$2"
  if [[ -z "$a_records" ]]; then
    echo clear
  elif grep -qx "$server_ip" <<<"$a_records"; then
    echo current
  else
    echo foreign
  fi
}

# Checks the idp hostname before anything is installed, the same way install.sh checks its own
# hostname. Sets idp_create_a. Dies on a foreign record. Needs `cloudflare` and `die` in scope.
idp_dns_preflight() {
  local token="$1" zone_id="$2" idp_hostname="$3" server_ip="$4"
  local a_records status
  a_records="$(cloudflare "$token" a-records "$zone_id" "$idp_hostname")" ||
    die "could not read $idp_hostname's DNS records: $a_records"
  status="$(idp_a_record_status "$a_records" "$server_ip")"
  [[ "$status" == foreign ]] &&
    die "$idp_hostname points to $(tr '\n' ' ' <<<"$a_records")at Cloudflare, not to this server's $server_ip; fix that record and rerun"
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
