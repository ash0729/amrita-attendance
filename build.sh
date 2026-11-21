#!/usr/bin/env bash
# Builds the arm64 GeckoView APK. Requires JDK 17+, Android API 37, and Build Tools 36.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk; do
    if [[ -x "$candidate/bin/java" ]]; then JAVA_HOME="$candidate"; break; fi
  done
  if [[ -z "${JAVA_HOME:-}" ]]; then
    java_bin="$(readlink -f "$(command -v java)")"
    JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
  fi
  export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

./gradlew --no-daemon clean assembleRelease
APK=build/outputs/apk/release/amrita-attendance-release.apk
"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify "$APK"
cp "$APK" amrita-attendance.apk
mkdir -p dist
cp "$APK" dist/amrita-attendance.apk
sha256sum dist/amrita-attendance.apk
