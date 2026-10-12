#!/usr/bin/env bash
#
# Asserts that a built APK still meets the promises this project makes.
#
# These are the checks that matter and that nothing else performs. A build can
# succeed perfectly while quietly losing the one property the app exists for,
# so each of these is an assertion with a non-zero exit rather than a line of
# output somebody is expected to read:
#
#   * minSdkVersion is still 4       -- the whole premise
#   * a launcher activity exists     -- or it installs to nothing
#   * v1 (JAR) signing is present    -- or it will not install below Android 7
#   * the dex contains no API newer than the floor
#
# Kept out of the workflow YAML deliberately: here it runs on a laptop too, and
# a developer finds out before pushing rather than after.
#
# Usage: tools/verify-apk.sh [apk] [min-sdk]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/build/1ai.apk}"
MIN_SDK="${2:-4}"

[ -f "$APK" ] || { echo "ERROR: no such APK: $APK" >&2; exit 1; }

# ------------------------------------------------------------------ toolchain
# Prefer the tools that actually produced the APK. Discovering our own can pick
# a different build-tools revision than the build used, which makes the two
# disagree about the very thing being checked.
RECORDED="$(dirname "$APK")/sdk.env"
if [ -f "$RECORDED" ]; then
  # shellcheck disable=SC1090
  . "$RECORDED"
  [ -n "${AAPT2:-}" ] && [ -x "$AAPT2" ] || AAPT2=""
  [ -n "${APKSIGNER:-}" ] && [ -x "$APKSIGNER" ] || APKSIGNER=""
  echo "Using the toolchain recorded by build.sh (build-tools ${BUILD_TOOLS:-?}, platform ${PLATFORM:-?})"
fi

find_sdk() {
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" \
           "/usr/local/lib/android/sdk" "/usr/lib/android-sdk"; do
    [ -n "$d" ] && [ -d "$d/build-tools" ] && { echo "$d"; return 0; }
  done
  return 1
}
SDK="$(find_sdk)" || { echo "ERROR: no Android SDK found. Set ANDROID_HOME." >&2; exit 1; }

BT="$SDK/build-tools/${ANDROID_BUILD_TOOLS:-$(ls -1 "$SDK/build-tools" | sort -V | tail -1)}"
: "${AAPT2:=$BT/aapt2}"
: "${APKSIGNER:=$BT/apksigner}"

for t in "$AAPT2" "$APKSIGNER"; do
  [ -f "$t" ] || { echo "ERROR: missing $t" >&2; exit 1; }
done

fail=0
ok()   { printf '  \033[1;32mok\033[0m    %s\n' "$*"; }
bad()  { printf '  \033[1;31mFAIL\033[0m  %s\n' "$*"; fail=$((fail + 1)); }

echo "Verifying $APK"

# ------------------------------------------------------- the compatibility floor
BADGING="$("$AAPT2" dump badging "$APK" 2>&1)"

# Matched loosely on purpose. The key has been spelled both sdkVersion and
# minSdkVersion across aapt2 releases, and pinning the exact spelling made this
# check fail against an APK that was perfectly fine -- which is a far worse
# failure than a slightly looser match.
SDK_LINE="$(echo "$BADGING" | grep -E "^(min)?[sS]dkVersion" | head -1)"
if echo "$SDK_LINE" | grep -q "'$MIN_SDK'"; then
  ok "minSdkVersion is $MIN_SDK"
else
  bad "minSdkVersion is not $MIN_SDK"
  # Name the actual cause. "aapt2 could not read it", "the toolchain formats
  # this differently" and "the floor really moved" need different fixes.
  if ! echo "$BADGING" | grep -q "^package:"; then
    echo "        aapt2 produced no package line, so it could not read the APK at all."
    echo "        tool: $AAPT2"
    echo "$BADGING" | head -5 | sed 's/^/        /'
  elif [ -z "$SDK_LINE" ]; then
    echo "        no minSdkVersion line in the badging output; the toolchain or"
    echo "        the APK manifest is not what this expects. Full output:"
    echo "$BADGING" | head -25 | sed 's/^/        /'
  else
    echo "        got: $SDK_LINE"
    echo "        tool: $AAPT2"
  fi
fi

if echo "$BADGING" | grep -q "^launchable-activity"; then
  ok "launcher activity present"
else
  bad "no launchable activity -- the APK would install with no way to open it"
fi

# ------------------------------------------------------------------- signature
# v1 is what Android below 7.0 understands. Without it the APK installs on a
# modern phone and is rejected outright on the hardware this targets, which is
# precisely the failure that would not be noticed until someone tried.
SIG="$("$APKSIGNER" verify --min-sdk-version "$MIN_SDK" -v "$APK" 2>&1)" || {
  bad "signature verification failed"
  echo "$SIG" | sed 's/^/        /'
  SIG=""
}
if [ -n "$SIG" ]; then
  if echo "$SIG" | grep -qE "v1 scheme \(JAR signing\): true"; then
    ok "v1 (JAR) signing present"
  else
    bad "v1 (JAR) signing missing -- will not install below Android 7.0"
  fi
  echo "$SIG" | grep -qE "v2 scheme.*: true" && ok "v2 signing present"
fi

# --------------------------------------------------------------- the dex itself
# Catches anything arriving from a third-party jar, which the source-level
# check in build.sh cannot see.
if "$ROOT/tools/dexcheck.sh" "$APK" "$MIN_SDK"; then
  ok "dex contains no API newer than $MIN_SDK"
else
  bad "dex references APIs that do not exist on API $MIN_SDK"
fi

echo
if [ "$fail" -eq 0 ]; then
  printf '\033[1;32mAll checks passed.\033[0m\n'
  exit 0
fi
printf '\033[1;31m%d check(s) failed.\033[0m\n' "$fail"
exit 1
