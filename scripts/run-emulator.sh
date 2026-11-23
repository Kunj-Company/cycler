#!/usr/bin/env bash
# Start the Cycler test emulator and wait until it has booted.
# Usage: scripts/run-emulator.sh [avd-name]   (default: cycler_api35)
set -euo pipefail
source "$(dirname "$0")/_env.sh"
AVD="${1:-cycler_api35}"
if ! emulator -list-avds | grep -qx "$AVD"; then
  echo "No AVD named $AVD. Create it with:" >&2
  echo "  avdmanager create avd -n $AVD -k 'system-images;android-35;google_apis;arm64-v8a' -d pixel_8" >&2
  exit 1
fi
if adb devices | grep -q '^emulator-'; then
  echo "An emulator is already running."
else
  nohup emulator -avd "$AVD" -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
  echo "Starting $AVD..."
fi
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
echo "Booted: $(adb shell getprop ro.product.model) (API $(adb shell getprop ro.build.version.sdk))"
