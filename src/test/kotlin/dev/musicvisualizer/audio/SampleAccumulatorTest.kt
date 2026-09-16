package dev.musicvisualizer.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals

class SampleAccumulatorTest {
    @Test
    fun padsUntilFullAndKeepsNewestSamples() {
        val accumulator = SampleAccumulator(4)
        assertContentEquals(floatArrayOf(0f, 0f, 1f, 2f), accumulator.append(floatArrayOf(1f, 2f)))
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), accumulator.append(floatArrayOf(3f, 4f)))
        assertContentEquals(floatArrayOf(3f, 4f, 5f, 6f), accumulator.append(floatArrayOf(5f, 6f)))
    }

    @Test
    fun clearRestoresSilence() {
        val accumulator = SampleAccumulator(3)
        accumulator.append(floatArrayOf(1f, 2f, 3f))
        accumulator.clear()
        assertContentEquals(floatArrayOf(0f, 0f, 9f), accumulator.append(floatArrayOf(9f)))
    }
}
