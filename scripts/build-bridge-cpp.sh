#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/bridge-cpp"
OUT="$ROOT/bin"
mkdir -p "$OUT"

if command -v cmake >/dev/null 2>&1; then
  BUILD="$SRC/build"
  mkdir -p "$BUILD"
  cmake -S "$SRC" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release
  cmake --build "$BUILD" -j
  cp "$BUILD/ableton-link-bridge" "$OUT/ableton-link-bridge"
else
  echo "[build-bridge-cpp] cmake not found, using g++ direct build"
  g++ -std=c++17 -O2 \
    -DLINK_PLATFORM_UNIX=1 -DLINK_PLATFORM_LINUX=1 \
    -I"$SRC/third_party/ableton-link/include" \
    -I"$SRC/third_party/ableton-link/modules/asio-standalone/asio/include" \
    "$SRC/src/main.cpp" -o "$OUT/ableton-link-bridge" \
    -pthread -latomic
fi

chmod +x "$OUT/ableton-link-bridge"
echo "built $OUT/ableton-link-bridge"
