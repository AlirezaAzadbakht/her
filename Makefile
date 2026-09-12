SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.ONESHELL:
.DEFAULT_GOAL := help

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
APK := $(ROOT)/app/build/outputs/apk/debug/app-debug.apk
AVD := her
PKG := com.her.debug
ACTIVITY := $(PKG)/com.her.MainActivity

.PHONY: help emulate apk stop

help:
	@echo "make emulate  - boot the her AVD, install the debug APK, launch the app"
	@echo "make apk      - assemble the debug APK"
	@echo "make stop     - stop the running emulator"

apk:
	source "$(ROOT)/scripts/env.sh"
	"$(ROOT)/gradlew" assembleDebug

emulate: apk
	source "$(ROOT)/scripts/env.sh"
	if ! adb devices | grep -qE 'emulator-[0-9]+[[:space:]]+device'; then
		emulator -avd "$(AVD)" -gpu swiftshader_indirect -accel on -no-audio &
		adb wait-for-device
		until [[ "$$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do
			sleep 2
		done
	fi
	adb install -r -t "$(APK)"
	adb shell am start -n "$(ACTIVITY)"

stop:
	source "$(ROOT)/scripts/env.sh"
	adb emu kill || true
