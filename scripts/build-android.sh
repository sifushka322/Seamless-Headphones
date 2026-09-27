#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-localization.py localization/backend-en.json android/app/src/main/assets/ui-en.json
ROOT="$PWD"
if [[ -z "${JAVA_HOME:-}" && -d "$ROOT/.tools/jdk-17.0.20.1+1/Contents/Home" ]]; then
  export JAVA_HOME="$ROOT/.tools/jdk-17.0.20.1+1/Contents/Home"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$ROOT/.tools/android-sdk" ]]; then
  export ANDROID_HOME="$ROOT/.tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.tools/gradle-home}"
if [[ -n "${GRADLE_BIN:-}" ]]; then
  GRADLE="$GRADLE_BIN"
elif [[ -x "$ROOT/.tools/gradle-8.11.1/bin/gradle" ]]; then
  GRADLE="$ROOT/.tools/gradle-8.11.1/bin/gradle"
else
  GRADLE="$ROOT/android/gradlew"
fi
"$GRADLE" -p android --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
VERSION="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' android/app/build.gradle.kts)"
[[ -n "$VERSION" ]] || { echo "Cannot read Android versionName" >&2; exit 1; }
mkdir -p dist
cp android/app/build/outputs/apk/debug/app-debug.apk "dist/SeamlessHeadphones-$VERSION-android.apk"
echo "Built: $ROOT/dist/SeamlessHeadphones-$VERSION-android.apk (debug signing)"
