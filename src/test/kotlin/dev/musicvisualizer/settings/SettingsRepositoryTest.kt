package dev.musicvisualizer.settings

import dev.musicvisualizer.model.AppSettings
import dev.musicvisualizer.model.BackgroundMode
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsRepositoryTest {
    @Test
    fun settingsRoundTrip() = runTest {
        val directory = Files.createTempDirectory("ytmviz-settings")
        val repository = SettingsRepository(directory.resolve("settings.json"))
        val expected = AppSettings(backgroundMode = BackgroundMode.FIXED_IMAGE, fixedImagePath = "cover.png")
        repository.save(expected)
        assertEquals(expected, repository.load())
    }

    @Test
    fun oldPresetFilesReceiveNewFrequencyDefaults() = runTest {
        val directory = Files.createTempDirectory("ytmviz-old-settings")
        val path = directory.resolve("settings.json")
        Files.writeString(
            path,
            """{"schemaVersion":1,"selectedPresetId":"old","presets":[{"id":"old","name":"Old","layout":"LINEAR","reaction":"BALANCED"}],"presetCycle":["old"]}""",
        )

        val preset = SettingsRepository(path).load().presets.single()
        assertEquals(35f, preset.minFrequencyHz)
        assertEquals(22_000f, preset.maxFrequencyHz)
        assertNull(preset.linearDirection)
    }
}
