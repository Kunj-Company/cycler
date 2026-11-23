package com.cycler.app.ui

/** What changed in each release, newest first. Shown on the What's new page in Settings. */
object Changelog {
    class Version(val version: String, val lines: List<String>)

    val versions: List<Version> = listOf(
        Version(
            "Unreleased",
            listOf(
                "The in-app update check is gone; updates now come from the store you installed from.",
                "Android cloud backup is off. Use Back up everything to save a file yourself.",
                "The Privacy page now lists exactly what each online request sends.",
                "Place search sends your position rounded to about 1 km instead of 100 m.",
            ),
        ),
        Version(
            "0.1.11",
            listOf(
                "Elevation is now shown above sea level. Earlier rides are corrected too (Android 14 and newer).",
            ),
        ),
        Version(
            "0.1.10",
            listOf(
                "Check for updates in Settings → Updates.",
                "About links to the source code on GitHub, and a new Privacy page says what leaves the phone.",
                "Backups now include your settings.",
                "GPS jumps no longer add distance or spoil your average speed and records.",
                "Safer restore from backups.",
                "The OpenStreetMap credit is visible on the Ride screen again.",
                "Signed release build, about half the size.",
            ),
        ),
        Version(
            "0.1.9",
            listOf(
                "A short setup the first time you open the app.",
                "Home-screen widgets: Ride (start, pause, lap), This week, Last ride and Riding stats.",
                "Activity: one chart by week, month or year for distance, time or climb.",
                "Bike care page: edit jobs, add your own, and mark one done today, on an earlier date or after a ride.",
                "Rides: filter by this week, month or year, sort, search and filter by tag.",
                "Settings in sections, with About, What's new and licence pages.",
                "Cycler is now open source under the MIT licence.",
            ),
        ),
        Version(
            "0.1.8",
            listOf(
                "Lap button, optional auto-lap, and a lap table on ride pages.",
                "Interval workouts with presets and a custom builder.",
                "A configurable big-number ride screen with swipeable pages.",
                "Segments: cut one from a ride and see every ride over it timed and ranked.",
                "FIT and TCX export, and Health Connect for heart rate and weight.",
                "Offline maps for the area around you.",
            ),
        ),
        Version(
            "0.1.7",
            listOf(
                "Advanced stats on ride pages.",
                "13 share card styles with an editor; flyover video is map-only.",
                "Plan tab: full-screen place search, start and end in one box, loop presets.",
                "Settings regrouped, with backup and restore of all data.",
            ),
        ),
        Version(
            "0.1.6",
            listOf(
                "Tabs: Ride, Plan, Rides and Activity, with a rides feed.",
                "Post-ride page for a title, feeling, notes, tags, photos and videos.",
            ),
        ),
        Version(
            "0.1.5",
            listOf(
                "Turn cues, climb-ahead notes, traffic signals and a big-number screen.",
                "Flyover video and share card; photos and videos on rides.",
                "Best efforts, an all-rides map and bike care.",
            ),
        ),
        Version(
            "0.1.4",
            listOf(
                "Reliability fixes to recording, storage and route progress.",
                "Sunset warning, wind hint, speed splits and a map style switcher.",
            ),
        ),
        Version(
            "0.1.3",
            listOf("Redesign, Activity tab, weather, auto-pause and off-route alerts."),
        ),
        Version(
            "0.1.2",
            listOf("Route suggestions near you, and Start and End fields in planning."),
        ),
        Version(
            "0.1.1",
            listOf("First version: record, plan and save rides."),
        ),
    )
}
