package dev.musicvisualizer.audio

class SampleAccumulator(private val capacity: Int = 2048) {
    init { require(capacity > 0) }
    private val buffer = FloatArray(capacity)
    private var writeIndex = 0
    private var count = 0

    @Synchronized
    fun append(samples: FloatArray): FloatArray {
        samples.forEach { sample ->
            buffer[writeIndex] = sample
            writeIndex = (writeIndex + 1) % capacity
            if (count < capacity) count++
        }
        val result = FloatArray(capacity)
        val padding = capacity - count
        val oldest = if (count == capacity) writeIndex else 0
        for (index in 0 until count) result[padding + index] = buffer[(oldest + index) % capacity]
        return result
    }

    @Synchronized
    fun clear() {
        buffer.fill(0f)
        writeIndex = 0
        count = 0
    }
}
