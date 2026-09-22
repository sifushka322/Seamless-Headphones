#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${MACOS_SDK:-/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk}"
if [[ ! -d "$SDK" ]]; then SDK="$(xcrun --sdk macosx --show-sdk-path)"; fi
mkdir -p .build/modules
swiftc -swift-version 5 -sdk "$SDK" -module-cache-path "$PWD/.build/modules" macos/Sources/Wire.swift macos/Tests/CoreTests.swift -o .build/core-tests
.build/core-tests
swiftc -swift-version 5 -sdk "$SDK" -module-cache-path "$PWD/.build/modules" macos/Sources/AutoPolicy.swift macos/Tests/AutoTests.swift -o .build/auto-tests
.build/auto-tests
