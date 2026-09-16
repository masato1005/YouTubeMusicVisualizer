package dev.musicvisualizer.media

import dev.musicvisualizer.model.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files

@Serializable
private data class BridgeRequest(val command: String, val value: Long? = null)

@Serializable
private data class BridgeResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkBase64: String? = null,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val isPlaying: Boolean = false,
)

class WindowsMediaBridge(private val scope: CoroutineScope) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableTrack = MutableStateFlow(TrackInfo())
    val track: StateFlow<TrackInfo> = mutableTrack
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var pollingJob: Job? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        runCatching {
            val script = javaClass.getResourceAsStream("/windows/media-bridge.ps1")
                ?: error("media-bridge.ps1 が見つかりません")
            val temporary = Files.createTempFile("ytmviz-media-bridge", ".ps1")
            script.use { Files.copy(it, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            temporary.toFile().deleteOnExit()
            val child = ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Mta",
                "-ExecutionPolicy", "Bypass", "-File", temporary.toAbsolutePath().toString(),
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            process = child
            writer = child.outputStream.bufferedWriter(Charsets.UTF_8)
            reader = child.inputStream.bufferedReader(Charsets.UTF_8)
        }.onFailure { mutableError.value = it.message }

        pollingJob = scope.launch {
            while (isActive) {
                val state = request("state")
                if (state?.ok == true) {
                    mutableError.value = null
                    mutableTrack.value = TrackInfo(
                        title = state.title,
                        artist = state.artist,
                        album = state.album,
                        artworkBase64 = state.artworkBase64 ?: mutableTrack.value.artworkBase64,
                        positionMillis = state.positionMillis,
                        durationMillis = state.durationMillis,
                        isPlaying = state.isPlaying,
                    )
                } else if (state?.error != null) {
                    mutableError.value = state.error
                }
                delay(1_000)
            }
        }
    }

    suspend fun togglePlayPause(): Boolean = request("toggle")?.ok == true
    suspend fun next(): Boolean = request("next")?.ok == true
    suspend fun previous(): Boolean = request("previous")?.ok == true
    suspend fun seekBy(deltaMillis: Long): Boolean = request("seek", deltaMillis)?.ok == true

    private suspend fun request(command: String, value: Long? = null): BridgeResponse? = withContext(Dispatchers.IO) {
        synchronized(this@WindowsMediaBridge) {
            if (process?.isAlive != true || writer == null || reader == null) return@synchronized null
            runCatching {
                writer!!.write(json.encodeToString(BridgeRequest(command, value)))
                writer!!.newLine()
                writer!!.flush()
                val line = reader!!.readLine() ?: return@runCatching null
                json.decodeFromString<BridgeResponse>(line)
            }.onFailure { mutableError.value = it.message }.getOrNull()
        }
    }

    override fun close() {
        pollingJob?.cancel()
        runCatching { writer?.close() }
        process?.destroy()
    }
}
