#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DBC="$ROOT/dbclient"
CP_FILE="$DBC/.runtime.cp"

cd "$DBC"

if [[ ! -s "$CP_FILE" ]]; then
  "$ROOT/scripts/build-dbclient-runtime.sh"
fi

CP="target/classes:$(cat "$CP_FILE")"
echo "DJLINK_START workspace=$ROOT dbclient=$DBC"
exec /usr/lib/jvm/java-17-openjdk-amd64/bin/java -cp "$CP" dbclient.Main
