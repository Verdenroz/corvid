# shellcheck shell=bash
# Pure helpers for install.sh's MTA-STS Worker step, kept separate so they can be unit tested
# without SSH or Cloudflare.

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

# The Worker script: a policy rendered by corvid-config, served verbatim as text/plain.
mta_sts_worker_script() {
  local policy="$1"
  cat <<JS
const POLICY = \`$policy\`;
export default {
  fetch() {
    return new Response(POLICY, { headers: { "content-type": "text/plain" } });
  },
};
JS
}
