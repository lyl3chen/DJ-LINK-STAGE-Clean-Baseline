#!/usr/bin/env bash
set -euo pipefail
ROOT="/home/shenlei/.openclaw/agents/dev/workspace"
DBC="$ROOT/dbclient"
cd "$DBC"
mvn -q -DskipTests compile
mvn -q dependency:build-classpath -Dmdep.outputFile="$DBC/.runtime.cp"
echo "built runtime classpath at $DBC/.runtime.cp"
