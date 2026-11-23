# Changelog

All notable changes to Cycler. Versions are tagged in Git as `v0.1.N`.

## 0.1.12

Prepared for Google Play. Same release key as 0.1.10 and 0.1.11.

### Fixed
- If Android stops the app during a ride, recording now restarts by itself and keeps your
  laps; if it can't, the ride so far is saved and you are told. A ride that can't be saved
  no longer makes the app crash every time you open it.
- Time spent in a tunnel or under heavy cover now counts as riding time, and distance moved
  while paused no longer disagrees with the saved track.
- The map works on phones without Vulkan graphics (the app crashed at start on some).
- GPX files with bad coordinates, and settings restored from a backup, can no longer crash
  the app.
- Elevation above sea level now also works on Android 8 to 13.
- Exported files stay valid when a ride name contains odd characters.
- Other apps can no longer start a ride through the widget.

### Changed
- Targets Android 16 (API 36).
- The in-app update check and the Updates page are gone. Download a new version from the
  Releases page and install it over the old one; your rides are kept.
- Android cloud backup is off. Use Back up everything in Settings to save a file yourself.
- The Privacy page lists exactly what each online request sends, and there is a standalone
  policy in `docs/PRIVACY.md`.
- Place search sends your position rounded to about 1 km instead of 100 m.
- Routing errors say what happened in plain words, with a Retry button.
- Discard asks for a second confirmation. Typed notes, the big-number screen and exports
  survive rotating the phone. The ride notification has Pause and Finish buttons.
- The map retries by itself when the signal comes back.
- Screen-reader labels for tabs, selections and charts, and stronger text contrast.
- A themed (monochrome) launcher icon.

## 0.1.11

### Fixed
- Elevation is shown above sea level. GPS reports height above a mathematical model of the
  Earth, which around India is about 90 m below sea level, so rides could show heights
  like −90 m. Rides recorded by earlier versions are corrected once, in the background
  (Android 14 and newer).

### Changed
- README with screenshots of the app and its widgets.

## 0.1.10

First release on GitHub, signed with the release key.

### Added
- Settings → Updates: check GitHub for a newer version and download it.
- About links to the source code and releases on GitHub.
- A Privacy page listing exactly what leaves the phone; Health Connect's privacy link opens it.
- Backups include your settings (bike, goal, recording, voice, ride screen and theme).

### Fixed
- GPS jumps (a bad fix, a tunnel) no longer add distance or inflate average speed and records.
- Restoring a backup only accepts files a Cycler backup contains, with size limits, so a
  damaged or crafted zip cannot overwrite or delete other data.
- The OpenStreetMap credit on the Ride screen is no longer hidden behind the bottom panel.
- The setup page said location is never sent anywhere; it now says what is sent and why.

### Changed
- Release builds are signed with a release key and shrunk with R8 (about half the size).
  Earlier debug-signed builds must be uninstalled first; back up in the app beforehand.

## 0.1.9

### Added
- First-launch setup: location, background recording, bike type and weekly goal.
- Home-screen widgets: Ride (start, pause, resume, lap, live numbers), This week,
  Last ride and Riding stats.
- Activity chart by week, month or year for distance, time or climb, with totals and a
  comparison with the previous period.
- Bike care page: editable jobs with kilometre and day intervals, notes and history.
  A job can be marked done today, on an earlier date or after a past ride.
- Rides: period filters, sort, search and tag filter. Saved routes can be sorted.
- About, What's new, Licence and Open-source notices pages.
- MIT licence and third-party notices.

### Changed
- Settings is a short list of sections instead of one long page.
- Activity page is shorter; bike care moved to its own page.
- The bike care list is included in backups.

## 0.1.8

### Added
- Lap button, optional auto-lap, and a lap table on ride pages.
- Interval workouts: presets and a custom builder, with prompts for each block.
- Configurable big-number ride screen with swipeable pages.
- Segments: cut one from a ride; every ride over it is timed and ranked.
- FIT and TCX export, and sharing a FIT file to other apps.
- Health Connect: heart rate for rides and rider weight.
- Offline maps: download the area around you.

### Changed
- Build: Android Gradle Plugin 8.9.1, compileSdk 36.
- Version numbers now use the 0.1.N form.

## 0.1.7
- Advanced stats on ride pages.
- 13 share card styles with an editor; flyover video is map-only.
- Plan tab: full-screen place search, start and end as one box, loop presets.
- Settings regrouped; backup and restore of all data.

## 0.1.6
- Tabs: Ride, Plan, Rides, Activity. Rides feed.
- Post-ride page: title, feeling, notes, tags, photos and videos.

## 0.1.5
- Turn cues, climb-ahead notes, traffic signals, big-number screen.
- Flyover video and share card; photos and videos on rides.
- Best efforts, all-rides map, bike care.

## 0.1.4
- Reliability fixes to recording, storage and route progress.
- Sunset warning, wind hint, speed splits, map style switcher.

## 0.1.3
- Redesign; Activity tab; weather; auto-pause; off-route alerts.

## 0.1.2
- Route suggestions near you; Start and End fields in planning.

## 0.1.1
- First version: record, plan and save rides.
