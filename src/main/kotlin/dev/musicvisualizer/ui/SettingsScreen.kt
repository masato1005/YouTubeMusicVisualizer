package dev.musicvisualizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.musicvisualizer.model.AppSettings
import dev.musicvisualizer.model.ArtworkFit
import dev.musicvisualizer.model.BackgroundFill
import dev.musicvisualizer.model.BackgroundMode
import dev.musicvisualizer.model.PlaybackCommand
import dev.musicvisualizer.model.LinearDirection
import dev.musicvisualizer.model.TrackInfoVisibility
import dev.musicvisualizer.model.VisualPreset
import dev.musicvisualizer.model.VisualizerLayout
import dev.musicvisualizer.model.VisualizerPrimitive
import dev.musicvisualizer.model.WaveColorMode
import dev.musicvisualizer.model.effectiveLinearDirection
import kotlin.math.roundToInt
import java.awt.GraphicsEnvironment
import javax.sound.sampled.AudioSystem

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onPresetChange: ((VisualPreset) -> VisualPreset) -> Unit,
    onChooseImage: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
    onOpenLoginChrome: () -> Unit,
    onReconnectAfterLogin: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("ビジュアル", "背景", "操作", "一般")
    MaterialTheme {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Visualizer settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("閉じる") }
            }
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, name -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(name) }) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (selectedTab) {
                    0 -> VisualSettings(settings, onPresetChange)
                    1 -> BackgroundSettings(settings, onSettingsChange, onPresetChange, onChooseImage)
                    2 -> InteractionSettings(settings, onSettingsChange)
                    3 -> GeneralSettings(settings, onSettingsChange, onExit, onOpenLoginChrome, onReconnectAfterLogin)
                }
            }
        }
    }
}

@Composable
private fun VisualSettings(
    settings: AppSettings,
    onPresetChange: ((VisualPreset) -> VisualPreset) -> Unit,
) {
    val preset = settings.presets.first()
    EnumChoice("配置", preset.layout, VisualizerLayout.entries) { onPresetChange { preset -> preset.copy(layout = it) } }
    EnumChoice("描画", preset.primitive, VisualizerPrimitive.entries) { onPresetChange { preset -> preset.copy(primitive = it) } }
    EnumChoice("色", preset.colorMode, WaveColorMode.entries) { onPresetChange { preset -> preset.copy(colorMode = it) } }
    if (preset.colorMode != WaveColorMode.FROM_ARTWORK) {
        Text("基本色", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COLOR_CHOICES.forEach { (name, color) ->
                OutlinedButton(onClick = { onPresetChange { it.copy(waveColor = color) } }) { Text(name) }
            }
        }
    }
    if (preset.colorMode == WaveColorMode.GRADIENT) {
        Text("グラデーション第2色", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COLOR_CHOICES.forEach { (name, color) ->
                OutlinedButton(onClick = { onPresetChange { it.copy(secondaryColor = color) } }) { Text(name) }
            }
        }
    }
    LabeledSlider("粒度: ${preset.bandCount}", preset.bandCount.toFloat(), 8f..256f, 31) { value -> onPresetChange { it.copy(bandCount = value.roundToInt()) } }
    LabeledSlider("最低音域: ${formatFrequency(preset.minFrequencyHz)}", preset.minFrequencyHz, 20f..5_000f, 99) { value ->
        onPresetChange { it.copy(minFrequencyHz = value, maxFrequencyHz = maxOf(it.maxFrequencyHz, value + 100f)) }
    }
    LabeledSlider("最高音域: ${formatFrequency(preset.maxFrequencyHz)}", preset.maxFrequencyHz, 2_000f..24_000f, 109) { value ->
        onPresetChange { it.copy(maxFrequencyHz = value, minFrequencyHz = minOf(it.minFrequencyHz, value - 100f)) }
    }
    LabeledSlider("感度: ${"%.2f".format(preset.sensitivity)}", preset.sensitivity, 0.01f..8f) { value -> onPresetChange { it.copy(sensitivity = value) } }
    LabeledSlider("滑らかさ: ${(preset.smoothing * 100).roundToInt()}%", preset.smoothing, 0f..0.98f) { value -> onPresetChange { it.copy(smoothing = value) } }
    LabeledSlider("線幅: ${"%.1f".format(preset.lineWidth)}", preset.lineWidth, 0.5f..18f) { value -> onPresetChange { it.copy(lineWidth = value) } }
    LabeledSlider("透明度: ${(preset.waveAlpha * 100).roundToInt()}%", preset.waveAlpha, 0.05f..1f) { value -> onPresetChange { it.copy(waveAlpha = value) } }
    EnumChoice("直線方向", preset.effectiveLinearDirection(), LinearDirection.entries) { direction ->
        onPresetChange { preset -> preset.copy(linearDirection = direction, linearMirrored = direction == LinearDirection.MIRRORED) }
    }
    CheckRow("円の中央にジャケットを表示", preset.showArtworkInCircle) { onPresetChange { preset -> preset.copy(showArtworkInCircle = it) } }
}

