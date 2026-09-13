SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.ONESHELL:
.DEFAULT_GOAL := help

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
APK := $(ROOT)/app/build/outputs/apk/debug/app-debug.apk
AVD := her
PKG := com.her.debug
ACTIVITY := $(PKG)/com.her.MainActivity

.PHONY: help emulate apk stop test scenarios scenario scenarios-record scenario-record scenarios-replay

help:
	@echo "make emulate  - boot the her AVD, install the debug APK, launch the app"
	@echo "make apk      - assemble the debug APK (always rebuilds)"
	@echo "make stop     - stop the running emulator"
	@echo "make test     - run the offline unit tests"
	@echo "make scenarios - run the scenario pool against the live LLM from .env"
	@echo "make scenario ID=system-calendar-create-event - run one scenario"
	@echo "make scenarios-record - run live and save passing attempts as cassettes"
	@echo "make scenario-record ID=system-calendar-create-event - record one scenario"
	@echo "make scenarios-replay - replay cassettes offline (what CI runs)"

test:
	source "$(ROOT)/scripts/env.sh"
	gradle :app:testDebugUnitTest

scenarios-record:
	source "$(ROOT)/scripts/env.sh"
	gradle :app:scenarioTest -Pscenario.mode=record

scenario-record:
	source "$(ROOT)/scripts/env.sh"
	gradle :app:scenarioTest -Pscenario.mode=record -Pscenario.only="$(ID)"

scenarios-replay:
	source "$(ROOT)/scripts/env.sh"
	gradle :app:scenarioTest -Pscenario.mode=replay

apk:
	source "$(ROOT)/scripts/env.sh"
	gradle assembleDebug

emulate:
	source "$(ROOT)/scripts/env.sh"
	if [[ ! -f "$(APK)" ]]; then
		gradle assembleDebug
	fi
	if ! adb devices | grep -qE 'emulator-[0-9]+[[:space:]]+device'; then
		emulator -avd "$(AVD)" -gpu swiftshader_indirect -accel on -no-audio &
		adb wait-for-device
		until [[ "$$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do
			sleep 2
		done
	fi
	remote_path="$$(adb shell pm path "$(PKG)" 2>/dev/null | sed -n 's/^package://p' | tr -d '\r' || true)"
	if [[ -z "$$remote_path" ]]; then
		adb install -t "$(APK)"
	else
		local_md5="$$(md5sum "$(APK)" | awk '{print $$1}')"
		remote_md5="$$(adb shell md5sum "$$remote_path" | awk '{print $$1}')"
		if [[ "$$local_md5" != "$$remote_md5" ]]; then
			adb install -r -t "$(APK)"
		fi
	fi
	adb shell am start -n "$(ACTIVITY)"

stop:
	source "$(ROOT)/scripts/env.sh"
	adb emu kill || true

scenarios:
	source "$(ROOT)/scripts/env.sh"
	gradle :app:scenarioTest

scenario:
	source "$(ROOT)/scripts/env.sh"
	gradle :app:scenarioTest -Pscenario.only="$(ID)"
