#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-localization.py localization/backend-en.json localization/macos-en.json
SDK="${MACOS_SDK:-/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk}"
if [[ ! -d "$SDK" ]]; then SDK="$(xcrun --sdk macosx --show-sdk-path)"; fi
APP="$PWD/dist/Seamless Headphones.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" .build/modules
swiftc -sdk "$SDK" -module-cache-path "$PWD/.build/modules" scripts/make-icon.swift -o .build/make-icon
.build/make-icon "$PWD/.build/AppIcon.iconset"
iconutil -c icns .build/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
swiftc -swift-version 5 -parse-as-library -O -sdk "$SDK" -target "$(uname -m)-apple-macosx14.0" \
  -module-cache-path "$PWD/.build/modules" macos/Sources/*.swift \
  -framework AppKit -framework SwiftUI -framework CoreBluetooth -framework IOBluetooth \
  -framework CoreAudio -framework Security -o "$APP/Contents/MacOS/SeamlessHeadphones"
cp macos/Info.plist "$APP/Contents/Info.plist"
cp localization/backend-en.json "$APP/Contents/Resources/backend-en.json"
cp localization/macos-en.json "$APP/Contents/Resources/macos-en.json"
cp -R macos/Resources/en.lproj macos/Resources/ru.lproj "$APP/Contents/Resources/"
codesign --force --sign - "$APP"
echo "Built: $APP"
if [[ "${1:-}" == "--dmg" ]]; then
  STAGE="$(mktemp -d /tmp/seamless-headphones-dmg.XXXXXX)"
  trap 'rm -rf "$STAGE"' EXIT
  cp -R "$APP" "$STAGE/"
  ln -s /Applications "$STAGE/Applications"
  hdiutil create -volname 'Seamless Headphones' -srcfolder "$STAGE" -ov -format UDZO "$PWD/dist/SeamlessHeadphones-0.5.0-mac.dmg"
fi
