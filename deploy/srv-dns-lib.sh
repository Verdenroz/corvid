# shellcheck shell=bash
# install.sh's discovery SRV step: _corvid._tcp.<domain> points the app at the web tier. Stalwart
# publishes its own _jmap._tcp record on port 443 and manages it, so this is a different name.

# Prints create, update, none or foreign for the "ID PORT TARGET" lines in RECORDS. Update is one
# record for this host on another port, which a changed web_port leaves behind.
srv_dns_status() {
  local records="$1" hostname="$2" web_port="$3" port target
  if [[ -z "$records" ]]; then
    echo create
    return
  fi
  read -r _ port target <<<"$records"
  if [[ "$(wc -l <<<"$records")" -gt 1 || "$target" != "$hostname" ]]; then
    echo foreign
  elif [[ "$port" == "$web_port" ]]; then
    echo none
  else
    echo update
  fi
}

# Sets srv_action (create, update or none), srv_record_id and srv_old_port. Dies on a record that
# points to another host. Needs `cloudflare` and `die` in scope.
srv_dns_preflight() {
  local token="$1" zone_id="$2" domain="$3" hostname="$4" web_port="$5" records
  records="$(cloudflare "$token" srv-records "$zone_id" "_corvid._tcp.$domain")" ||
    die "could not read _corvid._tcp.$domain's DNS records: $records"
  # shellcheck disable=SC2034  # read by install.sh
  srv_action="$(srv_dns_status "$records" "$hostname" "$web_port")"
  if [[ "$srv_action" == foreign ]]; then
    die "_corvid._tcp.$domain points to $(cut -d' ' -f2- <<<"$records" | tr '\n' ';') at Cloudflare, not to $hostname port $web_port; fix that record and rerun"
  fi
  # shellcheck disable=SC2034  # read by install.sh
  read -r srv_record_id srv_old_port _ <<<"$records"
}

# Carries out the create or update srv_dns_preflight chose; RECORD_ID is the record to update. A
# refusal is a warning carrying the record to set by hand, since mail works without discovery.
# Needs `cloudflare`, `warn` and `ok` in scope.
srv_dns_apply() {
  local action="$1" token="$2" zone_id="$3" domain="$4" hostname="$5" web_port="$6" record_id="${7:-}" out verb=add
  [[ "$action" != update ]] || verb=change
  if out="$(cloudflare "$token" "$action-srv" "$zone_id" "${record_id:-_corvid._tcp.$domain}" "$web_port" "$hostname" 2>&1)"; then
    ok "_corvid._tcp.$domain now points to $hostname port $web_port"
  else
    warn "Cloudflare did not $action the discovery record ($out); $verb it by hand: _corvid._tcp.$domain SRV 0 5 $web_port $hostname."
  fi
}
