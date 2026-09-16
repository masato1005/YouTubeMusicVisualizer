package dev.musicvisualizer.browser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChromeControllerTest {
    @Test
    fun loginModeNeverEnablesRemoteDebugging() {
        val arguments = buildChromeArguments(
            executable = Path.of("chrome.exe"),
            profileDirectory = Path.of("dedicated-profile"),
            minimized = false,
            mode = ChromeLaunchMode.LOGIN,
        )

        assertFalse(arguments.any { it.startsWith("--remote-debugging-") })
        assertFalse(arguments.any { it.startsWith("--app=") })
        assertTrue(arguments.any { it.startsWith("--user-data-dir=") })
        assertTrue(arguments.contains("https://music.youtube.com/"))
        assertTrue(arguments.contains("--new-window"))
    }

    @Test
    fun controlledModeEnablesRemoteDebugging() {
        val arguments = buildChromeArguments(
            executable = Path.of("chrome.exe"),
            profileDirectory = Path.of("dedicated-profile"),
            minimized = true,
            mode = ChromeLaunchMode.CONTROLLED,
        )

        assertTrue(arguments.contains("--remote-debugging-port=0"))
        assertTrue(arguments.contains("--start-minimized"))
    }

    @Test
    fun loginCompletionMarkerRoundTrips() {
        val profile = Files.createTempDirectory("ytmviz-chrome-profile")
        val controller = ChromeController(profile)

        assertFalse(controller.isLoginSetupComplete())
        controller.markLoginSetupComplete()
        assertTrue(controller.isLoginSetupComplete())
    }
}
