package com.cycler.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.cycler.app.data.RouteStore
import com.cycler.app.ui.CyclerTheme
import com.cycler.app.ui.Onboarding
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders each onboarding step to app/build/shots/onb_*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class OnboardingShots {
    @get:Rule val rule = createComposeRule()

    private lateinit var vm: MainVm

    @Before fun setUp() {
        RouteStore.resetForTests()
        vm = MainVm(ApplicationProvider.getApplicationContext())
    }

    private fun shot(name: String, step: Int, dark: Boolean = false, granted: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                CyclerTheme(if (dark) "dark" else "light") { Onboarding(vm, granted, {}, step) {} }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test fun welcome() = shot("onb_1_welcome", 0)
    @Test fun location() = shot("onb_2_location", 1)
    @Test fun location_granted() = shot("onb_2b_location_granted", 1, granted = true)
    @Test fun background() = shot("onb_3_background", 2)
    @Test fun riding() = shot("onb_4_riding", 3)
    @Test fun ready() = shot("onb_5_ready", 4)
    @Test fun welcome_dark() = shot("onb_1_welcome_dark", 0, dark = true)
    @Test fun riding_dark() = shot("onb_4_riding_dark", 3, dark = true)

    @Test fun fresh_install_is_not_onboarded() {
        assertFalse(vm.onboarded)
    }

    @Test fun existing_rides_skip_onboarding() {
        vm.store.save("Morning ride", com.cycler.app.data.RouteType.RECORDED, emptyList(), 60, 1000.0, 0.0)
        val again = MainVm(ApplicationProvider.getApplicationContext())
        assertEquals(true, again.onboarded)
    }
}
