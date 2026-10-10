#!/usr/bin/env bash
# Registers the runner on first start, then runs it. RUNNER_TOKEN is only read while .runner is
# missing, so a restart after the one-hour token has expired still comes up.
set -euo pipefail

cd /home/runner
if [[ ! -f .runner ]]; then
  ./config.sh --unattended --replace --url "$RUNNER_URL" --token "$RUNNER_TOKEN" \
    --name "$RUNNER_NAME" --labels "$RUNNER_LABELS" --work "$RUNNER_WORK" >/dev/null
fi
exec ./run.sh
