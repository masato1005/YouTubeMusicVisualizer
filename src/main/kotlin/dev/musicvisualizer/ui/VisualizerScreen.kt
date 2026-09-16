@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.musicvisualizer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.musicvisualizer.app.ActionFeedback
import dev.musicvisualizer.audio.AudioCaptureStatus
import dev.musicvisualizer.model.AppSettings
import dev.musicvisualizer.model.ArtworkFit
import dev.musicvisualizer.model.BackgroundFill
import dev.musicvisualizer.model.BackgroundMode
import dev.musicvisualizer.model.PlaybackCommand
import dev.musicvisualizer.model.LinearDirection
import dev.musicvisualizer.model.TrackInfo
import dev.musicvisualizer.model.TrackInfoVisibility
import dev.musicvisualizer.model.VisualPreset
import dev.musicvisualizer.model.VisualizerLayout
import dev.musicvisualizer.model.VisualizerPrimitive
import dev.musicvisualizer.model.WaveColorMode
import dev.musicvisualizer.model.effectiveLinearDirection
import dev.musicvisualizer.interaction.isClickGesture
import dev.musicvisualizer.interaction.routeClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Files
import java.nio.file.Path as NioPath
import java.util.Base64
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun VisualizerScreen(
    settings: AppSettings,
    spectrum: FloatArray,
    track: TrackInfo,
    audioStatus: AudioCaptureStatus,
    feedback: ActionFeedback?,
    interactionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCommand: (PlaybackCommand) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChrome: () -> Unit,
) {
    val preset = settings.presets.firstOrNull { it.id == settings.selectedPresetId } ?: settings.presets.first()
    val artwork by loadArtwork(settings, track)
    val clickScope = rememberCoroutineScope()
    var pendingClick by remember { mutableStateOf<Job?>(null) }
    var pressedAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var secondaryPress by remember { mutableStateOf(false) }
    var altMoveGesture by remember { mutableStateOf(false) }
    var clickWidth by remember { mutableIntStateOf(1) }
    var interactionNonce by remember { mutableLongStateOf(0L) }
    MaterialTheme {
        val interactionModifier = if (interactionEnabled) {
            Modifier
                .onPointerEvent(PointerEventType.Press) { event ->
                    secondaryPress = event.buttons.isSecondaryPressed
                    if (secondaryPress) onOpenSettings()
                    else if (event.keyboardModifiers.isAltPressed) {
                        altMoveGesture = true
                        pressedAt = null
                    } else {
                        altMoveGesture = false
                        pressedAt = event.changes.firstOrNull()?.position
                    }
                }
                .onPointerEvent(PointerEventType.Release) { event ->
                    if (secondaryPress) { secondaryPress = false; return@onPointerEvent }
                    if (altMoveGesture) {
                        altMoveGesture = false
                        pressedAt = null
                        return@onPointerEvent
                    }
                    val releasedAt = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val origin = pressedAt ?: return@onPointerEvent
                    pressedAt = null
                    if (!isClickGesture((releasedAt - origin).getDistance(), altPressedAtPress = false)) return@onPointerEvent
                    val fraction = releasedAt.x / clickWidth
                    val existing = pendingClick
                    if (existing?.isActive == true) {
                        existing.cancel()
                        pendingClick = null
                        interactionNonce++
                        onCommand(routeClick(fraction, true, settings.leftZoneFraction, settings.rightZoneFraction, settings.clickBindings))
                    } else {
                        pendingClick = clickScope.launch {
                            delay(settings.clickDelayMillis)
                            interactionNonce++
                            onCommand(routeClick(fraction, false, settings.leftZoneFraction, settings.rightZoneFraction, settings.clickBindings))
                            pendingClick = null
                        }
                    }
                }
        } else Modifier
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { clickWidth = it.width.coerceAtLeast(1) }
                .then(interactionModifier),
        ) {
            Crossfade(targetState = artwork, animationSpec = tween(700), label = "artwork") { visibleArtwork ->
                BackgroundLayer(settings, preset, visibleArtwork, maxWidth, maxHeight)
            }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val colors = waveColors(preset, artwork)
                when (preset.layout) {
                    VisualizerLayout.LINEAR -> drawLinearSpectrum(spectrum, preset, colors)
                    VisualizerLayout.CIRCULAR -> drawCircularSpectrum(spectrum, preset, colors)
                }
            }
            if (preset.layout == VisualizerLayout.CIRCULAR && preset.showArtworkInCircle && artwork != null) {
                val side = min(maxWidth.value, maxHeight.value).dp * preset.circleRadius * 1.56f
                Image(
                    bitmap = artwork!!.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.Center).size(side).clip(CircleShape),
                )
            }

            TrackInfoOverlay(settings, track, interactionNonce)
            AnimatedVisibility(
                visible = feedback != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                feedback?.let {
                    Surface(color = Color.Black.copy(alpha = 0.52f), shape = RoundedCornerShape(18.dp)) {
                        Text(it.glyph, color = Color.White, fontSize = 30.sp, modifier = Modifier.padding(18.dp))
                    }
                }
            }
            if (audioStatus is AudioCaptureStatus.Failed) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("YouTube Musicを待機中", color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Medium)
                    Text(audioStatus.message, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, textAlign = TextAlign.Center)
                    Button(onClick = onOpenChrome) { Text("再接続") }
                }
            }
        }
    }
}

