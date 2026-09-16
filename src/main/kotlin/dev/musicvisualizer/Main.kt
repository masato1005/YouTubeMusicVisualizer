package dev.musicvisualizer

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.musicvisualizer.app.AppController
import dev.musicvisualizer.ui.AudioFallbackDialog
import dev.musicvisualizer.ui.LoginSetupScreen
import dev.musicvisualizer.ui.SettingsScreen
import dev.musicvisualizer.ui.VisualizerScreen
import dev.musicvisualizer.ui.WindowBehavior
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Path

internal data class WindowPolicy(
    val showInteractiveLoginWindow: Boolean,
    val enableVisualizerInput: Boolean,
)

internal fun resolveWindowPolicy(loginSetupVisible: Boolean) = WindowPolicy(
    showInteractiveLoginWindow = loginSetupVisible,
    enableVisualizerInput = !loginSetupVisible,
)

fun main() = application {
    val controller = remember { AppController() }
    val settings by controller.settings.collectAsState()
    val spectrum by controller.spectrum.collectAsState()
    val track by controller.track.collectAsState()
    val audioStatus by controller.audioStatus.collectAsState()
    val feedback by controller.feedback.collectAsState()
    val settingsVisible by controller.settingsVisible.collectAsState()
    val fallbackPrompt by controller.fallbackPrompt.collectAsState()
    val loginSetupVisible by controller.loginSetupVisible.collectAsState()
    val windowPolicy = resolveWindowPolicy(loginSetupVisible)
    var placement by remember { mutableStateOf(WindowPlacement.Floating) }
    var mainWindow by remember { mutableStateOf<ComposeWindow?>(null) }
    val quit = {
        mainWindow?.bounds?.let { bounds ->
            controller.updateSettings { it.copy(windowX = bounds.x, windowY = bounds.y, windowWidth = bounds.width, windowHeight = bounds.height) }
        }
        controller.close()
        exitApplication()
    }
    val windowState = rememberWindowState(
        width = settings.windowWidth.dp,
        height = settings.windowHeight.dp,
        position = if (settings.windowX != null && settings.windowY != null) {
            WindowPosition.Absolute(settings.windowX!!.dp, settings.windowY!!.dp)
        } else WindowPosition.PlatformDefault,
    )
    windowState.placement = placement

    LaunchedEffect(Unit) { controller.start() }

    Window(
        onCloseRequest = quit,
        state = windowState,
        undecorated = true,
        transparent = true,
        resizable = true,
        alwaysOnTop = settings.alwaysOnTop,
        title = "YouTube Music Visualizer",
    ) {
        LaunchedEffect(settings.windowX, settings.windowY, settings.windowWidth, settings.windowHeight) {
            window.setBounds(settings.windowX ?: window.x, settings.windowY ?: window.y, settings.windowWidth, settings.windowHeight)
        }
        LaunchedEffect(settings.monitorIndex) {
            val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            screens.getOrNull(settings.monitorIndex)?.defaultConfiguration?.bounds?.let { bounds ->
                if (!bounds.intersects(window.bounds)) window.setLocation(bounds.x + 80, bounds.y + 80)
            }
        }
        DisposableEffect(window) {
            mainWindow = window
            val behavior = WindowBehavior.install(window, controller::setFixedBackground)
            onDispose {
                behavior.close()
                if (mainWindow == window) mainWindow = null
            }
        }

        val keyModifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                placement = if (placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                true
            } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && placement == WindowPlacement.Fullscreen) {
                placement = WindowPlacement.Floating
                true
            } else false
        }
        VisualizerScreen(
            settings = settings,
            spectrum = spectrum,
            track = track,
            audioStatus = audioStatus,
            feedback = feedback,
            interactionEnabled = windowPolicy.enableVisualizerInput,
            modifier = keyModifier,
            onCommand = controller::perform,
            onOpenSettings = controller::showSettings,
            onOpenChrome = { controller.retryAudio() },
        )

        if (fallbackPrompt) {
            AudioFallbackDialog(
                onAccept = controller::acceptSystemAudioFallback,
                onReject = controller::rejectSystemAudioFallback,
            )
        }
    }

    if (windowPolicy.showInteractiveLoginWindow) {
        Window(
            onCloseRequest = {},
            title = "YouTube Music Visualizer - Googleログイン",
            state = rememberWindowState(width = 540.dp, height = 300.dp),
            resizable = false,
            alwaysOnTop = true,
        ) {
            LoginSetupScreen(
                onOpenLoginChrome = controller::openChromeForLogin,
                onComplete = controller::completeLoginAndReconnect,
            )
        }
    }

    if (settingsVisible) {
        Window(
            onCloseRequest = controller::hideSettings,
            title = "Visualizer settings",
            state = rememberWindowState(width = 720.dp, height = 760.dp),
            resizable = true,
        ) {
            SettingsScreen(
                settings = settings,
                onSettingsChange = controller::updateSettings,
                onPresetChange = controller::updateSelectedPreset,
                onChooseImage = { chooseImage(window)?.let(controller::setFixedBackground) },
                onClose = controller::hideSettings,
                onExit = quit,
                onOpenLoginChrome = controller::openChromeForLogin,
                onReconnectAfterLogin = controller::completeLoginAndReconnect,
            )
        }
    }
}

private fun chooseImage(owner: ComposeWindow): Path? {
    val dialog = FileDialog(owner as Frame, "背景画像を選択", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif") }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Path.of(dialog.directory, file)
}
