#!/usr/bin/env bash
# Downloads a workspace-local JDK 17 + Android SDK 36 under .toolchain/.
# Uses a public AndroidSDK mirror because dl.google.com is not always reachable.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$ROOT/.toolchain/android-sdk"
DL="$ROOT/.toolchain/downloads"
MIRROR="${ANDROID_SDK_MIRROR:-https://mirrors.cloud.tencent.com/AndroidSDK}"
mkdir -p "$SDK/licenses" "$DL"

download() {
  local name="$1"
  if [ ! -s "$DL/$name" ]; then
    curl -L --fail --retry 3 -o "$DL/$name.part" "$MIRROR/$name"
    mv "$DL/$name.part" "$DL/$name"
  fi
}

download "commandlinetools-linux-11076708_latest.zip"
download "platform-36_r02.zip"
download "build-tools_r36_linux.zip"
download "platform-tools_r37.0.1-linux.zip"

rm -rf "$SDK/cmdline-tools/latest"
mkdir -p "$SDK/cmdline-tools/tmp"
unzip -qo "$DL/commandlinetools-linux-11076708_latest.zip" -d "$SDK/cmdline-tools/tmp"
mv "$SDK/cmdline-tools/tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
rm -rf "$SDK/cmdline-tools/tmp"

rm -rf "$SDK/platforms/android-36"
unzip -qo "$DL/platform-36_r02.zip" -d "$SDK/platforms"

rm -rf "$SDK/build-tools/36.0.0" "$SDK/build-tools/_tmp"
mkdir -p "$SDK/build-tools/_tmp"
unzip -qo "$DL/build-tools_r36_linux.zip" -d "$SDK/build-tools/_tmp"
top="$(find "$SDK/build-tools/_tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
mv "$top" "$SDK/build-tools/36.0.0"
rm -rf "$SDK/build-tools/_tmp"

rm -rf "$SDK/platform-tools"
unzip -qo "$DL/platform-tools_r37.0.1-linux.zip" -d "$SDK"

printf '24333f8a63b6825ea9c5514f83c2829b004d1dc0\n' > "$SDK/licenses/android-sdk-license"
printf '84831b9409646166d9d7f90e883e4b65d86bbf3b\n' > "$SDK/licenses/android-sdk-preview-license"

cat > "$ROOT/local.properties" <<EOF
sdk.dir=$SDK
EOF

echo "SDK ready at $SDK"
echo "Next: source scripts/env.sh && ./gradlew assembleDebug"
