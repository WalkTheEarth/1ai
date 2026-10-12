#!/usr/bin/env bash
#
# Installs the APK on a running device or emulator and checks that it comes up
# without a crash.
#
# Building is not the same as running. Every error class this project actually
# risks -- NoSuchMethodError from compiling against android-34 while shipping
# to API 4, VerifyError from a dex the old verifier rejects,
# Resources$NotFoundException from a theme parent that does not exist yet --
# compiles perfectly cleanly and appears only at runtime, on the target
# hardware.
#
# This lives in the repo rather than in workflow YAML for two reasons. It can
# be run against a local emulator before pushing, and the Android emulator
# GitHub Action executes its `script` input one line at a time via `sh -c`, so
# an if/then/fi block written there fails with "Syntax error: end of file
# unexpected" rather than doing anything.
#
# Usage: tools/smoke-test.sh [apk] [serial]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/build/1ai.apk}"
SERIAL="${2:-}"
PACKAGE=com.oneai
ACTIVITY=$PACKAGE/.ui.ChatsActivity

# How long to wait for the process to appear. Generous: a cold start on a 2013
# emulator image is slow, and a flaky timeout is worse than a slow pass.
SETTLE_SECONDS="${SETTLE_SECONDS:-20}"

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

[ -f "$APK" ] || { echo "ERROR: no such APK: $APK" >&2; exit 1; }

fail() {
  printf '\033[1;31mFAIL\033[0m  %s\n' "$*" >&2
  exit 1
}
step() { printf '  %s\n' "$*"; }

echo "Smoke-testing $(basename "$APK") on $("${ADB[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r\n') (API $("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n'))"

step "installing"
"${ADB[@]}" install -r "$APK" 2>&1 | tail -1 | grep -q Success || fail "install failed"

"${ADB[@]}" logcat -c >/dev/null 2>&1 || true

step "launching"
"${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null 2>&1 || fail "could not start $ACTIVITY"

sleep "$SETTLE_SECONDS"

step "checking the process survived"
# Android has no pidof before API 24, so ps is the portable way to ask.
#
# grep is run on the host rather than the device on purpose: `grep -q` closing
# the pipe early makes the on-device ps exit with SIGPIPE, which under `set -e`
# would look like a failure even when the process is right there.
if ! "${ADB[@]}" shell ps 2>/dev/null | grep -q "$PACKAGE"; then
  echo
  echo "--- logcat ---"
  "${ADB[@]}" logcat -d 2>/dev/null | tail -60
  fail "$PACKAGE is not running after launch"
fi
step "process is alive"

step "scanning logcat for runtime errors"
LOG="$("${ADB[@]}" logcat -d 2>/dev/null || true)"
FOUND="$(printf '%s\n' "$LOG" | grep -E \
  'FATAL EXCEPTION|VerifyError|NoSuchMethodError|NoClassDefFoundError|Resources\$NotFoundException|OutOfMemoryError' \
  || true)"

if [ -n "$FOUND" ]; then
  echo
  printf '%s\n' "$FOUND" | head -20
  echo
  echo "--- surrounding logcat ---"
  printf '%s\n' "$LOG" | tail -60
  fail "the app reported a runtime error"
fi

echo
printf '\033[1;32mLaunched cleanly.\033[0m\n'
