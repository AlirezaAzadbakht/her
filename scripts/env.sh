#!/usr/bin/env bash
# Source this file before invoking Gradle outside Android Studio:
#   source scripts/env.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$ROOT/.toolchain/jdk-17"
export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="$ROOT/.toolchain/avd"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
