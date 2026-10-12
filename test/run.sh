#!/usr/bin/env bash
#
# Runs the JVM test suite over the provider layer.
#
# The provider, SSE and (later) TLS-hostname code contains no Android types at
# all, so it can be compiled and exercised on the desktop JVM. That matters
# because the alternative is discovering wire-format bugs by watching a phone
# from 2010 fail, which is a very slow way to find a missing JSON field.
#
# Android supplies org.json at runtime; on the desktop we link the reference
# implementation instead.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/test"
JAR="$ROOT/test/libs/json-20250107.jar"

[ -f "$JAR" ] || { echo "missing $JAR"; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/classes"

# Only the Android-free sources are compiled here. Android-specific transports
# live in subpackages (net/android, net/bc) and are excluded by -maxdepth.
find "$ROOT/app/src/com/oneai/net" -maxdepth 1 -name '*.java' > "$OUT/srcs.txt"
find "$ROOT/app/src/com/oneai/provider" -name '*.java' >> "$OUT/srcs.txt"

# util/ mixes Android-free helpers (Hostname, Pem) with Android-only ones
# (Markdown, which is all spans), so the pure ones are named explicitly rather
# than globbed. Anything added here must not import android.*.
for f in Hostname Pem; do
  echo "$ROOT/app/src/com/oneai/util/$f.java" >> "$OUT/srcs.txt"
done
find "$ROOT/test/src" -name '*.java' >> "$OUT/srcs.txt"

javac -encoding UTF-8 -nowarn -cp "$JAR" -d "$OUT/classes" @"$OUT/srcs.txt"

java -cp "$OUT/classes:$JAR" com.oneai.test.AllTests
