# shellcheck shell=bash
# install.sh's Android app links step: how https://<domain>/.well-known/assetlinks.json is served.
# Credential Manager fetches it on 443 with no redirect, so the passkey RP ID is the mail domain
# and something has to answer there. Pure decision helpers plus the Cloudflare orchestration,
# kept separate so both can be unit tested against a fake Cloudflare without SSH.

ASSETLINKS_PATH=/.well-known/assetlinks.json

# A Cloudflare Workers script name derived from DOMAIN: letters, digits and hyphens only.
assetlinks_worker_name() {
  local domain="$1"
  echo "corvid-assetlinks-${domain//./-}"
}

# How the document reaches the internet, from what the mail domain's own name holds in DNS.
# OWNED says what this script already binds (domain or route, else empty); RECORDS are
# `address-records` lines (ID TYPE CONTENT proxied|dns-only). Prints:
#   worker-domain  the name is free, so a Worker takes it over as a custom domain
#   worker-route   the name is proxied by Cloudflare, so a Worker answers one path of it
#   caddy          the web tier holds 443 and the name's only record is an A record to this server
#   manual         anything else; someone else's name, which Corvid does not take over
assetlinks_mode() {
  local owned="$1" records="$2" web_port="$3" server_ip="$4" kind content proxied
  case "$owned" in
    domain) echo worker-domain; return ;;
    route) echo worker-route; return ;;
  esac
  [[ -n "$records" ]] || { echo worker-domain; return; }
  while read -r _ kind content proxied; do
    [[ "$proxied" != proxied ]] || { echo worker-route; return; }
  done <<<"$records"
  if [[ "$web_port" == 443 ]]; then
    while read -r _ kind content proxied; do
      [[ "$kind" == A && "$content" == "$server_ip" ]] || { echo manual; return; }
    done <<<"$records"
    echo caddy
    return
  fi
  echo manual
}

# The Worker script: the document corvid-config rendered, served as application/json at its path
# and nothing else, with no redirect.
assetlinks_worker_script() {
  local document="$1"
  cat <<JS
const DOCUMENT = \`$document\`;
export default {
  fetch(request) {
    if (new URL(request.url).pathname !== "$ASSETLINKS_PATH") {
      return new Response("not found", { status: 404 });
    }
    return new Response(DOCUMENT, { headers: { "content-type": "application/json" } });
  },
};
JS
}

# Chooses the way, changing nothing at Cloudflare. Sets assetlinks_worker and assetlinks_choice.
# Needs `cloudflare` and `die` in scope, and the account id from mta_sts_preflight.
assetlinks_preflight() {
  local token="$1" domain="$2" domain_zone_id="$3" account_id="$4" server_ip="$5" web_port="$6"
  local owner route records owned=""

  assetlinks_worker="$(assetlinks_worker_name "$domain")"
  owner="$(cloudflare "$token" worker-owner "$account_id" "$domain")" ||
    die "could not check $domain: $owner"
  route="$(cloudflare "$token" worker-route "$domain_zone_id" "$domain$ASSETLINKS_PATH")" ||
    die "could not check the Worker routes of $domain: $route"
  [[ "$owner" != "$assetlinks_worker" ]] || owned=domain
  [[ -n "$owned" || "$route" != "$assetlinks_worker" ]] || owned=route
  records="$(cloudflare "$token" address-records "$domain_zone_id" "$domain")" ||
    die "could not read the DNS records of $domain: $records"
  # shellcheck disable=SC2034  # read by install.sh
  assetlinks_choice="$(assetlinks_mode "$owned" "$records" "$web_port" "$server_ip")"
}

# Puts the Worker in front of the document, for the two Worker modes. Needs `cloudflare`, `die`,
# `step` and `ok` in scope.
assetlinks_publish() {
  local token="$1" domain="$2" domain_zone_id="$3" account_id="$4" worker_name="$5" mode="$6" document="$7"
  local out

  step "publishing the Android app links"
  # shellcheck disable=SC2034  # read by cloudflare(), defined in install.sh
  CLOUDFLARE_STDIN="$(assetlinks_worker_script "$document")"
  case "$mode" in
    worker-domain)
      out="$(cloudflare "$token" deploy-worker "$account_id" "$domain_zone_id" "$worker_name" "$domain" 2>&1)" ||
        die "Cloudflare did not deploy the Worker for $domain: $out"
      ;;
    worker-route)
      out="$(cloudflare "$token" deploy-route "$account_id" "$domain_zone_id" "$worker_name" "$domain$ASSETLINKS_PATH" 2>&1)" ||
        die "Cloudflare did not deploy the Worker route for $domain: $out"
      ;;
  esac
  unset CLOUDFLARE_STDIN
  ok "https://$domain$ASSETLINKS_PATH is served by the Worker $worker_name"
}
