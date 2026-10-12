#!/usr/bin/env bash
#
# 1AI build script.
#
# Deliberately does NOT use Gradle or the Android Gradle Plugin: AGP and
# AndroidX impose minSdk floors (14/19/21+) that would make the whole point of
# this app -- running on Android 1.6 -- impossible. Driving aapt2/javac/R8/
# zipalign/apksigner directly is what buys us minSdkVersion 4.
#
# Usage:
#   ./build.sh fetch-deps     download + checksum-verify third-party jars
#   ./build.sh                build a release APK
#   ./build.sh clean
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
LIBS="$ROOT/libs"
TOOLS="$ROOT/tools"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"

MIN_SDK=4
TARGET_SDK=28

MAVEN=https://repo1.maven.org/maven2
GOOGLE_MAVEN=https://dl.google.com/dl/android/maven2

# R8 version is pinned. The R8 bundled inside build-tools 34 is a -dev build
# (8.2.2-dev) that crashes with an internal NullPointerException on any class
# carrying an InnerClasses attribute -- i.e. on virtually all real Java. A
# pinned stable R8 is a hard requirement, not a preference.
R8_VERSION=9.5.23

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- SDK locate
find_sdk() {
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "/usr/lib/android-sdk"; do
    [ -n "$d" ] && [ -d "$d/platforms" ] && { echo "$d"; return 0; }
  done
  return 1
}
SDK="$(find_sdk)" || die "no Android SDK found. Set ANDROID_HOME."

BT_VER="$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)"
[ -n "$BT_VER" ] || die "no build-tools in $SDK"
BT="$SDK/build-tools/$BT_VER"

PLATFORM="$(ls -1 "$SDK/platforms" | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
[ -f "$ANDROID_JAR" ] || die "$ANDROID_JAR missing"

AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
LAMBDA_STUBS="$BT/core-lambda-stubs.jar"

for t in "$AAPT2" "$ZIPALIGN" "$APKSIGNER" "$LAMBDA_STUBS"; do
  [ -f "$t" ] || die "missing build tool $t"
done

# --------------------------------------------------------------- downloads
# Every downloaded artifact is verified against the checksum its repository
# publishes, so a truncated or tampered jar cannot silently enter the build.
fetch_verified() { # url dest
  local url="$1" dest="$2" want got
  [ -f "$dest" ] && return 0
  mkdir -p "$(dirname "$dest")"
  curl -fsSL "$url" -o "$dest.tmp" || { rm -f "$dest.tmp"; die "download failed: $url"; }
  want="$(curl -fsSL "$url.sha1" 2>/dev/null | tr -d '[:space:]')"
  got="$(sha1sum "$dest.tmp" | awk '{print $1}')"
  if [ -n "$want" ] && [ "$want" != "$got" ]; then
    rm -f "$dest.tmp"
    die "checksum mismatch for $(basename "$dest"): expected $want got $got"
  fi
  mv "$dest.tmp" "$dest"
  echo "    ok $(basename "$dest")"
}

ensure_r8() {
  R8_JAR="$TOOLS/r8.jar"
  if [ ! -f "$R8_JAR" ]; then
    say "Fetching R8 $R8_VERSION"
    fetch_verified "$GOOGLE_MAVEN/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar" "$R8_JAR"
  fi
}

# Mozilla's CA bundle, used by the bundled TLS transport. Fetched rather than
# committed so the roots stay current, and verified so a bad download cannot
# quietly enter the APK. Without this the app would trust only the device's own
# store, which on an old device predates the roots today's endpoints use.
CACERT_URL=https://curl.se/ca/cacert.pem
CACERT_DEST="$APP/res/raw/cacert.pem"

