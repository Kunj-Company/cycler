package com.cycler.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cycler.app.data.Pt
import com.cycler.app.ui.Cy
import com.cycler.app.ui.CyclerTheme
import com.cycler.app.ui.ElevationChart
import com.cycler.app.ui.MiniBars
import com.cycler.app.ui.Segmented
import com.cycler.app.ui.tagInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Basic TalkBack and contrast expectations: selected state, chart summaries, WCAG AA text colours. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class A11yTest {
    @get:Rule val rule = createComposeRule()

    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun segmentedExposesSelected() {
        val sel = mutableStateOf(1)
        rule.setContent { CyclerTheme("light") { Segmented(listOf("Light", "System", "Dark"), sel.value, { sel.value = it }) } }
        rule.onNodeWithText("System").assertIsSelected()
        rule.onNodeWithText("Light").assertIsNotSelected()
        rule.onNodeWithText("Light").performClick()
        rule.onNodeWithText("Light").assertIsSelected()
        rule.onNodeWithText("System").assertIsNotSelected()
    }

    @Test fun bottomBarTabsExposeSelectedAndRole() {
        rule.setContent { CyclerTheme("light") { BottomBar(Tab.RIDES) {} } }
        rule.onNodeWithText("Rides").assertIsSelected()
        rule.onNodeWithText("Ride").assertIsNotSelected()
        val tab = SemanticsMatcher("is a Tab") { it.config.getOrNull(SemanticsProperties.Role)?.toString() == "Tab" }
        assertEquals(4, rule.onAllNodes(tab, useUnmergedTree = false).fetchSemanticsNodes().size)
    }

    @Test fun chartsHaveContentDescriptions() {
        val pts = (0..20).map { Pt(12.0 + it * 0.001, 77.0, 900.0 + it) }
        rule.setContent {
            CyclerTheme("light") {
                Column {
                    ElevationChart(pts)
                    MiniBars(listOf(10.0, 20.0, 15.0))
                }
            }
        }
        rule.onAllNodes(SemanticsMatcher("elevation description") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Elevation profile") } == true
        }).fetchSemanticsNodes().also { assertEquals(1, it.size) }
        rule.onAllNodes(SemanticsMatcher("bars description") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Bar chart, 3 values") } == true
        }).fetchSemanticsNodes().also { assertEquals(1, it.size) }
    }

    private fun checkContrast(mode: String) {
        lateinit var cy: com.cycler.app.ui.CyColors
        lateinit var scheme: androidx.compose.material3.ColorScheme
        rule.setContent { CyclerTheme(mode) { cy = Cy.colors; scheme = MaterialTheme.colorScheme } }
        rule.waitForIdle()
        val surface = scheme.surface
        for ((name, c) in listOf("warn" to cy.warn, "danger" to cy.danger)) {
            for (bg in listOf(surface, scheme.background, cy.subtle)) {
                assertTrue("$mode $name plain ${ratio(c, bg)}", ratio(c, bg) >= 4.5)
            }
            // Banners: the raw colour over its own 12% tint.
            for (base in listOf(surface, cy.subtle)) {
                val tint = c.copy(alpha = 0.12f).compositeOver(base)
                assertTrue("$mode $name banner ${ratio(c, tint)}", ratio(c, tint) >= 4.5)
            }
        }
        // Tags: the tag ink over the colour's 12% tint.
        for ((name, c) in listOf("success" to cy.success, "accent" to cy.accent, "warn" to cy.warn, "danger" to cy.danger)) {
            val ink = tagInk(c, cy.isDark)
            for (base in listOf(surface, cy.subtle)) {
                val tint = c.copy(alpha = 0.12f).compositeOver(base)
                assertTrue("$mode $name tag ${ratio(ink, tint)}", ratio(ink, tint) >= 4.5)
            }
        }
        assertTrue(ratio(scheme.onSurfaceVariant, surface) >= 4.5)
        assertTrue(ratio(scheme.onSurface, surface) >= 4.5)
    }

    @Test fun lightThemeTextColoursMeetAA() = checkContrast("light")

    @Test fun darkThemeTextColoursMeetAA() = checkContrast("dark")
}
