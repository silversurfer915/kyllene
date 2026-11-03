package dev.hermeskotlin.core.voice.gemini

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One web result, as the host's search backend gave it: enough to speak, not enough to read aloud in full. */
@Serializable
data class SearchHit(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
)

/** Whether the Hermes host can mint a Gemini Live token: it holds the Google key, the phone never does. */
@Serializable
data class GeminiLiveStatus(
    val available: Boolean = false,
    val reason: String? = null,
    val model: String? = null,
)

/**
 * The `conduit_push` dashboard plugin's Gemini Live routes, the same ones Conduit calls on iOS.
 *
 * `GEMINI_API_KEY` stays on the host; each connection spends a single-use token locked to one model.
 */
class GeminiLiveApi(private val client: HttpClient) {

    suspend fun status(url: GatewayUrl, profile: String?): ApiResult<GeminiLiveStatus> = apiCall {
        client.get(url.resolve(PLUGIN + "gemini-live/status")) {
            profile?.let { parameter("profile", it) }
        }
    }.map { it.body<GeminiLiveStatus>() }

    /** Mints one token. The response carries the socket address it is good for. */
    suspend fun token(url: GatewayUrl, profile: String?): ApiResult<GeminiLiveToken> = apiCall {
        client.post(url.resolve(PLUGIN + "gemini-live/token")) {
            profile?.let { parameter("profile", it) }
        }
    }.map { GeminiLiveToken.parse(it.bodyAsText()) }

    /** The profile's SOUL/persona, so the voice sounds like Hermes rather than a generic assistant. */
    suspend fun personality(url: GatewayUrl, profile: String?): String? {
        val response = when (
            val result = apiCall {
                client.get(url.resolve(PLUGIN + "personality")) {
                    profile?.let { parameter("profile", it) }
                }
            }
        ) {
            is ApiResult.Success -> result.value
            else -> return null
        }
        val body = runCatching { response.body<PersonaBody>() }.getOrNull() ?: return null
        if (!body.ok || !body.available) return null
        return body.text.trim().takeIf { it.isNotEmpty() }
    }

    /** What the host remembers, so the voice opens knowing who it is talking to. */
    suspend fun memoryContext(url: GatewayUrl, profile: String?): String? {
        val response = when (
            val result = apiCall {
                client.get(url.resolve(PLUGIN + "memory/context")) {
                    profile?.let { parameter("profile", it) }
                }
            }
        ) {
            is ApiResult.Success -> result.value
            else -> return null
        }
        val body = runCatching { response.body<MemoryBody>() }.getOrNull() ?: return null
        if (!body.ok || !body.available) return null
        return body.context.trim().takeIf { it.isNotEmpty() }
    }

    /** Asks the host's own search backend. Null when the route is missing or the backend is down. */
    suspend fun webSearch(url: GatewayUrl, profile: String?, query: String, limit: Int = 3): List<SearchHit>? {
        val response = when (
            val result = apiCall {
                client.post(url.resolve(PLUGIN + "web-search")) {
                    profile?.let { parameter("profile", it) }
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("query", query)
                        put("limit", limit)
                    })
                }
            }
        ) {
            is ApiResult.Success -> result.value
            else -> return null
        }
        val body = runCatching { response.body<SearchBody>() }.getOrNull() ?: return null
        return if (body.ok) body.results else null
    }

    private companion object {
        const val PLUGIN = "api/plugins/conduit_push/"
    }
}

@Serializable
private data class PersonaBody(val ok: Boolean = false, val available: Boolean = false, val text: String = "")

@Serializable
private data class MemoryBody(val ok: Boolean = false, val available: Boolean = false, val context: String = "")

/** The `web-search` route's body. */
@Serializable
private data class SearchBody(
    val ok: Boolean = false,
    val results: List<SearchHit> = emptyList(),
)