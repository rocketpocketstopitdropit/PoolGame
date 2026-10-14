#!/usr/bin/env bash
# Builds the browser version into web/build/site.
# Uses the standalone Kotlin compiler; set KOTLIN_HOME to its folder (the one containing bin/ and lib/).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_HOME="${KOTLIN_HOME:?set KOTLIN_HOME to the unpacked kotlinc folder}"
KJS="$KOTLIN_HOME/bin/kotlinc-js"
STD="$KOTLIN_HOME/lib/kotlin-stdlib-js.klib"
OUT="$ROOT/web/build"

rm -rf "$OUT"
mkdir -p "$OUT/klib" "$OUT/site"

# 1) Compile the shared game engine (the same files the Android app uses) plus the web screen.
"$KJS" -libraries "$STD" -Xir-produce-klib-file -ir-output-dir "$OUT/klib" -ir-output-name pool \
  "$ROOT"/app/src/main/java/com/poolgame/app/core/*.kt "$ROOT"/web/src/*.kt

# 2) Link into a single JavaScript file.
"$KJS" -libraries "$STD" -Xir-produce-js -Xinclude="$OUT/klib/pool.klib" -Xir-dce \
  -module-kind plain -ir-output-dir "$OUT/site" -ir-output-name pool

cp "$ROOT"/web/static/* "$OUT/site/"
echo "Built $(du -h "$OUT/site/pool.js" | cut -f1) pool.js -> $OUT/site"
