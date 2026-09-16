package dev.musicvisualizer.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

sealed interface AudioCaptureStatus {
    data object Idle : AudioCaptureStatus
    data object Starting : AudioCaptureStatus
    data class Capturing(val description: String) : AudioCaptureStatus
    data class Failed(val message: String, val canFallback: Boolean = false) : AudioCaptureStatus
}

interface AudioCapture : AutoCloseable {
    val samples: SharedFlow<FloatArray>
    val status: StateFlow<AudioCaptureStatus>
    suspend fun start()
    suspend fun stop()
    override fun close() = Unit
}

class ProcessBridgeAudioCapture(
    private val processId: Long,
    private val scope: CoroutineScope,
    private val bridgePath: Path = defaultBridgePath(),
) : AudioCapture {
    private val mutableSamples = MutableSharedFlow<FloatArray>(extraBufferCapacity = 4)
    override val samples: SharedFlow<FloatArray> = mutableSamples
    private val mutableStatus = MutableStateFlow<AudioCaptureStatus>(AudioCaptureStatus.Idle)
    override val status: StateFlow<AudioCaptureStatus> = mutableStatus
    private var process: Process? = null
    private var readerJob: Job? = null

    override suspend fun start() {
        if (!Files.isRegularFile(bridgePath)) {
            mutableStatus.value = AudioCaptureStatus.Failed(
                "プロセス音声ブリッジが見つかりません: $bridgePath",
                canFallback = true,
            )
            return
        }
        mutableStatus.value = AudioCaptureStatus.Starting
        runCatching {
            ProcessBuilder(
                bridgePath.toAbsolutePath().toString(),
                "--pid", processId.toString(),
                "--stdout-f32le",
                "--sample-rate", "48000",
                "--channels", "1",
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        }.onSuccess { child ->
            process = child
            mutableStatus.value = AudioCaptureStatus.Capturing("Chromeプロセス $processId")
            readerJob = scope.launch(Dispatchers.IO) { readSamples(child) }
        }.onFailure {
            mutableStatus.value = AudioCaptureStatus.Failed(it.message ?: "音声ブリッジを起動できません", true)
        }
    }

    private suspend fun readSamples(child: Process) {
        val input = BufferedInputStream(child.inputStream, 32 * 1024)
        val bytes = ByteArray(2048 * Float.SIZE_BYTES)
        try {
            while (scope.isActive && child.isAlive) {
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset == 0) break
                val floats = FloatArray(offset / Float.SIZE_BYTES)
                ByteBuffer.wrap(bytes, 0, offset).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                mutableSamples.emit(floats)
            }
            if (child.exitValue() != 0) {
                mutableStatus.value = AudioCaptureStatus.Failed("音声ブリッジが終了しました (${child.exitValue()})", true)
            }
        } catch (error: Exception) {
            if (child.isAlive) mutableStatus.value = AudioCaptureStatus.Failed(error.message ?: "音声取得に失敗しました", true)
        }
    }

    override suspend fun stop() {
        process?.destroy()
        readerJob?.cancelAndJoin()
        process = null
        readerJob = null
        mutableStatus.value = AudioCaptureStatus.Idle
    }

    override fun close() {
        process?.destroy()
    }

    companion object {
        fun defaultBridgePath(): Path {
            val appDirectory = System.getProperty("compose.application.resources.dir")
                ?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.dir"), "native", "bin")
            return appDirectory.resolve("ytmviz-audio-bridge.exe")
        }
    }
}

class JavaSoundLoopbackCapture(
    private val scope: CoroutineScope,
    private val preferredMixerName: String? = null,
) : AudioCapture {
    private val mutableSamples = MutableSharedFlow<FloatArray>(extraBufferCapacity = 4)
    override val samples: SharedFlow<FloatArray> = mutableSamples
    private val mutableStatus = MutableStateFlow<AudioCaptureStatus>(AudioCaptureStatus.Idle)
    override val status: StateFlow<AudioCaptureStatus> = mutableStatus
    private var line: TargetDataLine? = null
    private var job: Job? = null

    override suspend fun start() {
        mutableStatus.value = AudioCaptureStatus.Starting
        val format = AudioFormat(48_000f, 16, 2, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val mixers = AudioSystem.getMixerInfo().map { it to AudioSystem.getMixer(it) }
        val selected = mixers.firstOrNull { (mixerInfo, mixer) ->
            mixer.isLineSupported(info) && preferredMixerName?.let { mixerInfo.name.contains(it, true) } == true
        } ?: mixers.firstOrNull { (mixerInfo, mixer) ->
            mixer.isLineSupported(info) && LOOPBACK_NAMES.any { mixerInfo.name.contains(it, true) }
        }
        if (selected == null) {
            mutableStatus.value = AudioCaptureStatus.Failed("利用可能なシステム音声入力が見つかりません")
            return
        }
        runCatching {
            (selected.second.getLine(info) as TargetDataLine).also {
                it.open(format, 16 * 1024)
                it.start()
                line = it
            }
        }.onSuccess { target ->
            mutableStatus.value = AudioCaptureStatus.Capturing(selected.first.name)
            job = scope.launch(Dispatchers.IO) {
                val bytes = ByteArray(4096 * 4)
                while (isActive && target.isOpen) {
                    val count = target.read(bytes, 0, bytes.size)
                    if (count <= 0) continue
                    val frames = count / 4
                    val mono = FloatArray(frames)
                    for (frame in 0 until frames) {
                        val offset = frame * 4
                        val left = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF)).toShort()
                        val right = ((bytes[offset + 3].toInt() shl 8) or (bytes[offset + 2].toInt() and 0xFF)).toShort()
                        mono[frame] = ((left + right) / 2f) / Short.MAX_VALUE
                    }
                    mutableSamples.emit(mono)
                }
            }
        }.onFailure {
            mutableStatus.value = AudioCaptureStatus.Failed(it.message ?: "システム音声を取得できません")
        }
    }

    override suspend fun stop() {
        line?.stop()
        line?.close()
        job?.cancelAndJoin()
        line = null
        job = null
        mutableStatus.value = AudioCaptureStatus.Idle
    }

    override fun close() {
        line?.close()
    }

    companion object {
        private val LOOPBACK_NAMES = listOf("stereo mix", "what u hear", "waveout", "loopback", "mixed output")
    }
}
