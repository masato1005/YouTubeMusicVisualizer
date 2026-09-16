package dev.musicvisualizer.model

import kotlin.test.Test
import kotlin.test.assertTrue

class DefaultPresetsTest {
    @Test
    fun defaultsReachFurtherIntoHighFrequencies() {
        assertTrue(defaultVisual().maxFrequencyHz == 22_000f)
    }

    @Test
    fun settingsStartWithOneEditableVisual() {
        assertTrue(AppSettings().presets.size == 1)
        assertTrue(AppSettings().presetCycle.isEmpty())
    }
}
