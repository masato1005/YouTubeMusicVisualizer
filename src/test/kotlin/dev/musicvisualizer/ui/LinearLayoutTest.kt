package dev.musicvisualizer.ui

import dev.musicvisualizer.model.LinearDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class LinearLayoutTest {
    @Test
    fun oneSidedLineUsesTheWindowEdgeAsItsBaseline() {
        assertEquals(0.96f, linearBaselineFraction(LinearDirection.UP, configured = 0.5f))
        assertEquals(0.04f, linearBaselineFraction(LinearDirection.DOWN, configured = 0.5f))
        assertEquals(0.5f, linearBaselineFraction(LinearDirection.MIRRORED, configured = 0.5f))
    }
}
