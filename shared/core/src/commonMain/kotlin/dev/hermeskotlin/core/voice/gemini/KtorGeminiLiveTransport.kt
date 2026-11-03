package dev.hermeskotlin.core.voice.gemini

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The Ktor socket under a Gemini Live call. The connection goes to Google, not the gateway, and the credential
 * rides in its query string, so Kyllene's gateway cookies are never sent to it (the cookie store is per host).
 */
class KtorGeminiLiveTransport(
    private val client: HttpClient,
    /** The call's scope: the reader lives and dies with it. */
    private val scope: CoroutineScope,
) : GeminiLiveTransport {

    private var session: DefaultClientWebSocketSession? = null
    private var reader: Job? = null
    private val frames = Channel<String>(Channel.BUFFERED)

    @Volatile
    override var closeCode: Int? = null
        private set

    @Volatile
    override var closeReason: String? = null
        private set

    override suspend fun connect(url: String, token: String) {
        check(url.contains("access_token=")) { "connection URL carries no access_token" }
        val opened = client.webSocketSession(url)
        session = opened
        // One reader for the connection: the engine takes frames in order and must not race.
        reader = scope.launch {
            try {
                for (frame in opened.incoming) {
                    when (frame) {
                        is Frame.Text -> frames.send(frame.readText())
                        is Frame.Binary -> frames.send(frame.data.decodeToString())
                        else -> Unit
                    }
                }
            } catch (_: Throwable) {
                // A dropped socket is a close: the engine reads null and reports it.
            } finally {
                val reason = runCatching { opened.closeReason.await() }.getOrNull()
                closeCode = reason?.code?.toInt()
                closeReason = reason?.message
                frames.close()
            }
        }
    }

    override suspend fun send(frame: String) {
        val open = session ?: error("socket is not open")
        open.send(frame)
    }

    override suspend fun receive(): String? = frames.receiveCatching().getOrNull()

    override suspend fun close() {
        val open = session ?: return
        session = null
        runCatching { open.close(CloseReason(CloseReason.Codes.NORMAL, "done")) }
        reader?.cancel()
        frames.close()
    }
}
