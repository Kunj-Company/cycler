package com.cycler.app.ui

import com.cycler.app.MainVm

/** After preferences are restored from a backup, pull them into the values the screens are showing. */
fun MainVm.reloadSettings() {
    profile = prefs.profile
    mapStyle = prefs.mapStyle
    keepScreenOn = prefs.keepScreenOn
    themeMode = prefs.themeMode
    autoPause = prefs.autoPause
    autoPauseLevel = prefs.autoPauseLevel
    offRouteAlert = prefs.offRouteAlert
    voice = prefs.voice
    voiceEveryKm = prefs.voiceEveryKm
    turnCues = prefs.turnCues
    weeklyGoalKm = prefs.weeklyGoalKm
    advanced = prefs.advancedStats
    weightKg = prefs.weightKg
    autoLapKm = prefs.autoLapKm
    ridePages = prefs.ridePages
    bikeNewRides = prefs.bikeNewRides
    onboarded = onboarded || prefs.onboarded
}
