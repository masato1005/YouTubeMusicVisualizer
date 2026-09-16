package dev.musicvisualizer.model

import kotlinx.serialization.Serializable

@Serializable
enum class VisualizerLayout { LINEAR, CIRCULAR }

@Serializable
enum class VisualizerPrimitive { SMOOTH_LINE, BARS }

@Serializable
enum class LinearDirection(private val label: String) {
    UP("上のみ"),
    DOWN("下のみ"),
    MIRRORED("上下対称");

    override fun toString(): String = label
}

@Serializable
enum class ReactionProfile { BALANCED, BASS, CALM }

@Serializable
enum class BackgroundMode { AUTOMATIC_ARTWORK, FIXED_IMAGE }

@Serializable
enum class BackgroundFill { TRANSPARENT, SOLID, BLURRED_ARTWORK }

@Serializable
enum class ArtworkFit { COVER, CONTAIN, FREE }

@Serializable
enum class WaveColorMode { SOLID, GRADIENT, FROM_ARTWORK }

@Serializable
enum class TrackInfoVisibility { ALWAYS, ON_TRACK_CHANGE, ON_CLICK, NEVER }

@Serializable
data class VisualPreset(
    val id: String,
    val name: String,
    val layout: VisualizerLayout,
    val primitive: VisualizerPrimitive = VisualizerPrimitive.SMOOTH_LINE,
    val reaction: ReactionProfile,
    val bandCount: Int = 64,
    val minFrequencyHz: Float = 35f,
    val maxFrequencyHz: Float = 22_000f,
    val sensitivity: Float = 1.0f,
    val smoothing: Float = 0.72f,
    val lineWidth: Float = 3f,
    val waveAlpha: Float = 0.94f,
    val waveColor: Long = 0xFFFFFFFF,
    val secondaryColor: Long = 0xFF6AD5FF,
    val colorMode: WaveColorMode = WaveColorMode.FROM_ARTWORK,
    val linearY: Float = 0.5f,
    val linearMirrored: Boolean = true,
    val linearDirection: LinearDirection? = null,
    val circleRadius: Float = 0.27f,
    val showArtworkInCircle: Boolean = true,
    val artworkScale: Float = 0.42f,
    val artworkX: Float = 0.5f,
    val artworkY: Float = 0.5f,
    val artworkFit: ArtworkFit = ArtworkFit.FREE,
)

@Serializable
data class AppSettings(
    val schemaVersion: Int = 2,
    val selectedPresetId: String = "visual",
    val presets: List<VisualPreset> = listOf(defaultVisual()),
    val presetCycle: List<String> = emptyList(),
    val backgroundMode: BackgroundMode = BackgroundMode.AUTOMATIC_ARTWORK,
    val backgroundFill: BackgroundFill = BackgroundFill.TRANSPARENT,
    val fixedImagePath: String? = null,
    val backgroundColor: Long = 0xFF080A0F,
    val backgroundDim: Float = 0.18f,
    val backgroundBlur: Float = 18f,
    val trackInfoVisibility: TrackInfoVisibility = TrackInfoVisibility.ON_TRACK_CHANGE,
    val feedbackVisible: Boolean = true,
    val feedbackDurationMillis: Long = 800,
    val clickDelayMillis: Long = 250,
    val clickBindings: ClickBindings = ClickBindings(),
    val leftZoneFraction: Float = 0.30f,
    val rightZoneFraction: Float = 0.30f,
    val alwaysOnTop: Boolean = false,
    val targetFps: Int = 60,
    val adaptiveFps: Boolean = true,
    val chromeMinimized: Boolean = false,
    val closeChromeOnExit: Boolean = false,
    val audioDeviceName: String? = null,
    val followDefaultAudioDevice: Boolean = true,
    val allowSystemAudioFallback: Boolean = false,
    val monitorIndex: Int = 0,
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowWidth: Int = 1100,
    val windowHeight: Int = 680,
)

@Serializable
data class TrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkBase64: String? = null,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val isPlaying: Boolean = false,
)

@Serializable
enum class PlaybackCommand { SEEK_BACK_10, PREVIOUS, TOGGLE_PLAY_PAUSE, NEXT_PRESET, SEEK_FORWARD_10, NEXT, NONE }

@Serializable
data class ClickBindings(
    val leftSingle: PlaybackCommand = PlaybackCommand.SEEK_BACK_10,
    val leftDouble: PlaybackCommand = PlaybackCommand.PREVIOUS,
    val centerSingle: PlaybackCommand = PlaybackCommand.TOGGLE_PLAY_PAUSE,
    val centerDouble: PlaybackCommand = PlaybackCommand.NONE,
    val rightSingle: PlaybackCommand = PlaybackCommand.SEEK_FORWARD_10,
    val rightDouble: PlaybackCommand = PlaybackCommand.NEXT,
)

fun VisualPreset.effectiveLinearDirection(): LinearDirection =
    linearDirection ?: if (linearMirrored) LinearDirection.MIRRORED else LinearDirection.UP

fun defaultVisual() = VisualPreset(
    id = "visual",
    name = "Visual",
    layout = VisualizerLayout.LINEAR,
    reaction = ReactionProfile.BALANCED,
    artworkFit = ArtworkFit.COVER,
)
