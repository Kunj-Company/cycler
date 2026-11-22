# Cycler

A personal cycling app for Android: record rides, plan routes, get loop suggestions.
No account, no cloud. Everything is saved on the phone.

## Run it

1. Open this folder in Android Studio and let Gradle sync.
2. Plug in the phone (USB debugging on) and press Run.

Or install the prebuilt debug build directly:

    adb install -r Cycler-debug.apk

## What it does

- **Ride**: live map, start/pause/finish recording. Keeps recording with the screen off.
  Shows distance, time, speed, average, climb. Can follow a saved route while riding.
- **Plan → A to B**: long-press the map or search for places, get up to three routes
  with distance, climb, time and an elevation profile.
- **Plan → Loop**: pick a distance, get round trips heading north, east, south and west.
- **Plan → My rides**: saved routes nearest first; ride one again or save it reversed.
- **Routes**: a "Suggest routes near me" button (short, medium and long loops from where you are), plus everything saved, with map, stats, elevation, rename, delete, GPX import/export.
- **Settings**: bike type (road, hybrid, gravel, MTB), map style, keep screen on.

## Services used (all free, no API keys)

- Maps: MapLibre with OpenFreeMap tiles, optional CyclOSM layer (OpenStreetMap data)
- Routing and elevation: BRouter public server (brouter.de)
- Place search: Photon (photon.komoot.io)

These are shared community servers, fine for personal use. Maps and planning need
internet; recording does not.

## Where things live

- `data/` models, geometry, JSON file storage, GPX
- `net/Net.kt` BRouter and Photon calls
- `track/TrackingService.kt` foreground GPS recording
- `ui/` the screens and the map wrapper
- `MainActivity.kt` app shell and planning logic
