#!/usr/bin/env bash
set -euo pipefail
URL="http://127.0.0.1:8080/api/players/state"
STATE_FILE="/tmp/djlink-health-fail.count"
MAX_FAIL=3

fail_count=0
if [[ -f "$STATE_FILE" ]]; then
  fail_count=$(cat "$STATE_FILE" 2>/dev/null || echo 0)
fi

if curl -fsS --max-time 3 "$URL" >/dev/null; then
  echo 0 > "$STATE_FILE"
  exit 0
fi

fail_count=$((fail_count + 1))
echo "$fail_count" > "$STATE_FILE"

if (( fail_count >= MAX_FAIL )); then
  systemctl --user restart djlink-webui.service
  echo 0 > "$STATE_FILE"
fi