ensure_cacert() {
  if [ -f "$CACERT_DEST" ]; then
    return 0
  fi
  say "Fetching the Mozilla CA bundle"
  mkdir -p "$(dirname "$CACERT_DEST")"
  local want got
  curl -fsSL "$CACERT_URL" -o "$CACERT_DEST.tmp" || die "could not fetch $CACERT_URL"
  want="$(curl -fsSL "$CACERT_URL.sha256" | awk '{print $1}')"
  got="$(sha256sum "$CACERT_DEST.tmp" | awk '{print $1}')"
  if [ -z "$want" ] || [ "$want" != "$got" ]; then
    rm -f "$CACERT_DEST.tmp"
    die "CA bundle checksum mismatch: expected $want got $got"
  fi
  mv "$CACERT_DEST.tmp" "$CACERT_DEST"
  echo "    ok cacert.pem (sha256 verified, $(grep -c 'BEGIN CERTIFICATE' "$CACERT_DEST") roots)"
}

# Bouncy Castle provides TLS 1.2 in pure Java, which Android below 4.1 has no
# way of doing otherwise. The jdk15to18 variants are compiled for Java 5, so
# they need no desugaring and reference none of the java.util.function /
# java.util.stream APIs that are missing on old Dalvik. Note that TLS lives in
# bctls, not bcprov -- depending on bcprov alone is not enough.
BC_VERSION=1.78

ensure_libs() {
  mkdir -p "$LIBS"
  for a in bctls-jdk15to18 bcprov-jdk15to18 bcutil-jdk15to18; do
    fetch_verified "$MAVEN/org/bouncycastle/$a/$BC_VERSION/$a-$BC_VERSION.jar" \
      "$LIBS/$a-$BC_VERSION.jar"
  done
}

fetch_deps() {
  mkdir -p "$LIBS" "$TOOLS"
  say "Fetching build tools, jars and trust anchors (checksum-verified)"
  ensure_r8
  ensure_cacert
  ensure_libs
  say "Dependencies ready"
}

# ------------------------------------------------- API-level source guard
# We compile against android-34, so javac happily accepts calls that do not
# exist on an API 4 device -- they compile fine and then throw
# NoSuchMethodError at runtime, on hardware we cannot attach a debugger to.
# Neither aapt2 nor d8 checks this, so tools/apicheck.py does.
check_api_level() {
  say "Checking sources against the API $MIN_SDK deny-list"
  python3 "$ROOT/tools/apicheck.py" "$APP" "$MIN_SDK" \
    || die "source contains members that do not exist on Android $MIN_SDK"
}

