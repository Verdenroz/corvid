# shellcheck shell=bash
# The Cloudflare Worker pieces the MTA-STS and app links steps share.

# A Workers script name: PREFIX, then DOMAIN with its dots as hyphens.
worker_name() {
  local prefix="$1" domain="$2"
  echo "$prefix-${domain//./-}"
}

# A Worker that serves BODY verbatim as CONTENT_TYPE at PATH and answers 404 everywhere else,
# with no redirect.
static_worker_script() {
  local path="$1" content_type="$2" body="$3"
  cat <<JS
const POLICY = \`$body\`;
export default {
  fetch(request) {
    if (new URL(request.url).pathname !== "$path") {
      return new Response("not found", { status: 404 });
    }
    return new Response(POLICY, { headers: { "content-type": "$content_type" } });
  },
};
JS
}
