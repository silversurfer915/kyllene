package dev.hermeskotlin.core.voice.gemini

/**
 * What the engine needs from a socket. Kyllene binds this to Ktor's `webSocketSession` (the client
 * `HttpClientFactory` already installs `WebSockets`); the JVM harness binds it to the JDK's own
 * WebSocket so the protocol can be proven without Android.
 */
interface GeminiLiveTransport {
    /** Opens the socket, spending [token] at [url]. Returns once the upgrade is accepted. */
    suspend fun connect(url: String, token: String)

    /** Sends one text frame. Throws when the socket is gone. */
    suspend fun send(frame: String)

    /** The next frame, or null once the socket has closed. Binary frames are decoded as UTF-8. */
    suspend fun receive(): String?

    /** Closes the socket; safe to call more than once. */
    suspend fun close()

    /** Google's close code and reason, once closed by either side. */
    val closeCode: Int?
    val closeReason: String?
}
