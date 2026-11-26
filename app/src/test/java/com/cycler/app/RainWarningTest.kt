package com.cycler.app

import com.cycler.app.net.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainWarningTest {
    private fun now(rain: Int) = Weather.Now(24.0, 10.0, 15.0, 90.0, rain, 61)

    @Test fun no_warning_below_the_threshold() {
        assertNull(now(0).rainWarning)
        assertNull(now(Weather.RAIN_WARN_PERCENT - 1).rainWarning)
    }

    @Test fun warns_from_the_threshold_with_the_chance() {
        val w = now(Weather.RAIN_WARN_PERCENT).rainWarning
        assertNotNull(w)
        assertTrue(w!!.contains("${Weather.RAIN_WARN_PERCENT}%"))
        assertTrue(now(90).rainWarning!!.contains("90%"))
    }

    @Test fun threshold_matches_the_weather_chip_warning_colour() {
        assertEquals(50, Weather.RAIN_WARN_PERCENT)
    }
}
