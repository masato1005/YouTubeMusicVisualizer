package dev.musicvisualizer.audio

import com.sun.jna.Native
import kotlin.test.Test
import kotlin.test.assertEquals

class WasapiStructuresTest {
    @Test
    fun activationParametersMatchWindowsAbi() {
        assertEquals(12, WasapiProcessLoopbackCapture.AudioClientActivationParams().size())
    }

    @Test
    fun propVariantMatchesWindowsAbi() {
        val expected = if (Native.POINTER_SIZE == 8) 24 else 16
        assertEquals(expected, WasapiProcessLoopbackCapture.PropVariant().size())
    }

    @Test
    fun pcmFormatMatchesWaveFormatExAbi() {
        assertEquals(20, WasapiProcessLoopbackCapture.WaveFormatEx().size())
    }
}
