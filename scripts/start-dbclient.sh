#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/shenlei/.openclaw/agents/dev/workspace"
DBC="$ROOT/dbclient"
CP_FILE="$DBC/.runtime.cp"

cd "$DBC"

if [[ ! -s "$CP_FILE" ]]; then
  "$ROOT/scripts/build-dbclient-runtime.sh"
fi

CP="target/classes:$(cat "$CP_FILE")"
exec /usr/lib/jvm/java-17-openjdk-amd64/bin/java -cp "$CP" dbclient.Main
