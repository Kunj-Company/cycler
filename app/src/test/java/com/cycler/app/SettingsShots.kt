package com.cycler.app

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.RouteStore
import com.cycler.app.ui.CyclerTheme
import com.cycler.app.ui.SettingsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the Settings hub and each of its pages to app/build/shots/set_*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class SettingsShots {
    @get:Rule val rule = createComposeRule()

    private lateinit var vm: MainVm

    @Before fun setUp() {
        RouteStore.resetForTests()
        vm = MainVm(ApplicationProvider.getApplicationContext<Application>())
    }

    private fun shot(name: String, page: String? = null, dark: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CyclerTheme(if (dark) "dark" else "light") { SettingsScreen(vm, startPage = page) }
            }
        }
        repeat(2) { rule.waitForIdle(); Thread.sleep(200) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test fun hub() = shot("set_00_hub")
    @Test fun hub_dark() = shot("set_00_hub_dark", dark = true)
    @Test fun appearance() = shot("set_01_appearance", "appearance")
    @Test fun bike() = shot("set_02_bike", "bike")
    @Test fun recording() = shot("set_03_recording", "recording")
    @Test fun recording_dark() = shot("set_03_recording_dark", "recording", dark = true)
    @Test fun alerts() = shot("set_04_alerts", "alerts")
    @Test fun goals() = shot("set_05_goals", "goals")
    @Test fun health() = shot("set_06_health", "health")
    @Test fun data() = shot("set_07_data", "data")
    @Test fun phone() = shot("set_08_phone", "phone")
    @Test fun about() = shot("set_09_about", "about")
    @Test fun about_dark() = shot("set_09_about_dark", "about", dark = true)
    @Test fun whatsnew() = shot("set_10_whatsnew", "whatsnew")
    @Test fun licence() = shot("set_11_licence", "licence")
    @Test fun licence_dark() = shot("set_11_licence_dark", "licence", dark = true)
    @Test fun notices() = shot("set_12_notices", "notices")
    @Test fun recording_off() {
        vm.autoPause = false
        shot("set_03_recording_off", "recording")
    }
    @Test fun alerts_voice_on() {
        vm.voice = true
        shot("set_04_alerts_voice", "alerts")
    }
}
