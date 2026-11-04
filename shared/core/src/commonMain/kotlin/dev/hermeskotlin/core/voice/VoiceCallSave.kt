package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One thing said on a call, and who said it. */
data class CallTurn(val role: String, val text: String)

/**
 * Saves a live call to the chat it was started from, through the host's `conduit_push` plugin:
 * `POST /api/plugins/conduit_push/voice/sessions` with a `chat_id` makes it a chat call — the plugin keeps the turns
 * with that chat without touching its own messages, and writes them to memory when the call ends, so a later chat
 * can recall the call.
 *
 * A host whose plugin predates the route answers 404: nothing is saved, and nothing is wrong.
 */
class VoiceCallSave(private val client: HttpClient, private val gateways: GatewayRepository) {

    /** True when the host took the call. Never raises: a call ending must not be held up by a save. */
    suspend fun save(chatId: String, callId: String, turns: List<CallTurn>): Boolean {
        val kept = turns.mapNotNull { turn -> turn.text.trim().takeIf { it.isNotEmpty() }?.let { CallTurn(turn.role, it) } }
        if (kept.isEmpty()) return false
        val gateway = gateways.current() ?: return false
        return try {
            val response = client.post(gateway.gatewayUrl.resolve(ROUTE)) {
                contentType(ContentType.Application.Json)
                setBody(body(chatId, callId, kept).toString())
            }
            response.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** The plugin's body: a call attached to [chatId], its turns counted from zero. */
    internal fun body(chatId: String, callId: String, turns: List<CallTurn>): JsonObject = buildJsonObject {
        put("call_id", callId)
        put("chat_id", chatId)
        put("engine", ENGINE)
        putJsonArray("turns") {
            turns.forEachIndexed { index, turn ->
                addJsonObject {
                    put("index", index)
                    put("role", turn.role)
                    put("text", turn.text.take(MAX_TURN_CHARS))
                }
            }
        }
    }

    private companion object {
        const val ROUTE = "api/plugins/conduit_push/voice/sessions"
        const val ENGINE = "gemini-live"
        const val MAX_TURN_CHARS = 4_000
    }
}