private fun formatFrequency(value: Float): String =
    if (value >= 1_000f) "${"%.1f".format(value / 1_000f)} kHz" else "${value.roundToInt()} Hz"

@Composable
private fun BackgroundSettings(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onPresetChange: ((VisualPreset) -> VisualPreset) -> Unit,
    onChooseImage: () -> Unit,
) {
    val preset = settings.presets.first { it.id == settings.selectedPresetId }
    EnumChoice("背景ソース", settings.backgroundMode, BackgroundMode.entries) { value -> onSettingsChange { it.copy(backgroundMode = value) } }
    Button(onClick = onChooseImage) { Text("画像を選択") }
    settings.fixedImagePath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    EnumChoice("画像外側", settings.backgroundFill, BackgroundFill.entries) { value -> onSettingsChange { it.copy(backgroundFill = value) } }
    if (settings.backgroundFill == BackgroundFill.SOLID) {
        Text("余白色", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BACKGROUND_COLORS.forEach { (name, color) ->
                OutlinedButton(onClick = { onSettingsChange { it.copy(backgroundColor = color) } }) { Text(name) }
            }
        }
    }
    EnumChoice("画像配置", preset.artworkFit, ArtworkFit.entries) { value -> onPresetChange { it.copy(artworkFit = value) } }
    LabeledSlider("画像サイズ: ${(preset.artworkScale * 100).roundToInt()}%", preset.artworkScale, 0.08f..1.5f) { value -> onPresetChange { it.copy(artworkScale = value) } }
    LabeledSlider("横位置: ${(preset.artworkX * 100).roundToInt()}%", preset.artworkX, 0f..1f) { value -> onPresetChange { it.copy(artworkX = value) } }
    LabeledSlider("縦位置: ${(preset.artworkY * 100).roundToInt()}%", preset.artworkY, 0f..1f) { value -> onPresetChange { it.copy(artworkY = value) } }
    LabeledSlider("暗さ: ${(settings.backgroundDim * 100).roundToInt()}%", settings.backgroundDim, 0f..0.9f) { value -> onSettingsChange { it.copy(backgroundDim = value) } }
    LabeledSlider("ぼかし: ${settings.backgroundBlur.roundToInt()}", settings.backgroundBlur, 0f..50f) { value -> onSettingsChange { it.copy(backgroundBlur = value) } }
}

