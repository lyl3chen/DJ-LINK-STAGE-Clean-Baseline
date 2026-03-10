#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DBC="$ROOT/dbclient"
cd "$DBC"
mvn -q -DskipTests compile
mvn -q dependency:build-classpath -Dmdep.outputFile="$DBC/.runtime.cp"
echo "built runtime classpath at $DBC/.runtime.cp"