@Composable
private fun BackgroundLayer(
    settings: AppSettings,
    preset: VisualPreset,
    artwork: ArtworkAsset?,
    maxWidth: Dp,
    maxHeight: Dp,
) {
    Box(Modifier.fillMaxSize()) {
        when (settings.backgroundFill) {
            BackgroundFill.TRANSPARENT -> Unit
            BackgroundFill.SOLID -> Box(Modifier.fillMaxSize().background(argbColor(settings.backgroundColor)))
            BackgroundFill.BLURRED_ARTWORK -> if (artwork != null) {
                Image(
                    artwork.image,
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(settings.backgroundBlur.dp),
                )
            } else Box(Modifier.fillMaxSize().background(argbColor(settings.backgroundColor)))
        }
        if (artwork != null && !(preset.layout == VisualizerLayout.CIRCULAR && preset.showArtworkInCircle)) {
            when (preset.artworkFit) {
                ArtworkFit.COVER -> Image(artwork.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                ArtworkFit.CONTAIN -> Image(artwork.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                ArtworkFit.FREE -> {
                    val side = min(maxWidth.value, maxHeight.value).dp * preset.artworkScale
                    Image(
                        artwork.image,
                        null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(side)
                            .offset {
                                IntOffset(
                                    ((maxWidth - side).toPx() * preset.artworkX).roundToInt(),
                                    ((maxHeight - side).toPx() * preset.artworkY).roundToInt(),
                                )
                            },
                    )
                }
            }
        }
        if (settings.backgroundDim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.backgroundDim.coerceIn(0f, 0.9f))))
        }
    }
}

