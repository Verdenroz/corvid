#!/usr/bin/env bash
# Fails when corvid-sentinel matches a Stalwart event type to a named Kind but the plan's
# WebHook does not subscribe to it: Stalwart never posts an event outside eventsPolicy's include
# list, so an unsubscribed one is dead code on a real deployment, never exercised by a real batch.
set -euo pipefail
cd "$(dirname "$0")/.."

handled=$(grep -oE '"[a-z]+\.[a-z-]+" *=> *Kind::[A-Za-z]+' crates/corvid-sentinel/src/webhook.rs |
  grep -v 'Kind::Other' | grep -oE '"[a-z]+\.[a-z-]+"' | tr -d '"')

subscribed=$(python3 -c '
import json
for line in open("deploy/stalwart.plan.ndjson"):
    obj = json.loads(line)
    if obj.get("object") == "WebHook":
        for value in obj["value"].values():
            for event in value.get("events", {}):
                print(event)
')

missing=$(comm -23 <(sort -u <<<"$handled") <(sort -u <<<"$subscribed"))
if [[ -n "$missing" ]]; then
  echo "corvid-sentinel matches these event types to a named Kind, but the plan's WebHook does not subscribe to them:" >&2
  printf '  %s\n' $missing >&2
  exit 1
fi
echo "ok: every event corvid-sentinel matches by name is subscribed in the plan"
