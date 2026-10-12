#!/usr/bin/env bash
#
# Rejects an APK that references APIs which do not exist on old Android.
#
# This is the backstop for the whole premise of the project. We compile against
# android-34 while shipping to minSdkVersion 4, so neither javac, aapt2, d8 nor
# R8 will ever tell us that we called something a 2010 device has never heard
# of. tools/apicheck.py catches that in the source; this catches it in the
# artefact, including anything pulled in from a third-party jar.
#
# The failure mode it prevents is nasty: the app installs and runs fine, then
# throws NoSuchMethodError or NoClassDefFoundError the moment a user opens the
# one screen that touches the missing class.
#
# Usage: tools/dexcheck.sh <apk> [min-sdk]
#
set -euo pipefail

APK="${1:-build/1ai.apk}"
MIN_SDK="${2:-4}"

[ -f "$APK" ] || { echo "ERROR: no such APK: $APK" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

unzip -qo "$APK" 'classes*.dex' -d "$TMP" 2>/dev/null || {
  echo "ERROR: $APK contains no classes.dex" >&2; exit 1; }

# Pull printable strings out of the dex without depending on binutils: the type
# and method descriptors we care about live in the string data section, so a
# plain byte scan finds them.
extract() {
  if command -v strings >/dev/null 2>&1; then
    strings -n 6 "$1"
  else
    tr -c '[:print:]' '\n' < "$1" | awk 'length($0) >= 6'
  fi
}

# Types and members that do NOT exist at API 4, and are not backported by d8's
# desugaring. Core-library desugaring (which does backport several of these)
# requires a desugared-lib jar and a much higher minSdk, so none of it applies
# here -- if one of these appears, the build is broken for the target device.
#
# Each entry is "descriptor:why".
FORBIDDEN=(
  "java/util/function/:java.util.function is API 24"
  "java/util/stream/:java.util.stream is API 24"
  "java/util/Optional:Optional is API 24"
  "java/util/Spliterator:Spliterator is API 24"
  "java/nio/file/:java.nio.file is API 26"
  "java/time/:java.time is API 26"
  "java/lang/AutoCloseable:AutoCloseable is API 19 (try-with-resources)"
  "addSuppressed:Throwable.addSuppressed is API 19"
  "getSuppressed:Throwable.getSuppressed is API 19"
  "java/util/Objects:java.util.Objects is API 19"
  "java/util/Base64:java.util.Base64 is API 26; android.util.Base64 is API 8"
  "java/util/concurrent/CompletableFuture:CompletableFuture is API 24"
  "java/lang/invoke/LambdaMetafactory:lambda was not desugared"
  "java/util/StringJoiner:StringJoiner is API 24"
  "java/util/concurrent/ThreadLocalRandom:ThreadLocalRandom is API 21"
  "java/lang/ref/Cleaner:Cleaner is API 9"
  "java/nio/charset/StandardCharsets:StandardCharsets is API 19"
)

fail=0
for entry in "${FORBIDDEN[@]}"; do
  needle="${entry%%:*}"
  why="${entry#*:}"
  for dex in "$TMP"/classes*.dex; do
    [ -f "$dex" ] || continue
    n="$(extract "$dex" | grep -c -- "$needle" || true)"
    if [ "$n" -gt 0 ]; then
      printf '\033[1;31m  FORBIDDEN\033[0m %-42s %s\n' "$needle" "$why"
      fail=$((fail + 1))
    fi
  done
done

count="$(ls -1 "$TMP"/classes*.dex | wc -l | tr -d ' ')"
if [ "$fail" -eq 0 ]; then
  printf '  clean: %s dex file(s), no APIs newer than %s\n' "$count" "API $MIN_SDK"
  exit 0
fi

echo
echo "  $fail reference(s) that would crash on Android API $MIN_SDK."
echo "  Find the source with: ./build.sh  (tools/apicheck.py checks the same list)"
exit 1
