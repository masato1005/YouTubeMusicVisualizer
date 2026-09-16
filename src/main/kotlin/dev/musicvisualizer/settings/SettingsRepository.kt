package dev.musicvisualizer.settings

import dev.musicvisualizer.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SettingsRepository(
    private val settingsPath: Path = defaultSettingsPath(),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(settingsPath)) return@withContext AppSettings()
        runCatching { json.decodeFromString<AppSettings>(Files.readString(settingsPath)) }
            .getOrElse { AppSettings() }
    }

    suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        Files.createDirectories(settingsPath.parent)
        val temporary = settingsPath.resolveSibling("${settingsPath.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(settings))
        runCatching {
            Files.move(
                temporary,
                settingsPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, settingsPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun defaultDataDirectory(): Path {
            val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            return if (local != null) Path.of(local, "YouTubeMusicVisualizer")
            else Path.of(System.getProperty("user.home"), ".youtube-music-visualizer")
        }

        fun defaultSettingsPath(): Path = defaultDataDirectory().resolve("settings.json")
    }
}
