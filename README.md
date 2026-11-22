# Cycler

A personal cycling app for Android: record rides, plan routes, get loop suggestions.
No account, no cloud. Everything is saved on the phone.

## Run it

1. Open this folder in Android Studio and let Gradle sync.
2. Plug in the phone (USB debugging on) and press Run.

Or install the prebuilt debug build directly:

    adb install -r Cycler-debug.apk

## What it does

- **Ride**: live map, start / pause / finish. Keeps recording with the screen off.
  Distance, speed, moving time, average, max, climb. Auto-pause when you stop.
  Wind, temperature and rain chance on the map. Weekly goal progress and a sunset
  warning before you start. Switch map style from the map itself.
- **Follow a route**: pick any saved or planned route and ride it. Shows distance to go,
  and vibrates if you leave the route.
- **Plan → A to B**: Start and End fields, optional stops, search or long-press the map.
  Up to three routes with distance, climb, time, difficulty and an elevation profile.
- **Plan → Loop**: pick a distance (5–200 km), get round trips in four directions.
- **Plan → My rides**: saved routes nearest first; ride one again or save it reversed.
- **Routes**: "Suggest routes near me" (short, medium, long loops), plus everything saved,
  with outline, stats, difficulty, rename, delete, GPX import/export.
- **Activity**: this week against your goal, last 8 weeks, month and all-time totals, records.
- **Settings**: light/dark, map style, bike type, auto-pause, off-route alert,
  spoken updates every kilometre, weekly goal.

## Services used (all free, no API keys)

- Maps: MapLibre with OpenFreeMap tiles, optional CyclOSM layer (OpenStreetMap data)
- Routing and elevation: BRouter public server (brouter.de)
- Place search: Photon (photon.komoot.io)
- Weather: Open-Meteo

These are shared community servers, fine for personal use. Maps, planning and weather
need internet; recording does not.

## Design

Neutral surfaces, hairline borders, ink-coloured buttons, one blue accent, Inter type.
Tokens live in `ui/Theme.kt`, shared pieces (buttons, segmented control, sheet, metrics,
charts) in `ui/Common.kt`.

## Tests and screenshots without a phone

    ./gradlew testDebugUnitTest

checks the distance, off-route and speed maths (`GeoTest`) and renders every screen, light and dark, to `app/build/shots/` using made-up rides.
The map is drawn as a sketch there; real tiles only appear on a device.

## Where things live

- `data/` models, geometry, JSON file storage, GPX
- `net/Net.kt` BRouter, Photon and weather calls
- `track/TrackingService.kt` foreground GPS recording, auto-pause, off-route alerts
- `ui/` theme, components, screens and the map wrapper
- `MainActivity.kt` app shell and planning logic
