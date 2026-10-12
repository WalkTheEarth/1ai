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

# Android supplies org.json at runtime; the desktop JVM does not, so the
# reference implementation is fetched here. It is checked in nowhere and
# verified on download, so a fresh clone can run the suite with no setup step.
JSON_VERSION=20250107
JAR="$ROOT/test/libs/json-$JSON_VERSION.jar"
JSON_URL="https://repo1.maven.org/maven2/org/json/json/$JSON_VERSION/json-$JSON_VERSION.jar"

if [ ! -f "$JAR" ]; then
  echo "fetching json-$JSON_VERSION.jar"
  mkdir -p "$(dirname "$JAR")"
  curl -fsSL "$JSON_URL" -o "$JAR.tmp" || { echo "download failed: $JSON_URL"; exit 1; }
  want="$(curl -fsSL "$JSON_URL.sha1" | tr -d '[:space:]')"
  got="$(sha1sum "$JAR.tmp" | awk '{print $1}')"
  if [ -n "$want" ] && [ "$want" != "$got" ]; then
    rm -f "$JAR.tmp"; echo "checksum mismatch: expected $want got $got"; exit 1
  fi
  mv "$JAR.tmp" "$JAR"
  echo "  ok $(basename "$JAR") (sha1 verified)"
fi

rm -rf "$OUT"; mkdir -p "$OUT/classes"

# Only the Android-free sources are compiled here. Android-specific transports
# live in subpackages (net/android, net/bc) and are excluded by -maxdepth.
find "$ROOT/app/src/com/oneai/net" -maxdepth 1 -name '*.java' > "$OUT/srcs.txt"
find "$ROOT/app/src/com/oneai/provider" -name '*.java' >> "$OUT/srcs.txt"

# These packages mix Android-free types with Android-only ones, so the pure
# ones are named explicitly rather than globbed -- data/ is mostly SQLite and
# Bitmap code, and util/ mostly spans. Anything listed here must not import
# android.*, and the build fails loudly if it does.
for f in data/Attachment util/Hostname util/Pem; do
  echo "$ROOT/app/src/com/oneai/$f.java" >> "$OUT/srcs.txt"
done
find "$ROOT/test/src" -name '*.java' >> "$OUT/srcs.txt"

javac -encoding UTF-8 -nowarn -cp "$JAR" -d "$OUT/classes" @"$OUT/srcs.txt"

java -cp "$OUT/classes:$JAR" com.oneai.test.AllTests
