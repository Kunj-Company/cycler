package com.cycler.app

import com.cycler.app.data.SupportPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun waits_for_a_few_rides() {
        assertFalse(SupportPrompt.due(0, 0, now))
        assertFalse(SupportPrompt.due(SupportPrompt.MIN_RIDES - 1, 0, now))
        assertTrue(SupportPrompt.due(SupportPrompt.MIN_RIDES, 0, now))
    }

    @Test fun at_most_once_every_two_weeks() {
        assertFalse(SupportPrompt.due(10, now - 13 * day, now))
        assertTrue(SupportPrompt.due(10, now - 14 * day, now))
    }

    @Test fun a_clock_set_back_does_not_block_it_forever() {
        assertTrue(SupportPrompt.due(10, now + 400 * day, now))
    }
}
