package dev.musicvisualizer.app

import dev.musicvisualizer.audio.AudioCapture
import dev.musicvisualizer.audio.AudioCaptureStatus
import dev.musicvisualizer.audio.JavaSoundLoopbackCapture
import dev.musicvisualizer.audio.SampleAccumulator
import dev.musicvisualizer.audio.SpectrumAnalyzer
import dev.musicvisualizer.audio.WasapiProcessLoopbackCapture
import dev.musicvisualizer.browser.ChromeController
import dev.musicvisualizer.media.ChromeDevToolsMediaBridge
import dev.musicvisualizer.model.AppSettings
import dev.musicvisualizer.model.BackgroundMode
import dev.musicvisualizer.model.PlaybackCommand
import dev.musicvisualizer.model.TrackInfo
import dev.musicvisualizer.model.VisualPreset
import dev.musicvisualizer.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import java.nio.file.Path

data class ActionFeedback(val glyph: String, val label: String, val nonce: Long = System.nanoTime())

class AppController(
    private val settingsRepository: SettingsRepository = SettingsRepository(),
    private val chromeController: ChromeController = ChromeController(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyzer = SpectrumAnalyzer()
    private val accumulator = SampleAccumulator()
    private val mediaBridge = ChromeDevToolsMediaBridge(scope, chromeController)
    private val mutableSettings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = mutableSettings
    val track: StateFlow<TrackInfo> = mediaBridge.track
    private val mutableSpectrum = MutableStateFlow(FloatArray(64))
    val spectrum: StateFlow<FloatArray> = mutableSpectrum
    private val mutableAudioStatus = MutableStateFlow<AudioCaptureStatus>(AudioCaptureStatus.Idle)
    val audioStatus: StateFlow<AudioCaptureStatus> = mutableAudioStatus
    private val mutableFeedback = MutableStateFlow<ActionFeedback?>(null)
    val feedback: StateFlow<ActionFeedback?> = mutableFeedback
    private val mutableSettingsVisible = MutableStateFlow(false)
    val settingsVisible: StateFlow<Boolean> = mutableSettingsVisible
    private val mutableFallbackPrompt = MutableStateFlow(false)
    val fallbackPrompt: StateFlow<Boolean> = mutableFallbackPrompt
    private val mutableLoginSetupVisible = MutableStateFlow(false)
    val loginSetupVisible: StateFlow<Boolean> = mutableLoginSetupVisible
    private var audioCapture: AudioCapture? = null
    private var audioSamplesJob: Job? = null
    private var audioStatusJob: Job? = null
    private var saveJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var decayJob: Job? = null
    private var chromePid: Long? = null
    private var lastSpectrumFrameNanos = 0L
    private var slowFrames = 0
    private var effectiveFps = 60

    suspend fun start() {
        mutableSettings.value = settingsRepository.load().sanitize()
        diagnosticsJob = scope.launch {
            mediaBridge.error.collectLatest { message ->
                if (message != null) System.err.println("[media] $message")
            }
        }
        decayJob = scope.launch {
            while (isActive) {
                delay(33)
                if (!track.value.isPlaying && mutableSpectrum.value.any { it > 0.001f }) {
                    mutableSpectrum.value = mutableSpectrum.value.map { it * 0.86f }.toFloatArray()
                }
            }
        }
        if (!chromeController.isLoginSetupComplete()) {
            openLoginChromeInternal()
            return
        }
        startControlledChrome()
    }

    private suspend fun startControlledChrome() {
        val chrome = chromeController.ensureRunning(mutableSettings.value.chromeMinimized)
        chrome.onSuccess { chromePid = it }
            .onFailure { showFeedback("!", it.message ?: "Chromeを起動できません") }
        mediaBridge.start()
        startProcessAudio()
    }

    fun openChromeForLogin() {
        scope.launch { openLoginChromeInternal() }
    }

    private suspend fun openLoginChromeInternal() {
        stopAudio()
        mediaBridge.stop()
        mutableLoginSetupVisible.value = true
        chromeController.openForLogin(minimized = false)
            .onSuccess { chromePid = it }
            .onFailure { showFeedback("!", it.message ?: "ログイン用Chromeを起動できません") }
    }

    fun completeLoginAndReconnect() {
        scope.launch {
            runCatching { chromeController.markLoginSetupComplete() }
                .onFailure {
                    showFeedback("!", it.message ?: "ログイン設定を保存できません")
                    return@launch
                }
            stopAudio()
            mediaBridge.stop()
            chromeController.restartControlled(mutableSettings.value.chromeMinimized)
                .onSuccess {
                    chromePid = it
                    mutableLoginSetupVisible.value = false
                    mediaBridge.start()
                    startProcessAudio()
                    showFeedback("✓", "YouTube Musicへ再接続しました")
                }
                .onFailure { showFeedback("!", it.message ?: "Chromeへ再接続できません") }
        }
    }

    fun showSettings() {
        mutableSettingsVisible.value = true
    }

    fun hideSettings() {
        mutableSettingsVisible.value = false
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        mutableSettings.value = transform(mutableSettings.value).sanitize()
        scheduleSave()
    }

    fun updateSelectedPreset(transform: (VisualPreset) -> VisualPreset) {
        updateSettings { current ->
            current.copy(presets = current.presets.map { preset ->
                if (preset.id == current.selectedPresetId) transform(preset).sanitize() else preset
            })
        }
    }

    fun setFixedBackground(path: Path) {
        updateSettings { it.copy(backgroundMode = BackgroundMode.FIXED_IMAGE, fixedImagePath = path.toAbsolutePath().toString()) }
    }

    fun perform(command: PlaybackCommand) {
        scope.launch {
            when (command) {
                PlaybackCommand.SEEK_BACK_10 -> {
                    mediaBridge.seekBy(-10_000)
                    showFeedback("−10", "10秒戻る")
                }
                PlaybackCommand.PREVIOUS -> {
                    mediaBridge.previous()
                    showFeedback("◀|", "前の曲")
                }
                PlaybackCommand.TOGGLE_PLAY_PAUSE -> {
                    mediaBridge.togglePlayPause()
                    showFeedback(if (track.value.isPlaying) "Ⅱ" else "▶", "再生／停止")
                }
                PlaybackCommand.NEXT_PRESET, PlaybackCommand.NONE -> Unit
                PlaybackCommand.SEEK_FORWARD_10 -> {
                    mediaBridge.seekBy(10_000)
                    showFeedback("+10", "10秒進む")
                }
                PlaybackCommand.NEXT -> {
                    mediaBridge.next()
                    showFeedback("|▶", "次の曲")
                }
            }
        }
    }

    fun acceptSystemAudioFallback() {
        mutableFallbackPrompt.value = false
        updateSettings { it.copy(allowSystemAudioFallback = true) }
        scope.launch { startSystemAudio() }
    }

    fun rejectSystemAudioFallback() {
        mutableFallbackPrompt.value = false
    }

    fun retryAudio() {
        scope.launch {
            if (!chromeController.isLoginSetupComplete()) {
                openLoginChromeInternal()
                return@launch
            }
            chromeController.ensureRunning(mutableSettings.value.chromeMinimized)
                .onSuccess { chromePid = it }
                .onFailure { showFeedback("!", it.message ?: "Chromeを起動できません") }
            startProcessAudio()
        }
    }

    private suspend fun startProcessAudio() {
        stopAudio()
        val pid = chromePid ?: chromeController.findManagedProcess()?.pid()
        if (pid == null) {
            mutableAudioStatus.value = AudioCaptureStatus.Failed("専用Chromeが見つかりません", true)
            mutableFallbackPrompt.value = true
            return
        }
        attachAudio(WasapiProcessLoopbackCapture(pid, scope))
    }

    private suspend fun startSystemAudio() {
        stopAudio()
        attachAudio(JavaSoundLoopbackCapture(scope, mutableSettings.value.audioDeviceName))
    }

    private suspend fun attachAudio(capture: AudioCapture) {
        audioCapture = capture
        audioSamplesJob = scope.launch {
            capture.samples.collectLatest { samples ->
                val preset = selectedPreset()
                val window = accumulator.append(samples)
                val requestedFps = if (mutableSettings.value.adaptiveFps) effectiveFps else mutableSettings.value.targetFps
                val now = System.nanoTime()
                if (now - lastSpectrumFrameNanos < 1_000_000_000L / requestedFps) return@collectLatest
                val started = System.nanoTime()
                mutableSpectrum.value = analyzer.analyze(
                    window,
                    preset.bandCount,
                    preset.sensitivity,
                    preset.smoothing,
                    preset.reaction,
                    preset.minFrequencyHz,
                    preset.maxFrequencyHz,
                )
                val elapsed = System.nanoTime() - started
                val budget = 1_000_000_000L / 60
                slowFrames = if (elapsed > budget * 3 / 4) slowFrames + 1 else (slowFrames - 1).coerceAtLeast(0)
                if (mutableSettings.value.adaptiveFps) {
                    if (slowFrames >= 20) effectiveFps = 30
                    else if (slowFrames == 0) effectiveFps = 60
                }
                lastSpectrumFrameNanos = now
            }
        }
        audioStatusJob = scope.launch {
            capture.status.collectLatest { status ->
                mutableAudioStatus.value = status
                System.err.println("[audio] $status")
                if (status is AudioCaptureStatus.Failed && status.canFallback) {
                    if (mutableSettings.value.allowSystemAudioFallback) scope.launch { startSystemAudio() }
                    else mutableFallbackPrompt.value = true
                }
            }
        }
        capture.start()
    }

    private suspend fun stopAudio() {
        audioSamplesJob?.cancel()
        audioStatusJob?.cancel()
        audioCapture?.stop()
        audioCapture?.close()
        audioCapture = null
        accumulator.clear()
        mutableSpectrum.value = FloatArray(selectedPreset().bandCount)
    }

    private fun selectedPreset(): VisualPreset = mutableSettings.value.presets.first()

    private fun showFeedback(glyph: String, label: String) {
        if (!mutableSettings.value.feedbackVisible) return
        val feedback = ActionFeedback(glyph, label)
        mutableFeedback.value = feedback
        scope.launch {
            delay(mutableSettings.value.feedbackDurationMillis)
            if (mutableFeedback.value == feedback) mutableFeedback.value = null
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(180)
            settingsRepository.save(mutableSettings.value)
        }
    }

    override fun close() {
        runBlocking {
            settingsRepository.save(mutableSettings.value)
            stopAudio()
            if (mutableSettings.value.closeChromeOnExit) chromeController.closeManagedProcess()
            mediaBridge.close()
        }
        scope.cancel()
    }
}

private fun AppSettings.sanitize(): AppSettings {
    val supplied = presets.ifEmpty { listOf(dev.musicvisualizer.model.defaultVisual()) }
    val visual = supplied.firstOrNull { it.id == selectedPresetId } ?: supplied.first()
    return copy(
        schemaVersion = 2,
        selectedPresetId = visual.id,
        presets = listOf(visual.sanitize()),
        presetCycle = emptyList(),
        clickBindings = clickBindings.copy(
            leftSingle = clickBindings.leftSingle.withoutPresetCommand(),
            leftDouble = clickBindings.leftDouble.withoutPresetCommand(),
            centerSingle = clickBindings.centerSingle.withoutPresetCommand(),
            centerDouble = clickBindings.centerDouble.withoutPresetCommand(),
            rightSingle = clickBindings.rightSingle.withoutPresetCommand(),
            rightDouble = clickBindings.rightDouble.withoutPresetCommand(),
        ),
        clickDelayMillis = clickDelayMillis.coerceIn(150, 600),
        leftZoneFraction = leftZoneFraction.coerceIn(0.15f, 0.4f),
        rightZoneFraction = rightZoneFraction.coerceIn(0.15f, 0.4f),
        feedbackDurationMillis = feedbackDurationMillis.coerceIn(200, 4_000),
        targetFps = if (targetFps <= 30) 30 else 60,
        windowWidth = windowWidth.coerceAtLeast(480),
        windowHeight = windowHeight.coerceAtLeast(320),
    )
}

private fun VisualPreset.sanitize(): VisualPreset {
    val minimum = minFrequencyHz.coerceIn(20f, 5_000f)
    return copy(
        bandCount = bandCount.coerceIn(8, 256),
        minFrequencyHz = minimum,
        maxFrequencyHz = maxFrequencyHz.coerceIn(minimum + 100f, 24_000f),
        sensitivity = sensitivity.coerceIn(0.01f, 8f),
        smoothing = smoothing.coerceIn(0f, 0.98f),
        lineWidth = lineWidth.coerceIn(0.5f, 18f),
        waveAlpha = waveAlpha.coerceIn(0.05f, 1f),
        linearY = linearY.coerceIn(0.05f, 0.95f),
        circleRadius = circleRadius.coerceIn(0.08f, 0.46f),
        artworkScale = artworkScale.coerceIn(0.08f, 1.5f),
        artworkX = artworkX.coerceIn(0f, 1f),
        artworkY = artworkY.coerceIn(0f, 1f),
    )
}

private fun PlaybackCommand.withoutPresetCommand(): PlaybackCommand =
    if (this == PlaybackCommand.NEXT_PRESET) PlaybackCommand.NONE else this
