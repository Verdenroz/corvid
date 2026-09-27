# shellcheck shell=bash
# install.sh's MTA-STS Worker step: pure decision helpers plus the Cloudflare orchestration,
# kept separate so both can be unit tested against a fake Cloudflare without SSH.

# Marks the _mta-sts TXT this script publishes, so it is never confused with Stalwart's own
# "v=STSv1" copy, which looks identical otherwise.
MTA_STS_TXT_COMMENT="corvid-mta-sts-worker"

# A Cloudflare Workers script name derived from DOMAIN: letters, digits and hyphens only.
mta_sts_worker_name() {
  local domain="$1"
  echo "corvid-mta-sts-${domain//./-}"
}

# Whether an existing mta-sts.<domain> CNAME (as "ID CONTENT", or empty) can be replaced:
# clear (nothing there), stale (Stalwart's own, pointing at HOSTNAME), owned (this run's Worker
# already holds it) or foreign (someone else's; the install must stop, not overwrite it).
mta_sts_record_status() {
  local cname_line="$1" hostname="$2" worker_owned="$3"
  if [[ -z "$cname_line" ]]; then
    echo clear
  elif [[ "${cname_line#* }" == "$hostname" ]]; then
    echo stale
  elif (( worker_owned )); then
    echo owned
  else
    echo foreign
  fi
}

# The Worker script: a policy rendered by corvid-config, served verbatim as text/plain, and
# nothing else, so the host does not look like a live policy at other paths.
mta_sts_worker_script() {
  local policy="$1"
  cat <<JS
const POLICY = \`$policy\`;
export default {
  fetch(request) {
    if (new URL(request.url).pathname !== "/.well-known/mta-sts.txt") {
      return new Response("not found", { status: 404 });
    }
    return new Response(POLICY, { headers: { "content-type": "text/plain" } });
  },
};
JS
}

# Detects Corvid's own MTA-STS Worker (by its custom domain's service name, never by DNS content,
# since Stalwart's own "_mta-sts" TXT looks identical) and whether mta-sts.<domain> already holds
# a record: Stalwart's own (staged in stale_mta_sts, an array the caller declares), this run's
# Worker, or someone else's, which stops the install rather than being silently overwritten.
# Sets worker_name, account_id and worker_owned. Needs `cloudflare` and `die` in scope.
mta_sts_preflight() {
  local token="$1" domain="$2" hostname="$3" domain_zone_id="$4" web_port="$5"
  local cname kind record owner

  worker_name="$(mta_sts_worker_name "$domain")"
  account_id="$(cloudflare "$token" account "$domain_zone_id")" ||
    die "could not read the account that owns $domain: $account_id"
  owner="$(cloudflare "$token" worker-owner "$account_id" "mta-sts.$domain")" ||
    die "could not check mta-sts.$domain: $owner"
  worker_owned=0
  [[ "$owner" == "$worker_name" ]] && worker_owned=1

  [[ "$web_port" == 443 ]] && return

  cname="$(cloudflare "$token" records "$domain_zone_id" "mta-sts.$domain" CNAME)" ||
    die "could not read mta-sts.$domain: $cname"
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

  (( worker_owned )) && return
  for kind in A AAAA; do
    record="$(cloudflare "$token" records "$domain_zone_id" "mta-sts.$domain" "$kind")" ||
      die "could not read mta-sts.$domain ($kind): $record"
    [[ -n "$record" ]] && die "mta-sts.$domain already has a $kind record; remove it before installing off 443"
  done
}

# Publishes the policy through the Worker when the web tier is off 443, or removes a Worker this
# run created earlier when it is back on 443. Never touches Stalwart's own copy: the Worker's TXT
# is found by MTA_STS_TXT_COMMENT, not by content. Needs `cloudflare`, `die`, `step` and `ok`.
mta_sts_apply() {
  local token="$1" web_port="$2" domain="$3" domain_zone_id="$4" account_id="$5" worker_name="$6" \
    worker_owned="$7" policy="$8" policy_id="$9"
  local owned_txt out content records_out count

  if [[ "$web_port" != 443 ]]; then
    step "publishing the MTA-STS policy"
    # shellcheck disable=SC2034  # read by cloudflare(), defined in install.sh
    CLOUDFLARE_STDIN="$(mta_sts_worker_script "$policy")"
    out="$(cloudflare "$token" deploy-worker "$account_id" "$domain_zone_id" "$worker_name" "mta-sts.$domain" 2>&1)" ||
      die "Cloudflare did not deploy the Worker: $out"
    unset CLOUDFLARE_STDIN
    owned_txt="$(cloudflare "$token" worker-txt "$domain_zone_id" "_mta-sts.$domain" "$MTA_STS_TXT_COMMENT")" ||
      die "could not check _mta-sts.$domain: $owned_txt"
    content="v=STSv1; id=$policy_id"
    if [[ -z "$owned_txt" ]]; then
      out="$(cloudflare "$token" create-txt "$domain_zone_id" "_mta-sts.$domain" "$content" "$MTA_STS_TXT_COMMENT" 2>&1)" ||
        die "Cloudflare did not publish _mta-sts.$domain: $out"
    else
      out="$(cloudflare "$token" update-record "$domain_zone_id" "${owned_txt%% *}" "$content" 2>&1)" ||
        die "Cloudflare did not update _mta-sts.$domain: $out"
    fi
    records_out="$(cloudflare "$token" records "$domain_zone_id" "_mta-sts.$domain" TXT)" ||
      die "could not read _mta-sts.$domain: $records_out"
    count="$(grep -c 'v=STSv1' <<<"$records_out" || true)"
    [[ "$count" == 1 ]] ||
      die "_mta-sts.$domain now has $count v=STSv1 TXT records; a sender treats more than one as no policy at all. Remove the extra one at Cloudflare and rerun."
    ok "mta-sts.$domain serves the policy; _mta-sts.$domain publishes id $policy_id"
  elif (( worker_owned )); then
    step "removing the MTA-STS Worker"
    out="$(cloudflare "$token" remove-worker "$account_id" "$worker_name" "mta-sts.$domain" 2>&1)" ||
      die "Cloudflare did not remove the Worker: $out"
    owned_txt="$(cloudflare "$token" worker-txt "$domain_zone_id" "_mta-sts.$domain" "$MTA_STS_TXT_COMMENT")" ||
      die "could not check _mta-sts.$domain: $owned_txt"
    if [[ -n "$owned_txt" ]]; then
      out="$(cloudflare "$token" delete "$domain_zone_id" "${owned_txt%% *}" 2>&1)" ||
        die "Cloudflare did not remove _mta-sts.$domain: $out"
    fi
    ok "the Worker and its records are removed; Stalwart now serves MTA-STS on 443"
  fi
}
