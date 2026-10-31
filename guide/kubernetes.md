# Kubernetes

`charts/corvid` runs Corvid as one pod: Stalwart, unbound, the milter, the image proxy, the
sentinel, Caddy, and optionally the labeler and scribe. The containers share localhost as they do
under Podman, so every setting means the same thing. `values.config` is rendered verbatim as the
pod's `config.yaml` and follows the same schema.

!!! warning
    The chart has run on kind, never on a production cluster. Certificates and Cloudflare DNS
    have not been exercised on it.

On a kind cluster the pod came up with every container ready, the first run below bootstrapped
Stalwart and applied the plan, and HTML mail sent over SMTP came back over JMAP with its tracking
pixel stripped, its photo proxied and its `utm_` parameters removed. From another pod, the
NetworkPolicy blocked Stalwart's port 8080, and turning it off opened it.

## Before choosing it

Mail is an unusual workload for Kubernetes:

- Outbound mail leaves from the node's IP, which needs port 25 open, a PTR record you control and
  a clean reputation, as a VPS would. Most managed Kubernetes services block outbound port 25.
- Inbound mail needs a load balancer that passes port 25 and keeps the client's address
  (`externalTrafficPolicy: Local`), since SPF and the spam filter judge the sender's IP.

In practice that means a single-node cluster, such as k3s, on a VPS that allows mail.

## Install

1. Build the image and push it somewhere the cluster can pull from:

    ```sh
    podman build -f deploy/Containerfile -t ghcr.io/you/corvid:0.1.0 .
    podman push ghcr.io/you/corvid:0.1.0
    ```

2. Write a values file with `images.corvid.repository` and `tag`, the `config.server` section, and
   the secrets: name an existing Secret in `secrets.existingSecret` with the keys listed under
   `secrets.values`, or fill `secrets.values`. The chart refuses to render without the image-proxy
   secret, the sentinel's secret and the Cloudflare token, or without the labeler's secret when the
   labeler is on. Set `STALWART_RECOVERY_ADMIN` to `recovery:<password>` for the first run.
3. Install it:

    ```sh
    helm install corvid charts/corvid -f values.yaml
    ```

## First run

Stalwart needs its one-time bootstrap and the configuration plan. The Podman scripts do both,
run from a checkout against a port-forward. The workstation needs `stalwart-cli`, `envsubst`,
`python3`, `curl` and a local `corvid-config` (`cargo build -p corvid-config`).

```sh
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
deploy/bootstrap-server.sh              # writes the admin credential into $dir/corvid.env
kubectl rollout restart deploy/corvid   # leave bootstrap mode; restart the port-forward
deploy/apply-plan.sh
kubectl rollout restart deploy/corvid   # bind the listeners the plan declares
```

Then remove `STALWART_RECOVERY_ADMIN` from the Secret and restart once more: while it is set, it
signs in on every listener ahead of the account directory. Keep `$dir/corvid.env`, which holds the
admin credential for later applies.

## Later

To add the labeler, give it an app password ([Services](../devices/#services)), add that to the
Secret as `CORVID_JMAP_PASSWORD`, and upgrade with `labeler.enabled=true`.

For thread summaries, give the scribe an app password and add it to the Secret as
`CORVID_SCRIBE_JMAP_PASSWORD`, along with `CORVID_SCRIBE_SECRET`. Add `config.scribe` and
`config.sentinel.scribe`, then upgrade with `scribe.enabled=true`.

For passkeys, make a setup code with `deploy/passkey.sh code`, enter it in the app, then apply
`deploy/auth.plan.ndjson`. Applying it switches sign-in to the sentinel, so enroll a passkey
first.

## What the kind run showed

- unbound's trust-anchor init container fails its first attempt and succeeds on the retry, as
  designed.
- Right after a restart, unbound answers SERVFAIL for a few seconds while it primes the root
  keys. Mail arriving then waits on DNS.
- The DATA stage took 28 to 47 seconds, almost all of it Stalwart's spam filter querying DNS
  blocklists one at a time. The milter took about 200 ms.
- Stalwart logs nothing to stdout after bootstrap until a stdout tracer is configured.

## Not yet verified

- A production cluster, certificates and Cloudflare DNS automation.
- The labeler and the scribe in the chart.
- The sentinel in the chart, which is rendered and schema-checked (`helm template` and
  `kubeconform`) but has never run on a live cluster.
