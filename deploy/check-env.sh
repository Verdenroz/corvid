#!/usr/bin/env bash
# Fails when a service reads an env var that no env example and no unit mentions. Settings and
# plan variables come from config.yaml, which corvid-config's tests cover.
set -euo pipefail
cd "$(dirname "$0")/.."

read_by_code=$(grep -rhoE '(require|number|env_usize|var|secret)\("CORVID_[A-Z_]+"' crates/*/src | grep -oE 'CORVID_[A-Z_]+' | grep -v '^CORVID_TEST_' || true)
documented=$(
  grep -ohE '^(# )?[A-Z_]+=' deploy/env/*.example | tr -d '# ='
  { grep -ohE '^Environment=[A-Z_]+' deploy/*.container || true; } | cut -d= -f2
)

missing=$(comm -23 <(printf '%s\n' $read_by_code | sort -u) <(printf '%s\n' $documented | sort -u))
if [[ -n "$missing" ]]; then
  echo "read by a service but missing from deploy/env/*.example and every unit's Environment=:" >&2
  printf '  %s\n' $missing >&2
  exit 1
fi
echo "ok: every variable read is documented"
