#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Direct compilation also works without the Xcode application or SwiftPM.
# Override HER_MACOS_SDK when the host's default SDK/toolchain are mismatched.
sdk="${HER_MACOS_SDK:-$(xcrun --sdk macosx --show-sdk-path)}"
arch="$(uname -m)"
mkdir -p .build/bundle dist/Her.app/Contents/MacOS
swiftc -sdk "$sdk" -target "${arch}-apple-macosx14.0" -swift-version 5 \
  -parse-as-library -emit-module -emit-library -static -module-name HerCore \
  Sources/HerCore/*.swift -o .build/bundle/libHerCore.a \
  -emit-module-path .build/bundle/HerCore.swiftmodule
swiftc -sdk "$sdk" -target "${arch}-apple-macosx14.0" -swift-version 5 \
  -parse-as-library -I .build/bundle -L .build/bundle -lHerCore \
  Sources/HerApp/*.swift -o dist/Her.app/Contents/MacOS/Her
cp Resources/Info.plist dist/Her.app/Contents/Info.plist
codesign --force --sign - --identifier org.her.apple.preview dist/Her.app
codesign --verify --deep --strict dist/Her.app
printf 'Built local preview: %s/dist/Her.app\n' "$PWD"
