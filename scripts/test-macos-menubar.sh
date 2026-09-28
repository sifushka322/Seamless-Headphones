#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${MACOS_SDK:-/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk}"
if [[ ! -d "$SDK" ]]; then SDK="$(xcrun --sdk macosx --show-sdk-path)"; fi
mkdir -p .build/modules
swiftc -swift-version 5 -parse-as-library -D MENU_BAR_SMOKE_TEST \
  -sdk "$SDK" -target "$(uname -m)-apple-macosx14.2" -module-cache-path "$PWD/.build/modules" \
  macos/Sources/*.swift macos/Tests/MenuBarSmokeTests.swift \
  -framework AppKit -framework SwiftUI -framework CoreBluetooth -framework IOBluetooth \
  -framework CoreAudio -framework Security -o .build/soundshift-menubar-smoke
# Requires a logged-in macOS desktop. Demo mode never starts a Bluetooth link.
.build/soundshift-menubar-smoke --demo --english --light