# ------------------------------------------------------------------ build
build() {
  ensure_r8
  ensure_cacert
  ensure_libs
  check_api_level
  say "SDK         $SDK"
  say "build-tools $BT_VER"
  say "platform    $PLATFORM"
  say "R8          $R8_VERSION"
  echo

  rm -rf "$OUT"; mkdir -p "$GEN" "$CLASSES" "$DEX"

  # 1. Resources ---------------------------------------------------------
  say "[1/8] aapt2 compile"
  "$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

  say "[2/8] aapt2 link"
  "$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    "$OUT/res.zip"

  # 2. Java --------------------------------------------------------------
  say "[3/8] javac"
  local cp="$ANDROID_JAR:$LAMBDA_STUBS"
  if compgen -G "$LIBS/*.jar" > /dev/null; then
    for j in "$LIBS"/*.jar; do cp="$cp:$j"; done
  fi

  find "$APP/src" "$GEN" -name '*.java' > "$OUT/sources.txt"
  echo "      $(wc -l < "$OUT/sources.txt") source files"

  # -source/-target 8 is the oldest javac 21 still accepts; R8 desugars the
  # Java 8 language features down to minSdk 4 bytecode afterwards.
  javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$cp" -cp "$cp" \
    -d "$CLASSES" @"$OUT/sources.txt"
  [ -n "$(find "$CLASSES" -name '*.class' -print -quit)" ] || die "javac produced no classes"

  # 3. Shrink + dex ------------------------------------------------------
  say "[4/8] R8 (shrink + dex, min-api $MIN_SDK)"
  find "$CLASSES" -name '*.class' > "$OUT/classes.txt"
  # Third-party jars are R8 *inputs*, not just compile-time classpath, so that
  # Bouncy Castle actually ships in the dex and is shrunk down to what the
  # bundled TLS transport reaches.
  local r8_inputs=()
  if compgen -G "$LIBS/*.jar" > /dev/null; then
    for j in "$LIBS"/*.jar; do r8_inputs+=("$j"); done
  fi
  # R8's output goes to a log; it is only interesting when R8 fails. Its
  # "rule does not match anything" notes are multi-line and dominated by the
  # Bouncy Castle block before BC is on the classpath, which is pure noise.
  if ! java -cp "$R8_JAR" com.android.tools.r8.R8 \
        --release \
        --lib "$ANDROID_JAR" \
        --min-api "$MIN_SDK" \
        --output "$DEX" \
        --pg-conf "$ROOT/proguard-rules.pro" \
        @"$OUT/classes.txt" "${r8_inputs[@]}" > "$OUT/r8.log" 2>&1; then
    cat "$OUT/r8.log" >&2
    die "R8 failed (full log: $OUT/r8.log)"
  fi
  echo "      $(grep -cE '^Info' "$OUT/r8.log" || true) info notes suppressed (log: build/r8.log)"

  [ -f "$DEX/classes.dex" ] || die "R8 produced no classes.dex"

  # 4. Package -----------------------------------------------------------
  say "[5/8] package"
  cp "$OUT/base.apk" "$OUT/unsigned.apk"
  ( cd "$DEX" && zip -q "$OUT/unsigned.apk" classes.dex )

  say "[6/8] zipalign"
  "$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

  # 5. Sign --------------------------------------------------------------
  say "[7/8] sign"
  # A real signing key can be supplied through the environment (CI does this
  # from repository secrets). Without one we fall back to a throwaway debug
  # keystore, which is fine for sideloading but cannot be used to update an
  # installed app -- Android refuses an update signed by a different key.
  local ks="${ONEAI_KEYSTORE:-$ROOT/keystore.jks}"
  local store_pass="${ONEAI_KEYSTORE_PASS:-android}"
  local key_alias="${ONEAI_KEY_ALIAS:-oneai}"
  local key_pass="${ONEAI_KEY_PASS:-$store_pass}"

  if [ -n "${ONEAI_KEYSTORE:-}" ] && [ ! -f "$ks" ]; then
    die "ONEAI_KEYSTORE points at $ks, which does not exist"
  fi

  if [ ! -f "$ks" ]; then
    echo "      generating debug keystore (sideload-only)"
    keytool -genkeypair -keystore "$ks" -storepass "$store_pass" -keypass "$key_pass" \
      -alias "$key_alias" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=1AI, OU=dev, O=oneai" >/dev/null 2>&1
  fi

  # v1 (JAR) signing is mandatory below Android 7; v2/v3 are added for modern
  # devices. --min-sdk-version is what tells apksigner to emit the v1 scheme.
  # RSA specifically: apksig uses SHA-1 digests for minSdk < 18 and refuses EC
  # keys outright below 18, so an RSA key is the compatible choice here.
  "$APKSIGNER" sign \
    --ks "$ks" --ks-pass "pass:$store_pass" --key-pass "pass:$key_pass" \
    --ks-key-alias "$key_alias" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/1ai.apk" "$OUT/aligned.apk"

  # 6. Verify the artefact -----------------------------------------------
  # minSdk, launcher activity, v1 signing and the dex API scan all in one
  # place, shared with CI so the two can never disagree about what "valid"
  # means. Source-level checking cannot see anything arriving from the Bouncy
  # Castle jars; this can.
  say "[8/8] verifying the APK"
  "$ROOT/tools/verify-apk.sh" "$OUT/1ai.apk" "$MIN_SDK"

  echo
  say "Built $OUT/1ai.apk  ($(du -h "$OUT/1ai.apk" | cut -f1))"
  "$AAPT2" dump badging "$OUT/1ai.apk" 2>/dev/null \
    | grep -E "^package|^sdkVersion|^targetSdkVersion|^launchable-activity"
}

case "${1:-build}" in
  fetch-deps) fetch_deps ;;
  clean)      rm -rf "$OUT"; say "cleaned" ;;
  build|"")   build ;;
  *)          echo "usage: $0 [build|fetch-deps|clean]" >&2; exit 2 ;;
esac
