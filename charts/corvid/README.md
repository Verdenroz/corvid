# Corvid Helm chart

Runs Corvid as one pod: Stalwart, unbound, the milter, the image proxy, the sign-in sentinel,
Caddy, and optionally the labeler. The containers share localhost exactly as in the
Podman deployment, so every setting means the same thing in both. `values.config` is rendered
verbatim as the pod's `config.yaml` and follows its schema (`deploy/config.yaml`).

**Status: installed and exercised on kind, never on a production cluster.** On a kind cluster
(Kubernetes 1.37) the pod came up with every container ready, the first-run procedure below
bootstrapped Stalwart and applied the plan through a port-forward, and HTML mail delivered over
SMTP came back over JMAP rewritten by the milter: the tracking pixel stripped, the photo proxied,
`utm_` parameters removed. From another pod, the NetworkPolicy blocked Stalwart's port 8080 and
turning it off opened it. Certificates and DNS automation were not exercised: `.test` names get
no certificate, and the Cloudflare token was a placeholder.

## Where this fits

Mail is an unusual workload for Kubernetes. Consider these points before choosing it over the
Podman deployment:

- Outbound mail leaves from the node's IP. That IP needs port 25 open, a PTR record you control,
  and a clean reputation, exactly as a VPS would. Most managed Kubernetes services block outbound
  port 25.
- Inbound mail needs a load balancer that passes port 25 and preserves the client's address
  (`externalTrafficPolicy: Local`), since SPF and the spam filter judge the sender's IP.
- In practice that means a single-node cluster, such as k3s, on a VPS that allows mail.

## Install

1. Build the image and push it somewhere the cluster can pull from:

       podman build -f deploy/Containerfile -t ghcr.io/you/corvid:0.1.0 .
       podman push ghcr.io/you/corvid:0.1.0

2. Write a values file with `images.corvid.repository` and `tag`, the `config.server` section,
   and the secrets. Either name an existing Secret in `secrets.existingSecret`, with the keys
   listed under `secrets.values`, or fill `secrets.values`. The chart refuses to render without
   the image-proxy secret, the sentinel's HMAC secret and the Cloudflare token, or without the
   labeler's secret when it is enabled. Set `STALWART_RECOVERY_ADMIN`
   to `recovery:<password>` for the first run.

3. `helm install corvid charts/corvid -f values.yaml`

## First run

Stalwart needs its one-time bootstrap and the configuration plan. The Podman scripts do both;
here they run from a checkout of this repository against a port-forward. The workstation needs
`stalwart-cli`, `envsubst`, `python3` and `curl`, and a local `corvid-config`
(`cargo build -p corvid-config`).

    kubectl port-forward deploy/corvid 8080:8080 &
    dir=$(mktemp -d)                                  # stands in for /opt/corvid
    cp values-config.yaml "$dir/config.yaml"          # the same content as values.config
    mkdir "$dir/secrets" && cp cloudflare.token "$dir/secrets/"
    cat > "$dir/corvid.env" <<EOF
    STALWART_URL=http://127.0.0.1:8080
    STALWART_USER=
    STALWART_PASSWORD=
    RECOVERY_ADMIN_USER=recovery
    RECOVERY_ADMIN_PASSWORD=<the password from STALWART_RECOVERY_ADMIN>
    EOF
    export CORVID_DIR="$dir" CORVID_ENV="$dir/corvid.env" CORVID_CONFIG="$dir/config.yaml" \
           CORVID_CONFIG_CMD="$PWD/target/debug/corvid-config"
    deploy/bootstrap-server.sh          # writes the admin credential into $dir/corvid.env
    kubectl rollout restart deploy/corvid   # leave bootstrap mode; restart the port-forward
    deploy/apply-plan.sh
    kubectl rollout restart deploy/corvid   # bind the listeners the plan declares

Then remove `STALWART_RECOVERY_ADMIN` from the Secret and restart once more. While it is set it
authenticates on every listener, ahead of the account directory. Keep `$dir/corvid.env`: it holds
the admin credential for later applies.

## Later

- For the labeler, create an app password for it (see `deploy/RUNBOOK.md`), add it to the Secret
  as `CORVID_JMAP_PASSWORD`, and upgrade with `labeler.enabled=true`.
- For passkeys, mint a setup code with `deploy/passkey.sh code` (the first key on a server always
  approves devices), enter it in the app, and apply `deploy/auth.plan.ndjson` as the RUNBOOK
  describes. That switches logins to the sentinel, so enrol a passkey before relying on it.

## What the kind run showed

- The unbound trust-anchor init container fails its first attempt (`unbound-anchor` exits 1
  when it writes the anchor) and succeeds on the retry, as designed.
- Right after a restart, unbound answers SERVFAIL for a few seconds while it primes the root
  keys. Mail arriving in that window waits on DNS.
- The DATA stage took 28 to 47 seconds, almost all of it Stalwart's spam filter querying DNS
  blocklists one at a time; some refused or timed out from the test network. The milter took
  about 200 ms. Expect this to vary with the network the node sits on.
- Stalwart logs nothing to stdout after bootstrap until a stdout tracer is configured; see the
  traps in `CLAUDE.md`.

## Not yet verified

- A production cluster, certificates, and Cloudflare DNS automation.
- The labeler in the chart.
- The sentinel in the chart: rendered and schema-validated (`helm template` plus `kubeconform`),
  never run against a live cluster.
