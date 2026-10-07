# shellcheck shell=bash
# install.sh's discovery SRV step: _corvid._tcp.<domain> points the app at the web tier. Stalwart
# publishes its own _jmap._tcp record on port 443 and manages it, so this is a different name.

# Prints clear, current, stale or foreign for the "ID PORT TARGET" lines in RECORDS. Stale is one
# record for this host on another port, which a changed web_port leaves behind.
srv_dns_status() {
  local records="$1" hostname="$2" web_port="$3" port target
  if [[ -z "$records" ]]; then
    echo clear
    return
  fi
  read -r _ port target <<<"$records"
  if [[ "$(wc -l <<<"$records")" -gt 1 || "$target" != "$hostname" ]]; then
    echo foreign
  elif [[ "$port" == "$web_port" ]]; then
    echo current
  else
    echo stale
  fi
}

# Sets srv_action (create, update or none), srv_record_id and srv_old_port. Dies on a record that
# points to another host. Needs `cloudflare` and `die` in scope.
srv_dns_preflight() {
  local token="$1" zone_id="$2" domain="$3" hostname="$4" web_port="$5" records status
  records="$(cloudflare "$token" srv-records "$zone_id" "_corvid._tcp.$domain")" ||
    die "could not read _corvid._tcp.$domain's DNS records: $records"
  status="$(srv_dns_status "$records" "$hostname" "$web_port")"
  if [[ "$status" == foreign ]]; then
    die "_corvid._tcp.$domain points to $(cut -d' ' -f2- <<<"$records" | tr '\n' ';') at Cloudflare, not to $hostname port $web_port; fix that record and rerun"
  fi
  # shellcheck disable=SC2034  # read by install.sh
  read -r srv_record_id srv_old_port _ <<<"$records"
  # shellcheck disable=SC2034  # read by install.sh
  srv_action=$(case "$status" in clear) echo create ;; stale) echo update ;; *) echo none ;; esac)
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

# Moves the record srv_dns_preflight found stale to the new port, with the same warning on refusal.
srv_dns_update() {
  local token="$1" zone_id="$2" domain="$3" hostname="$4" web_port="$5" record_id="$6" out
  if out="$(cloudflare "$token" update-srv "$zone_id" "$record_id" "$web_port" "$hostname" 2>&1)"; then
    ok "_corvid._tcp.$domain now points to $hostname port $web_port"
  else
    warn "Cloudflare did not update the discovery record ($out); change it by hand: _corvid._tcp.$domain SRV 0 5 $web_port $hostname."
  fi
}
