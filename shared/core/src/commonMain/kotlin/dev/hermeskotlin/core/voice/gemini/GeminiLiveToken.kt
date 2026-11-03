package dev.hermeskotlin.core.voice.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What `/api/plugins/conduit_push/gemini-live/token` hands back: a single-use Google ephemeral
 * token, locked to one model, plus the socket address to spend it on.
 *
 * The key itself never leaves the Hermes host.
 */
data class GeminiLiveToken(
    val token: String,
    val webSocketUrl: String,
    val model: String,
    val voice: String?,
    val expiresAt: String?,
    val newSessionExpiresAt: String?,
) {
    /** The socket address with the credential attached, the way Conduit spends it. */
    fun connectionUrl(): String {
        if (webSocketUrl.contains("access_token=")) return webSocketUrl
        val separator = if (webSocketUrl.contains('?')) '&' else '?'
        return "$webSocketUrl${separator}access_token=$token"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the route's JSON body. Throws when the host reports it cannot mint one. */
        fun parse(body: String): GeminiLiveToken {
            val root = json.parseToJsonElement(body).jsonObject
            if (root["ok"]?.string() == "false") {
                throw IllegalStateException(root["reason"]?.string() ?: root["error"]?.string() ?: "token unavailable")
            }
            val token = root["token"]?.string()
                ?: throw IllegalStateException("token response has no token")
            val url = root["websocket_url"]?.string()
                ?: throw IllegalStateException("token response has no websocket_url")
            require(url.startsWith("wss://") || url.startsWith("ws://")) {
                "websocket_url is not a websocket address: $url"
            }
            return GeminiLiveToken(
                token = token,
                webSocketUrl = url,
                model = root["model"]?.string() ?: GeminiLiveProtocol.MODEL,
                voice = root["voice"]?.string(),
                expiresAt = root["expires_at"]?.string(),
                newSessionExpiresAt = root["new_session_expires_at"]?.string(),
            )
        }

        private fun kotlinx.serialization.json.JsonElement.string(): String? =
            (this as? JsonPrimitive)?.contentOrNull
    }
}
