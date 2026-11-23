#!/usr/bin/env bash
# Build the debug APK, install it on the connected device or emulator,
# grant location and notifications, and open the app.
# Usage: scripts/install-debug.sh [--no-build]
set -euo pipefail
source "$(dirname "$0")/_env.sh"
cd "$ROOT"
if [ "${1:-}" != "--no-build" ]; then ./gradlew assembleDebug; fi
APK="app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$APK"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant com.cycler.app "android.permission.$p" 2>/dev/null || true
done
adb shell am start -n com.cycler.app/.MainActivity
