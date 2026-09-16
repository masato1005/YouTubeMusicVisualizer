package dev.musicvisualizer.audio

import dev.musicvisualizer.model.ReactionProfile
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrumAnalyzerTest {
    @Test
    fun silenceProducesZeroSpectrum() {
        val analyzer = SpectrumAnalyzer()
        val result = analyzer.analyze(FloatArray(2048), 64, 1f, 0f, ReactionProfile.BALANCED)
        assertEquals(64, result.size)
        assertTrue(result.all { it == 0f })
    }

    @Test
    fun sineWavePeaksNearItsFrequencyBand() {
        val sampleRate = 48_000
        val input = FloatArray(2048) { index -> sin(2.0 * PI * 440.0 * index / sampleRate).toFloat() * 0.6f }
        val result = SpectrumAnalyzer(sampleRate = sampleRate)
            .analyze(input, 64, 1f, 0f, ReactionProfile.BALANCED)
        val peak = result.indices.maxBy { result[it] }
        assertTrue(peak in 20..31, "Expected the 440 Hz peak in a middle-low log band, got $peak")
        assertTrue(result[peak] > 0.5f)
    }

    @Test
    fun outputIsAlwaysNormalized() {
        val input = FloatArray(2048) { if (it % 2 == 0) 1f else -1f }
        val result = SpectrumAnalyzer().analyze(input, 128, 20f, 0f, ReactionProfile.BASS)
        assertTrue(result.all { it in 0f..1f })
    }

    @Test
    fun configurableUpperRangeCanRepresentTwentyKilohertz() {
        val sampleRate = 48_000
        val frequency = 20_250.0
        val input = FloatArray(2048) { index ->
            sin(2.0 * PI * frequency * index / sampleRate).toFloat() * 0.6f
        }
        val extended = SpectrumAnalyzer(sampleRate = sampleRate)
            .analyze(input, 64, 1f, 0f, ReactionProfile.BALANCED, 35f, 22_000f)
        val limited = SpectrumAnalyzer(sampleRate = sampleRate)
            .analyze(input, 64, 1f, 0f, ReactionProfile.BALANCED, 35f, 18_000f)

        assertTrue(extended.max() > 0.5f)
        assertTrue(extended.max() > limited.max() * 4f)
        assertTrue(extended.indices.maxBy { extended[it] } >= 60)
    }

    @Test
    fun minimumSensitivityDoesNotSaturateOnALoudTone() {
        val sampleRate = 48_000
        val input = FloatArray(2048) { index -> sin(2.0 * PI * 440.0 * index / sampleRate).toFloat() }
        val result = SpectrumAnalyzer(sampleRate = sampleRate)
            .analyze(input, 64, 0.01f, 0f, ReactionProfile.BALANCED)

        assertTrue(result.max() in 0.001f..0.25f, "Minimum sensitivity should remain controllable, got ${result.max()}")
    }
}
