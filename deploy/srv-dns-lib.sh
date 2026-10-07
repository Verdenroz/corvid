# shellcheck shell=bash
# install.sh's discovery SRV step: _corvid._tcp.<domain> points the app at the web tier. Stalwart
# publishes its own _jmap._tcp record on port 443 and manages it, so this is a different name.

# Prints clear, current or foreign for the "ID PORT TARGET" lines in RECORDS.
srv_dns_status() {
  local records="$1" hostname="$2" web_port="$3"
  if [[ -z "$records" ]]; then
    echo clear
  elif [[ "$(cut -d' ' -f2- <<<"$records")" == "$web_port $hostname" ]]; then
    echo current
  else
    echo foreign
  fi
}

# Sets srv_create. Dies on a record that points elsewhere. Needs `cloudflare` and `die` in scope.
srv_dns_preflight() {
  local token="$1" zone_id="$2" domain="$3" hostname="$4" web_port="$5" records status
  records="$(cloudflare "$token" srv-records "$zone_id" "_corvid._tcp.$domain")" ||
    die "could not read _corvid._tcp.$domain's DNS records: $records"
  status="$(srv_dns_status "$records" "$hostname" "$web_port")"
  if [[ "$status" == foreign ]]; then
    die "_corvid._tcp.$domain points to $(cut -d' ' -f2- <<<"$records" | tr '\n' ';') at Cloudflare, not to $hostname port $web_port; fix that record and rerun"
  fi
  # shellcheck disable=SC2034  # read by install.sh
  srv_create=$([[ "$status" == clear ]] && echo 1 || echo 0)
}

# Creates the record, called only after srv_dns_preflight found it clear. A refusal is a warning
# carrying the record to add by hand, since mail works without discovery. Needs `cloudflare`,
# `warn` and `ok` in scope.
srv_dns_create() {
  local token="$1" zone_id="$2" domain="$3" hostname="$4" web_port="$5" out
  if out="$(cloudflare "$token" create-srv "$zone_id" "_corvid._tcp.$domain" "$web_port" "$hostname" 2>&1)"; then
    ok "_corvid._tcp.$domain now points to $hostname port $web_port"
  else
    warn "Cloudflare did not create the discovery record ($out); add it by hand: _corvid._tcp.$domain SRV 0 5 $web_port $hostname."
  fi
}