@Composable
private fun TrackInfoOverlay(settings: AppSettings, track: TrackInfo, interactionNonce: Long) {
    var changedVisible by remember { mutableStateOf(false) }
    var clickVisible by remember { mutableStateOf(false) }
    LaunchedEffect(track.title, settings.trackInfoVisibility) {
        if (settings.trackInfoVisibility == TrackInfoVisibility.ON_TRACK_CHANGE && track.title.isNotBlank()) {
            changedVisible = true
            kotlinx.coroutines.delay(3_500)
            changedVisible = false
        }
    }
    LaunchedEffect(interactionNonce, settings.trackInfoVisibility) {
        if (settings.trackInfoVisibility == TrackInfoVisibility.ON_CLICK && interactionNonce > 0L && track.title.isNotBlank()) {
            clickVisible = true
            kotlinx.coroutines.delay(3_500)
            clickVisible = false
        }
    }
    val visible = when (settings.trackInfoVisibility) {
        TrackInfoVisibility.ALWAYS -> track.title.isNotBlank()
        TrackInfoVisibility.ON_TRACK_CHANGE -> changedVisible
        TrackInfoVisibility.ON_CLICK -> clickVisible && track.title.isNotBlank()
        TrackInfoVisibility.NEVER -> false
    }
    AnimatedVisibility(visible = visible, modifier = Modifier.padding(24.dp)) {
        Column {
            Text(track.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (track.artist.isNotBlank()) Text(track.artist, color = Color.White.copy(alpha = 0.66f), fontSize = 13.sp)
        }
    }
}

private data class ArtworkAsset(val image: ImageBitmap, val palette: List<Color>)

@Composable
private fun loadArtwork(settings: AppSettings, track: TrackInfo) = produceState<ArtworkAsset?>(null, settings.backgroundMode, settings.fixedImagePath, track.artworkBase64) {
    value = withContext(Dispatchers.IO) {
        val bytes = when (settings.backgroundMode) {
            BackgroundMode.FIXED_IMAGE -> settings.fixedImagePath?.let(NioPath::of)?.takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
            BackgroundMode.AUTOMATIC_ARTWORK -> track.artworkBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        } ?: return@withContext null
        runCatching {
            ArtworkAsset(
                image = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap(),
                palette = extractPalette(bytes),
            )
        }.getOrNull()
    }
}

private fun extractPalette(bytes: ByteArray): List<Color> {
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return listOf(Color.White, Color(0xFF6AD5FF))
    val bins = DoubleArray(12)
    val saturation = DoubleArray(12)
    val brightness = DoubleArray(12)
    val step = (min(image.width, image.height) / 48).coerceAtLeast(1)
    for (y in 0 until image.height step step) for (x in 0 until image.width step step) {
        val rgb = image.getRGB(x, y)
        val hsb = java.awt.Color.RGBtoHSB(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF, null)
        if (hsb[2] < 0.12f) continue
        val bin = (hsb[0] * bins.size).toInt().coerceIn(bins.indices)
        val weight = (0.25 + hsb[1]) * (0.4 + hsb[2])
        bins[bin] += weight
        saturation[bin] += hsb[1] * weight
        brightness[bin] += hsb[2] * weight
    }
    val selected = bins.indices.sortedByDescending { bins[it] }.take(2).filter { bins[it] > 0 }
    if (selected.isEmpty()) return listOf(Color.White, Color(0xFF6AD5FF))
    val colors = selected.map { bin ->
        val sat = (saturation[bin] / bins[bin]).toFloat().coerceAtLeast(0.42f)
        val bright = (brightness[bin] / bins[bin]).toFloat().coerceAtLeast(0.62f)
        val rgb = java.awt.Color.HSBtoRGB((bin + 0.5f) / bins.size, sat, bright)
        Color(rgb)
    }
    return if (colors.size == 1) listOf(colors.first(), Color.White) else colors
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinearSpectrum(
    spectrum: FloatArray,
    preset: VisualPreset,
    colors: List<Color>,
) {
    if (spectrum.isEmpty()) return
    val direction = preset.effectiveLinearDirection()
    val baseline = size.height * linearBaselineFraction(direction, preset.linearY)
    val maximum = size.height * if (direction == LinearDirection.MIRRORED) 0.28f else 0.54f
    val brush = Brush.horizontalGradient(colors)
    val step = size.width / spectrum.size
    when (preset.primitive) {
        VisualizerPrimitive.BARS -> spectrum.forEachIndexed { index, value ->
            val x = step * (index + 0.5f)
            val height = value * maximum
            val startY = if (direction == LinearDirection.DOWN) baseline else baseline - height
            val endY = if (direction == LinearDirection.UP) baseline else baseline + height
            drawLine(brush, androidx.compose.ui.geometry.Offset(x, startY), androidx.compose.ui.geometry.Offset(x, endY), strokeWidth = (step * 0.62f).coerceAtLeast(1f), cap = StrokeCap.Round, alpha = preset.waveAlpha)
        }
        VisualizerPrimitive.SMOOTH_LINE -> {
            val topPoints = spectrum.mapIndexed { index, value ->
                val x = step * (index + 0.5f)
                val height = value * maximum
                androidx.compose.ui.geometry.Offset(x, baseline - height)
            }
            val bottomPoints = topPoints.map { androidx.compose.ui.geometry.Offset(it.x, baseline + (baseline - it.y)) }
            val top = smoothOpenPath(topPoints)
            val bottom = smoothOpenPath(bottomPoints)
            if (direction != LinearDirection.DOWN) drawPath(top, brush, alpha = preset.waveAlpha, style = Stroke(preset.lineWidth, cap = StrokeCap.Round))
            if (direction != LinearDirection.UP) drawPath(bottom, brush, alpha = preset.waveAlpha, style = Stroke(preset.lineWidth, cap = StrokeCap.Round))
        }
    }
}

internal fun linearBaselineFraction(direction: LinearDirection, configured: Float): Float = when (direction) {
    LinearDirection.UP -> 0.96f
    LinearDirection.DOWN -> 0.04f
    LinearDirection.MIRRORED -> configured
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCircularSpectrum(
    spectrum: FloatArray,
    preset: VisualPreset,
    colors: List<Color>,
) {
    if (spectrum.isEmpty()) return
    val center = this.center
    val radius = min(size.width, size.height) * preset.circleRadius
    val maximum = min(size.width, size.height) * 0.18f
    val brush = Brush.sweepGradient(colors, center)
    val points = ArrayList<androidx.compose.ui.geometry.Offset>(spectrum.size)
    spectrum.forEachIndexed { index, value ->
        val angle = -PI / 2.0 + (2.0 * PI * index / spectrum.size)
        val outer = radius + value * maximum
        val innerPoint = androidx.compose.ui.geometry.Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
        val outerPoint = androidx.compose.ui.geometry.Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer)
        if (preset.primitive == VisualizerPrimitive.BARS) {
            drawLine(brush, innerPoint, outerPoint, strokeWidth = preset.lineWidth.coerceAtLeast(2f), cap = StrokeCap.Round, alpha = preset.waveAlpha)
        } else {
            points += outerPoint
        }
    }
    if (preset.primitive == VisualizerPrimitive.SMOOTH_LINE) {
        val path = smoothClosedPath(points)
        drawPath(path, brush, alpha = preset.waveAlpha, style = Stroke(preset.lineWidth, cap = StrokeCap.Round))
    }
}

private fun smoothOpenPath(points: List<androidx.compose.ui.geometry.Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    for (index in 1 until points.lastIndex) {
        val control = points[index]
        val next = points[index + 1]
        quadraticTo(control.x, control.y, (control.x + next.x) / 2f, (control.y + next.y) / 2f)
    }
    if (points.size > 1) lineTo(points.last().x, points.last().y)
}

private fun smoothClosedPath(points: List<androidx.compose.ui.geometry.Offset>): Path = Path().apply {
    if (points.size < 2) return@apply
    val first = points.first()
    val last = points.last()
    moveTo((last.x + first.x) / 2f, (last.y + first.y) / 2f)
    points.forEachIndexed { index, control ->
        val next = points[(index + 1) % points.size]
        quadraticTo(control.x, control.y, (control.x + next.x) / 2f, (control.y + next.y) / 2f)
    }
    close()
}

private fun waveColors(preset: VisualPreset, artwork: ArtworkAsset?): List<Color> = when (preset.colorMode) {
    WaveColorMode.SOLID -> listOf(argbColor(preset.waveColor), argbColor(preset.waveColor))
    WaveColorMode.GRADIENT -> listOf(argbColor(preset.waveColor), argbColor(preset.secondaryColor), argbColor(preset.waveColor))
    WaveColorMode.FROM_ARTWORK -> {
        if (artwork != null) artwork.palette + artwork.palette.first()
        else listOf(Color.White, Color(0xFF6AD5FF), Color.White)
    }
}

private fun argbColor(value: Long): Color = Color(value.toInt())

@Composable
fun AudioFallbackDialog(onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("音声を取得できません") },
        text = { Text("専用Chromeだけの取得に失敗しました。PC全体の音声入力へ切り替えますか？") },
        confirmButton = { Button(onClick = onAccept) { Text("切り替える") } },
        dismissButton = { Button(onClick = onReject) { Text("キャンセル") } },
    )
}

@Composable
fun LoginSetupScreen(onOpenLoginChrome: () -> Unit, onComplete: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Googleログインの準備", style = MaterialTheme.typography.headlineSmall)
                Text("ChromeでGoogleへログインし、YouTube Musicが開いたらこのウィンドウへ戻ってください。")
                Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
                    Text("ログイン完了・再接続")
                }
                Button(onClick = onOpenLoginChrome, modifier = Modifier.fillMaxWidth()) {
                    Text("Chromeを開き直す")
                }
            }
        }
    }
}
