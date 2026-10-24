#!/bin/bash
set -euo pipefail
app_path="${1:?Pass the built iOS Simulator app path}"
output_dir="${2:-build/apple-smoke}"
mkdir -p "$output_dir"
device_id="$(xcrun simctl list devices available -j | python3 -c 'import sys,json; d=json.load(sys.stdin); print(next(x["udid"] for r,v in d["devices"].items() if ".iOS-" in r for x in v if x.get("isAvailable") and x["name"].startswith("iPhone")))')"
xcrun simctl boot "$device_id" || true
xcrun simctl bootstatus "$device_id" -b
xcrun simctl install "$device_id" "$app_path"
xcrun simctl launch "$device_id" org.her.apple.preview
# Only synthetic empty first-run state is used; no personal calendar/account access.
sleep 5
xcrun simctl io "$device_id" screenshot "$output_dir/ios-first-run.png"
xcrun simctl spawn "$device_id" launchctl list > "$output_dir/processes.txt"
if ! grep -Fq 'org.her.apple.preview' "$output_dir/processes.txt"; then
  printf 'Her did not remain running after launch.\n' >&2
  exit 1
fi
