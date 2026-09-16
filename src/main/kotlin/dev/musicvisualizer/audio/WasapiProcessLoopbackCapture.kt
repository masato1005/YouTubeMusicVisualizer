package dev.musicvisualizer.audio

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures only audio rendered by [targetProcessId] and its descendants by using the
 * Windows application-loopback activation contract introduced in build 20348.
 *
 * COM interfaces are invoked through their vtables so no separately compiled DLL is required.
 * All COM work and capture calls stay on the same MTA worker thread.
 */
class WasapiProcessLoopbackCapture(
    private val targetProcessId: Long,
    private val scope: CoroutineScope,
) : AudioCapture {
    private val mutableSamples = MutableSharedFlow<FloatArray>(extraBufferCapacity = 8)
    override val samples: SharedFlow<FloatArray> = mutableSamples
    private val mutableStatus = MutableStateFlow<AudioCaptureStatus>(AudioCaptureStatus.Idle)
    override val status: StateFlow<AudioCaptureStatus> = mutableStatus
    private var captureJob: Job? = null
    @Volatile private var stopRequested = false

    override suspend fun start() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            mutableStatus.value = AudioCaptureStatus.Failed("WASAPIはWindowsでのみ利用できます", true)
            return
        }
        if (captureJob?.isActive == true) return
        stopRequested = false
        mutableStatus.value = AudioCaptureStatus.Starting
        captureJob = scope.launch(Dispatchers.IO) { captureLoop() }
    }

    private suspend fun captureLoop() {
        val initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        if (initialized.toInt() < 0 && initialized.toInt() != RPC_E_CHANGED_MODE) {
            fail("COMを初期化できません (${hex(initialized.toInt())})")
            return
        }
        var audioClient: Pointer? = null
        var captureClient: Pointer? = null
        var event: WinNT.HANDLE? = null
        try {
            audioClient = activateAudioClient()
            val format = WaveFormatEx.pcmStereo(SAMPLE_RATE)
            val flags = AUDCLNT_STREAMFLAGS_LOOPBACK or AUDCLNT_STREAMFLAGS_EVENTCALLBACK or AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
            checkHr(comCall(audioClient, 3, audioClient, AUDCLNT_SHAREMODE_SHARED, flags, 0L, 0L, format.pointer, null), "IAudioClient.Initialize")
            event = Kernel32.INSTANCE.CreateEvent(null, false, false, null)
                ?: error("音声イベントを作成できません")
            checkHr(comCall(audioClient, 13, audioClient, event.pointer), "IAudioClient.SetEventHandle")

            val capturePointer = PointerByReference()
            val captureGuid = Guid.GUID(IID_IAUDIO_CAPTURE_CLIENT)
            checkHr(comCall(audioClient, 14, audioClient, captureGuid.pointer, capturePointer), "IAudioClient.GetService")
            captureClient = capturePointer.value ?: error("IAudioCaptureClientが返されませんでした")
            checkHr(comCall(audioClient, 10, audioClient), "IAudioClient.Start")
            mutableStatus.value = AudioCaptureStatus.Capturing("Chromeプロセス $targetProcessId")

            while (scope.isActive && !stopRequested) {
                val wait = Kernel32.INSTANCE.WaitForSingleObject(event, 500)
                if (wait == WAIT_TIMEOUT) continue
                if (wait != WinBase.WAIT_OBJECT_0) error("音声イベント待機に失敗しました ($wait)")
                drainPackets(captureClient)
            }
        } catch (error: Throwable) {
            if (!stopRequested) fail(error.message ?: "WASAPI音声取得に失敗しました")
        } finally {
            audioClient?.let { runCatching { comCall(it, 11, it) } }
            captureClient?.let(::releaseCom)
            audioClient?.let(::releaseCom)
            event?.let { Kernel32.INSTANCE.CloseHandle(it) }
            if (initialized.toInt() >= 0) Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun activateAudioClient(): Pointer {
        val activation = AudioClientActivationParams(targetProcessId.toInt())
        activation.write()
        val blob = Blob(activation.size(), activation.pointer)
        blob.write()
        val variant = PropVariant(blob)
        variant.write()

        val completion = ActivationCompletionHandler()
        val operation = PointerByReference()
        val function = NativeLibrary.getInstance("Mmdevapi").getFunction("ActivateAudioInterfaceAsync", Function.ALT_CONVENTION)
        val audioClientGuid = Guid.GUID(IID_IAUDIO_CLIENT)
        val hr = function.invokeInt(arrayOf(
            WString(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK),
            audioClientGuid.pointer,
            variant.pointer,
            completion.pointer,
            operation,
        ))
        checkHr(hr, "ActivateAudioInterfaceAsync")
        try {
            if (!completion.completed.await(10, TimeUnit.SECONDS)) error("WASAPIの有効化がタイムアウトしました")
            checkHr(completion.activationResult, "IActivateAudioInterfaceAsyncOperation.GetActivateResult")
            return completion.audioClient ?: error("IAudioClientが返されませんでした")
        } finally {
            operation.value?.let(::releaseCom)
        }
    }

    private suspend fun drainPackets(captureClient: Pointer) {
        val frames = IntByReference()
        while (true) {
            checkHr(comCall(captureClient, 5, captureClient, frames), "IAudioCaptureClient.GetNextPacketSize")
            if (frames.value <= 0) return
            val data = PointerByReference()
            val capturedFrames = IntByReference()
            val flags = IntByReference()
            val devicePosition = LongByReference()
            val qpcPosition = LongByReference()
            checkHr(
                comCall(captureClient, 3, captureClient, data, capturedFrames, flags, devicePosition, qpcPosition),
                "IAudioCaptureClient.GetBuffer",
            )
            val frameCount = capturedFrames.value
            try {
                val mono = FloatArray(frameCount)
                if (flags.value and AUDCLNT_BUFFERFLAGS_SILENT == 0 && data.value != null) {
                    val bytes = data.value.getByteArray(0, frameCount * FRAME_BYTES)
                    for (frame in 0 until frameCount) {
                        val offset = frame * FRAME_BYTES
                        val left = (((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)).toShort()
                        val right = (((bytes[offset + 3].toInt() and 0xFF) shl 8) or (bytes[offset + 2].toInt() and 0xFF)).toShort()
                        mono[frame] = ((left.toInt() + right.toInt()) / 2f) / Short.MAX_VALUE
                    }
                }
                mutableSamples.emit(mono)
            } finally {
                checkHr(comCall(captureClient, 4, captureClient, frameCount), "IAudioCaptureClient.ReleaseBuffer")
            }
        }
    }

    override suspend fun stop() {
        stopRequested = true
        captureJob?.cancelAndJoin()
        captureJob = null
        mutableStatus.value = AudioCaptureStatus.Idle
    }

    override fun close() {
        stopRequested = true
        captureJob?.cancel()
    }

    private fun fail(message: String) {
        mutableStatus.value = AudioCaptureStatus.Failed(message, canFallback = true)
    }

    private class ActivationCompletionHandler {
        val completed = CountDownLatch(1)
        val pointer: Pointer
        var activationResult: Int = E_UNEXPECTED
        var audioClient: Pointer? = null
        private val references = AtomicInteger(1)
        private val callbacks = mutableListOf<Callback>()
        private val vtable = Memory((Native.POINTER_SIZE * 4).toLong())
        private val instance = Memory(Native.POINTER_SIZE.toLong())

        init {
            val query = QueryInterfaceCallback { _, requestedIid, output ->
                if (guidEquals(requestedIid, IID_IUNKNOWN) ||
                    guidEquals(requestedIid, IID_IACTIVATE_AUDIO_INTERFACE_COMPLETION_HANDLER) ||
                    guidEquals(requestedIid, IID_IAGILE_OBJECT)
                ) {
                    output.value = instance
                    references.incrementAndGet()
                    S_OK
                } else {
                    output.value = null
                    E_NOINTERFACE
                }
            }
            val addRef = RefCallback { references.incrementAndGet() }
            val release = RefCallback { references.decrementAndGet().coerceAtLeast(0) }
            val activate = ActivateCallback { _, operation ->
                try {
                    val result = IntByReference(E_UNEXPECTED)
                    val activated = PointerByReference()
                    val hr = comCall(operation, 3, operation, result, activated)
                    activationResult = if (hr < 0) hr else result.value
                    audioClient = activated.value
                } finally {
                    completed.countDown()
                }
                S_OK
            }
            callbacks += listOf(query, addRef, release, activate)
            callbacks.forEachIndexed { index, callback ->
                vtable.setPointer((index * Native.POINTER_SIZE).toLong(), com.sun.jna.CallbackReference.getFunctionPointer(callback))
            }
            instance.setPointer(0, vtable)
            pointer = instance
        }
    }

    private fun interface QueryInterfaceCallback : com.sun.jna.win32.StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer, iid: Pointer, output: PointerByReference): Int
    }

    private fun interface RefCallback : com.sun.jna.win32.StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer): Int
    }

    private fun interface ActivateCallback : com.sun.jna.win32.StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer, operation: Pointer): Int
    }

    @Structure.FieldOrder("activationType", "processLoopbackParams")
    internal class AudioClientActivationParams(processId: Int = 0) : Structure() {
        @JvmField var activationType: Int = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
        @JvmField var processLoopbackParams: ProcessLoopbackParams = ProcessLoopbackParams(processId)
    }

    @Structure.FieldOrder("targetProcessId", "processLoopbackMode")
    internal class ProcessLoopbackParams(processId: Int = 0) : Structure() {
        @JvmField var targetProcessId: Int = processId
        @JvmField var processLoopbackMode: Int = PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE
    }

    @Structure.FieldOrder("size", "data")
    internal class Blob(size: Int = 0, data: Pointer? = null) : Structure() {
        @JvmField var size: Int = size
        @JvmField var data: Pointer? = data
    }

    @Structure.FieldOrder("variantType", "reserved1", "reserved2", "reserved3", "blob")
    internal class PropVariant(blob: Blob = Blob()) : Structure() {
        @JvmField var variantType: Short = VT_BLOB
        @JvmField var reserved1: Short = 0
        @JvmField var reserved2: Short = 0
        @JvmField var reserved3: Short = 0
        @JvmField var blob: Blob = blob
    }

    @Structure.FieldOrder("formatTag", "channels", "samplesPerSecond", "averageBytesPerSecond", "blockAlign", "bitsPerSample", "extraSize")
    internal class WaveFormatEx : Structure() {
        @JvmField var formatTag: Short = WAVE_FORMAT_PCM
        @JvmField var channels: Short = 2
        @JvmField var samplesPerSecond: Int = SAMPLE_RATE
        @JvmField var averageBytesPerSecond: Int = SAMPLE_RATE * FRAME_BYTES
        @JvmField var blockAlign: Short = FRAME_BYTES.toShort()
        @JvmField var bitsPerSample: Short = 16
        @JvmField var extraSize: Short = 0

        companion object {
            fun pcmStereo(sampleRate: Int) = WaveFormatEx().apply {
                samplesPerSecond = sampleRate
                averageBytesPerSecond = sampleRate * FRAME_BYTES
                write()
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_BYTES = 4
        private const val WAVE_FORMAT_PCM: Short = 1
        private const val AUDCLNT_SHAREMODE_SHARED = 0
        private const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
        private const val AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000
        private const val AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000.toInt()
        private const val AUDCLNT_BUFFERFLAGS_SILENT = 0x2
        private const val WAIT_TIMEOUT = 258
        private const val AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK = 1
        private const val PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE = 0
        private const val VT_BLOB: Short = 65
        private const val S_OK = 0
        private const val E_UNEXPECTED = 0x8000FFFF.toInt()
        private const val E_NOINTERFACE = 0x80004002.toInt()
        private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
        private const val VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK = "VAD\\Process_Loopback"
        private const val IID_IAUDIO_CLIENT = "{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}"
        private const val IID_IAUDIO_CAPTURE_CLIENT = "{C8ADBD64-E71E-48A0-A4DE-185C395CD317}"
        private const val IID_IUNKNOWN = "{00000000-0000-0000-C000-000000000046}"
        private const val IID_IACTIVATE_AUDIO_INTERFACE_COMPLETION_HANDLER = "{41D949AB-9862-444A-80F6-C261334DA5EB}"
        private const val IID_IAGILE_OBJECT = "{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}"

        private fun comCall(instance: Pointer, methodIndex: Int, vararg arguments: Any?): Int {
            val vtable = instance.getPointer(0)
            val address = vtable.getPointer((methodIndex * Native.POINTER_SIZE).toLong())
            return Function.getFunction(address, Function.ALT_CONVENTION).invokeInt(arguments)
        }

        private fun releaseCom(instance: Pointer) {
            runCatching { comCall(instance, 2, instance) }
        }

        private fun guidEquals(pointer: Pointer?, value: String): Boolean {
            if (pointer == null) return false
            return pointer.getByteArray(0, 16).contentEquals(Guid.GUID(value).pointer.getByteArray(0, 16))
        }

        private fun checkHr(hr: Int, operation: String) {
            if (hr < 0) error("$operation failed (${hex(hr)})")
        }

        private fun hex(value: Int): String = "0x%08X".format(value)
    }
}
