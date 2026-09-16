package dev.musicvisualizer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowBehaviorTest {
    @Test
    fun pointerInputBackgroundUsesTheMinimumNonZeroAlpha() {
        assertEquals(1, POINTER_INPUT_BACKGROUND_ALPHA)
    }
}
