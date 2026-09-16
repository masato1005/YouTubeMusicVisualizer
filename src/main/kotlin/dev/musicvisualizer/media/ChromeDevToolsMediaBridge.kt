package dev.musicvisualizer.media

import dev.musicvisualizer.browser.ChromeController
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ChromeDevToolsMediaBridge(
    private val scope: CoroutineScope,
    private val chromeController: ChromeController,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val mutableTrack = MutableStateFlow(TrackInfo())
    val track: StateFlow<TrackInfo> = mutableTrack
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private var connection: CdpConnection? = null
    private var pollingJob: Job? = null
    private var artworkUrl: String? = null

    suspend fun start() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                runCatching { pollState() }
                    .onFailure {
                        mutableError.value = it.message
                        connection?.close()
                        connection = null
                    }
                delay(1_000)
            }
        }
    }

    suspend fun togglePlayPause(): Boolean = executeBoolean(
        """(() => { const m=document.querySelector('video,audio'); if(!m) return false; if(m.paused){m.play();}else{m.pause();} return true; })()""",
    )

    suspend fun seekBy(deltaMillis: Long): Boolean = executeBoolean(
        """(() => { const m=document.querySelector('video,audio'); if(!m) return false; m.currentTime=Math.max(0,Math.min(m.duration||Infinity,m.currentTime+${deltaMillis / 1000.0})); return true; })()""",
    )

    suspend fun next(): Boolean = executeBoolean(
        """(() => { const b=document.querySelector('ytmusic-player-bar .next-button, .next-button'); if(!b) return false; b.click(); return true; })()""",
    )

    suspend fun previous(): Boolean = executeBoolean(
        """(() => { const b=document.querySelector('ytmusic-player-bar .previous-button, .previous-button'); if(!b) return false; b.click(); return true; })()""",
    )

    private suspend fun pollState() {
        val value = evaluate(
            """(() => {
                const metadata=navigator.mediaSession && navigator.mediaSession.metadata;
                const media=document.querySelector('video,audio');
                const artwork=metadata && metadata.artwork && metadata.artwork.length ? metadata.artwork[metadata.artwork.length-1].src : '';
                return {
                    title: metadata?.title || '', artist: metadata?.artist || '', album: metadata?.album || '', artworkUrl: artwork,
                    positionMillis: media && Number.isFinite(media.currentTime) ? Math.round(media.currentTime*1000) : 0,
                    durationMillis: media && Number.isFinite(media.duration) ? Math.round(media.duration*1000) : 0,
                    isPlaying: !!media && !media.paused
                };
            })()""",
        )?.jsonObject ?: return
        val newArtworkUrl = value.string("artworkUrl")
        val artwork = if (newArtworkUrl.isNotBlank() && newArtworkUrl != artworkUrl) {
            downloadArtwork(newArtworkUrl)?.also { artworkUrl = newArtworkUrl }
        } else mutableTrack.value.artworkBase64
        mutableTrack.value = TrackInfo(
            title = value.string("title"),
            artist = value.string("artist"),
            album = value.string("album"),
            artworkBase64 = artwork,
            positionMillis = value.long("positionMillis"),
            durationMillis = value.long("durationMillis"),
            isPlaying = value["isPlaying"]?.jsonPrimitive?.booleanOrNull == true,
        )
        mutableError.value = null
    }

    private suspend fun downloadArtwork(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299 || response.body().size > 12 * 1024 * 1024) null
            else Base64.getEncoder().encodeToString(response.body())
        }.getOrNull()
    }

    private suspend fun executeBoolean(expression: String): Boolean =
        evaluate(expression)?.jsonPrimitive?.booleanOrNull == true

    private suspend fun evaluate(expression: String): JsonElement? = withContext(Dispatchers.IO) {
        val active = connection?.takeIf { it.isOpen } ?: connect().also { connection = it }
        val response = active.call(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
                put("awaitPromise", true)
            },
        )
        response["result"]?.jsonObject
            ?.get("result")?.jsonObject
            ?.get("value")
    }

    private fun connect(): CdpConnection {
        val port = chromeController.devToolsPort() ?: error("専用ChromeのDevToolsポートを待機中です")
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/list"))
            .timeout(Duration.ofSeconds(3)).GET().build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        val target = json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonArray }
            .map(JsonElement::jsonObject)
            .firstOrNull { it.string("url").startsWith("https://music.youtube.com") }
            ?: error("YouTube Musicタブを待機中です")
        val webSocketUrl = target.string("webSocketDebuggerUrl").takeIf(String::isNotBlank)
            ?: error("Chrome DevTools接続先がありません")
        return CdpConnection(http, URI.create(webSocketUrl), json)
    }

    override fun close() {
        stop()
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        connection?.close()
        connection = null
    }

    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: 0L
}

private class CdpConnection(
    client: HttpClient,
    uri: URI,
    private val json: Json,
) : WebSocket.Listener {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val textBuffer = StringBuilder()
    private val socket: WebSocket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(4))
        .buildAsync(uri, this).get(5, TimeUnit.SECONDS)
    val isOpen: Boolean get() = !socket.isInputClosed && !socket.isOutputClosed

    fun call(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val request = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        socket.sendText(request.toString(), true).join()
        return future.get(5, TimeUnit.SECONDS)
    }

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
        textBuffer.append(data)
        if (last) {
            runCatching {
                val message = json.parseToJsonElement(textBuffer.toString()).jsonObject
                val id = message["id"]?.jsonPrimitive?.longOrNull
                if (id != null) pending.remove(id)?.complete(message)
            }
            textBuffer.clear()
        }
        webSocket.request(1)
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "closing").get(1, TimeUnit.SECONDS) }
    }
}
