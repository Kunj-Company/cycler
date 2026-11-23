#!/usr/bin/env bash
# Feed the emulator a moving GPS position so a ride can be recorded.
# Rides a 1.5 km-radius circle at a steady speed, one fix per second, with a gentle
# climb and descent. Each fix carries the speed, so the app shows real km/h and moving time.
# Usage: scripts/gps-ride.sh [minutes] [speed-kmh] [lat] [lon]
#   defaults: 5 minutes, 22 km/h, around 12.9716, 77.5946
# Emulator only (uses `adb emu geo fix`). Stop early with Ctrl-C.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
MIN="${1:-5}"; KMH="${2:-22}"; LAT="${3:-12.9716}"; LON="${4:-77.5946}"
SERIAL="$(adb devices | awk '/^emulator-/{print $1; exit}')"
[ -n "$SERIAL" ] || { echo "No emulator running." >&2; exit 1; }

# geo fix <lon> <lat> <altitude m> <satellites> <speed in knots>
awk -v n="$((MIN * 60))" -v kmh="$KMH" -v lat="$LAT" -v lon="$LON" 'BEGIN {
  pi = atan2(0, -1); r = 1500; v = kmh / 3.6; knots = v * 1.943844
  for (i = 0; i < n; i++) {
    a = (v * i) / r
    dlat = (r * sin(a)) / 111320
    dlon = (r * (1 - cos(a))) / (111320 * cos(lat * pi / 180))
    printf "%.6f %.6f %.1f 10 %.2f\n", lon + dlon, lat + dlat, 900 + 25 * sin(a * 2), knots
  }
}' | while read -r X Y Z SATS KN; do
  adb -s "$SERIAL" emu geo fix "$X" "$Y" "$Z" "$SATS" "$KN" >/dev/null
  sleep 1
done
echo "Done: $MIN min at $KMH km/h."