@Composable
private fun InteractionSettings(settings: AppSettings, onSettingsChange: ((AppSettings) -> AppSettings) -> Unit) {
    CommandChoiceRow("左・1クリック", settings.clickBindings.leftSingle) { value -> onSettingsChange { it.copy(clickBindings = it.clickBindings.copy(leftSingle = value)) } }
    CommandChoiceRow("左・2クリック", settings.clickBindings.leftDouble) { value -> onSettingsChange { it.copy(clickBindings = it.clickBindings.copy(leftDouble = value)) } }
    CommandChoiceRow("中央・1クリック", settings.clickBindings.centerSingle) { value -> onSettingsChange { it.copy(clickBindings = it.clickBindings.copy(centerSingle = value)) } }
    CommandChoiceRow("中央・2クリック", settings.clickBindings.centerDouble) { value -> onSettingsChange { it.copy(clickBindings = it.clickBindings.copy(centerDouble = value)) } }
    CommandChoiceRow("右・1クリック", settings.clickBindings.rightSingle) { value -> onSettingsChange { it.copy(clickBindings = it.clickBindings.copy(rightSingle = value)) } }
    CommandChoiceRow("右・2クリック", settings.clickBindings.rightDouble) { value -> onSettingsChange { it.copy(clickBindings = it.clickBindings.copy(rightDouble = value)) } }
    LabeledSlider("左領域: ${(settings.leftZoneFraction * 100).roundToInt()}%", settings.leftZoneFraction, 0.15f..0.4f) { value -> onSettingsChange { it.copy(leftZoneFraction = value) } }
    LabeledSlider("右領域: ${(settings.rightZoneFraction * 100).roundToInt()}%", settings.rightZoneFraction, 0.15f..0.4f) { value -> onSettingsChange { it.copy(rightZoneFraction = value) } }
    LabeledSlider("クリック判定: ${settings.clickDelayMillis}ms", settings.clickDelayMillis.toFloat(), 150f..600f) { value -> onSettingsChange { it.copy(clickDelayMillis = value.toLong()) } }
    CheckRow("操作アイコンを表示", settings.feedbackVisible) { value -> onSettingsChange { it.copy(feedbackVisible = value) } }
    LabeledSlider("表示時間: ${settings.feedbackDurationMillis}ms", settings.feedbackDurationMillis.toFloat(), 200f..4_000f) { value -> onSettingsChange { it.copy(feedbackDurationMillis = value.toLong()) } }
    Text("F11: 全画面切替　Alt+ドラッグ: 移動　端をドラッグ: サイズ変更", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun GeneralSettings(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onExit: () -> Unit,
    onOpenLoginChrome: () -> Unit,
    onReconnectAfterLogin: () -> Unit,
) {
    CheckRow("常に手前へ表示", settings.alwaysOnTop) { value -> onSettingsChange { it.copy(alwaysOnTop = value) } }
    CheckRow("Chromeを最小化して起動", settings.chromeMinimized) { value -> onSettingsChange { it.copy(chromeMinimized = value) } }
    CheckRow("終了時に専用Chromeも閉じる", settings.closeChromeOnExit) { value -> onSettingsChange { it.copy(closeChromeOnExit = value) } }
    CheckRow("既定の音声出力へ追従", settings.followDefaultAudioDevice) { value -> onSettingsChange { it.copy(followDefaultAudioDevice = value) } }
    if (!settings.followDefaultAudioDevice) {
        Text("フォールバック音声入力", style = MaterialTheme.typography.titleSmall)
        AudioSystem.getMixerInfo().forEach { mixer ->
            RadioRow(mixer.name, settings.audioDeviceName == mixer.name) {
                onSettingsChange { it.copy(audioDeviceName = mixer.name) }
            }
        }
    }
    CheckRow("負荷に応じて30fpsへ下げる", settings.adaptiveFps) { value -> onSettingsChange { it.copy(adaptiveFps = value) } }
    if (!settings.adaptiveFps) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.targetFps == 30) Button(onClick = { }) { Text("30 fps") }
            else OutlinedButton(onClick = { onSettingsChange { it.copy(targetFps = 30) } }) { Text("30 fps") }
            if (settings.targetFps == 60) Button(onClick = { }) { Text("60 fps") }
            else OutlinedButton(onClick = { onSettingsChange { it.copy(targetFps = 60) } }) { Text("60 fps") }
        }
    }
    EnumChoice("曲情報", settings.trackInfoVisibility, TrackInfoVisibility.entries) { value -> onSettingsChange { it.copy(trackInfoVisibility = value) } }
    Text("表示先モニター", style = MaterialTheme.typography.titleSmall)
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEachIndexed { index, device ->
        val bounds = device.defaultConfiguration.bounds
        RadioRow("Monitor ${index + 1} (${bounds.width}×${bounds.height})", settings.monitorIndex == index) {
            onSettingsChange { it.copy(monitorIndex = index) }
        }
    }
    Text("通常設定とChromeプロファイルはWindowsのユーザーデータ領域へ保存されます。", style = MaterialTheme.typography.bodySmall)
    Text("Googleログイン", style = MaterialTheme.typography.titleSmall)
    Text("Googleが制御中ブラウザからのログインを拒否するため、ログイン時だけリモート制御を無効にします。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenLoginChrome) { Text("ログイン用Chrome") }
        Button(onClick = onReconnectAfterLogin) { Text("ログイン完了・再接続") }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onExit) { Text("アプリを終了") }
}

@Composable
private fun CommandChoiceRow(label: String, value: PlaybackCommand, onChange: (PlaybackCommand) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            val values = PlaybackCommand.entries.filter { it != PlaybackCommand.NEXT_PRESET }
            onChange(values[(values.indexOf(value) + 1) % values.size])
        }) { Text(value.displayName()) }
    }
}

@Composable
private fun <T> EnumChoice(label: String, selected: T, values: List<T>, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value ->
                if (value == selected) Button(onClick = { onSelect(value) }) { Text(value.toString()) }
                else OutlinedButton(onClick = { onSelect(value) }) { Text(value.toString()) }
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Text(label)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

private fun PlaybackCommand.displayName(): String = when (this) {
    PlaybackCommand.SEEK_BACK_10 -> "10秒戻る"
    PlaybackCommand.PREVIOUS -> "前の曲"
    PlaybackCommand.TOGGLE_PLAY_PAUSE -> "再生／停止"
    PlaybackCommand.NEXT_PRESET, PlaybackCommand.NONE -> "操作なし"
    PlaybackCommand.SEEK_FORWARD_10 -> "10秒進む"
    PlaybackCommand.NEXT -> "次の曲"
}

private val COLOR_CHOICES = listOf(
    "白" to 0xFFFFFFFF,
    "水色" to 0xFF6AD5FF,
    "桃" to 0xFFFF69C8,
    "金" to 0xFFFFC857,
    "緑" to 0xFF70E1A1,
)

private val BACKGROUND_COLORS = listOf(
    "黒" to 0xFF000000,
    "濃紺" to 0xFF080A0F,
    "灰" to 0xFF24262B,
    "白" to 0xFFFFFFFF,
)
