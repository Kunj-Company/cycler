# Changelog

All notable changes to Cycler. Versions are tagged in Git as `v0.1.N`.

## Unreleased

### Changed
- Removed the in-app update check and the Updates page. Nothing opens an APK download.
- Android cloud backup is off (`allowBackup="false"`); the in-app zip backup is the only backup.
- The Privacy page, onboarding text and README now state exactly what each online request sends.
- Place search sends your position rounded to about 1 km (2 decimals) instead of 4 decimals.
- Added a standalone privacy policy in `docs/PRIVACY.md`, linked from About.
- Neutral wording for file export in the README and onboarding.

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
