package dev.musicvisualizer.audio

import dev.musicvisualizer.model.ReactionProfile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrumAnalyzer(
    private val fftSize: Int = 2048,
    private val sampleRate: Int = 48_000,
) {
    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)
    private var previous = FloatArray(0)

    @Synchronized
    fun analyze(
        input: FloatArray,
        bandCount: Int,
        sensitivity: Float,
        smoothing: Float,
        reaction: ReactionProfile,
        minFrequencyHz: Float = 35f,
        maxFrequencyHz: Float = 22_000f,
    ): FloatArray {
        require(bandCount in 8..256)
        val start = max(0, input.size - fftSize)
        for (index in 0 until fftSize) {
            val sample = input.getOrElse(start + index) { 0f }.toDouble()
            val window = 0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1))
            real[index] = sample * window
            imaginary[index] = 0.0
        }
        fft(real, imaginary)

        val output = FloatArray(bandCount)
        val minimumBinFrequency = sampleRate.toDouble() / fftSize
        val minFrequency = minFrequencyHz.toDouble().coerceIn(minimumBinFrequency, sampleRate / 2.0 - minimumBinFrequency)
        val maxFrequency = maxFrequencyHz.toDouble().coerceIn(minFrequency + minimumBinFrequency, sampleRate / 2.0)
        val logRange = ln(maxFrequency / minFrequency)
        for (band in 0 until bandCount) {
            val low = minFrequency * kotlin.math.exp(logRange * band / bandCount)
            val high = minFrequency * kotlin.math.exp(logRange * (band + 1) / bandCount)
            val lowBin = max(1, (low * fftSize / sampleRate).toInt())
            val highBin = min(fftSize / 2 - 1, max(lowBin, (high * fftSize / sampleRate).toInt()))
            var energy = 0.0
            for (bin in lowBin..highBin) {
                energy = max(energy, sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]))
            }
            // The FFT magnitude grows with fftSize. Normalize it before applying
            // user sensitivity so the low end of the slider remains useful. A
            // peak is used so wide high-frequency bands do not bury tonal audio.
            energy /= fftSize
            val frequencyWeight = when (reaction) {
                ReactionProfile.BASS -> 1.45 - (band.toDouble() / bandCount) * 0.65
                ReactionProfile.CALM -> 0.82
                ReactionProfile.BALANCED -> 1.0
            }
            output[band] = (1.0 - kotlin.math.exp(-energy * sensitivity * frequencyWeight * 10.0))
                .toFloat().coerceIn(0f, 1f)
        }

        if (previous.size != bandCount) previous = FloatArray(bandCount)
        val blend = smoothing.coerceIn(0f, 0.98f)
        for (index in output.indices) {
            val fallingBlend = if (output[index] < previous[index]) blend else blend * 0.55f
            output[index] = previous[index] * fallingBlend + output[index] * (1f - fallingBlend)
        }
        previous = output.copyOf()
        return output
    }

    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLengthReal = cos(angle)
            val wLengthImaginary = sin(angle)
            var offset = 0
            while (offset < n) {
                var wr = 1.0
                var wi = 0.0
                for (index in 0 until length / 2) {
                    val even = offset + index
                    val odd = even + length / 2
                    val oddReal = re[odd] * wr - im[odd] * wi
                    val oddImaginary = re[odd] * wi + im[odd] * wr
                    re[odd] = re[even] - oddReal
                    im[odd] = im[even] - oddImaginary
                    re[even] += oddReal
                    im[even] += oddImaginary
                    val nextWr = wr * wLengthReal - wi * wLengthImaginary
                    wi = wr * wLengthImaginary + wi * wLengthReal
                    wr = nextWr
                }
                offset += length
            }
            length = length shl 1
        }
    }
}
