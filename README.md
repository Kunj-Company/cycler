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
- **While following**: the next turn and its distance, a nudge before each turn, climbs
  coming up, traffic signals on the map, and a big-number screen for the handlebar.
- **Plan → A to B**: Start and End fields, optional stops, search or long-press the map.
  Up to three routes with distance, climb, time, difficulty and an elevation profile.
- **Plan → Loop**: pick a distance (5–200 km), get round trips in four directions.
- **Plan → Saved**: "Suggest routes near me" (short, medium, long loops) and your saved
  routes, with GPX import.
- **Rides**: a feed of your finished rides, newest first, with stats, route shape,
  photos, how it felt, notes and tags.
- **After a ride**: a save page for the title, how it felt, notes, tags, photos and videos.
  The same page edits a ride later.
- **Activity**: this week against your goal, last 8 weeks, month and all-time totals, records.
- **Ride page**: attach photos and videos, see stops, stopped time and signal count.
- **Share**: an animated 3D flyover video of the ride (slow start, fast middle, slow end,
  then your photos and a summary), or a still card. Both go to the system share sheet.
- **Activity extras**: best efforts over 1, 5, 10, 20 and 40 km, all rides on one map,
  and bike care reminders by kilometres ridden.
- **Settings**: light/dark, map style, bike type, auto-pause, off-route alert,
  spoken updates every kilometre, weekly goal.

## Services used (all free, no API keys)

- Maps: MapLibre with OpenFreeMap tiles, optional CyclOSM layer (OpenStreetMap data)
- Routing and elevation: BRouter public server (brouter.de)
- Place search: Photon (photon.komoot.io)
- Weather: Open-Meteo
- Traffic signals: Overpass API (OpenStreetMap data)

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
- `share/` the share card, the flyover renderer and the MP4 encoder
- `track/TrackingService.kt` foreground GPS recording, auto-pause, off-route alerts
- `ui/` theme, components, screens and the map wrapper
- `MainActivity.kt` app shell and planning logic
