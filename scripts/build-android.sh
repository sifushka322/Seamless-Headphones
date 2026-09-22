#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-17.0.20.1+1/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$ROOT/.tools/android-sdk}"
export GRADLE_USER_HOME="$ROOT/.tools/gradle-home"
GRADLE="${GRADLE_BIN:-$ROOT/.tools/gradle-8.11.1/bin/gradle}"
"$GRADLE" -p android --no-daemon :app:testDebugUnitTest :app:assembleDebug
mkdir -p dist
cp android/app/build/outputs/apk/debug/app-debug.apk dist/SeamlessHeadphones-0.4.0-android.apk
echo "Built: $ROOT/dist/SeamlessHeadphones-0.4.0-android.apk (debug signing)"
