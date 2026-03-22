#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$HOME/.openclaw/agents/dev/workspace/dj-link-stage/desktop-ui"

if [ ! -d "$PROJECT_DIR" ]; then
  echo "❌ 找不到目录: $PROJECT_DIR"
  exit 1
fi

cd "$PROJECT_DIR"
echo "🚀 启动 DJ Link Stage Desktop..."
./gradlew run
