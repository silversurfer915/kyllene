package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.HermesJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A call saved into a chat by the host plugin: [callId] names its turns. */
data class ChatCall(val callId: String, val engine: String?)

/**
 * Reads back the calls a chat holds, from the same `conduit_push` plugin that saved them
 * (`GET /voice/chat-calls`). A host whose plugin predates the route answers 404, and so does a chat with no calls:
 * both are an empty list, never an error.
 */
class VoiceCallHistory(private val client: HttpClient, private val gateways: GatewayRepository) {

    /** Every call saved into [chatId], oldest first. Empty when the host can't say. */
    suspend fun calls(chatId: String): List<ChatCall> = parseCalls(fetch(chatId, null))

    /** What was said on [callId], in order. Empty when the host can't say. */
    suspend fun turns(chatId: String, callId: String): List<CallTurn> = parseTurns(fetch(chatId, callId))

    private suspend fun fetch(chatId: String, callId: String?): JsonObject? {
        val gateway = gateways.current() ?: return null
        return try {
            val response = client.get(gateway.gatewayUrl.resolve(ROUTE)) {
                parameter("chat_id", chatId)
                if (callId != null) parameter("call_id", callId)
            }
            if (!response.status.isSuccess()) return null
            HermesJson.parseToJsonElement(response.bodyAsText()).jsonObject
                .takeIf { it.string("ok") == "true" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val ROUTE = "api/plugins/conduit_push/voice/chat-calls"
    }
}

/** A `chat-calls` reply's calls; anything unreadable is left out rather than failing the list. */
internal fun parseCalls(body: JsonObject?): List<ChatCall> {
    val calls = body?.get("calls") as? JsonArray ?: return emptyList()
    return calls.mapNotNull { element ->
        val call = element as? JsonObject ?: return@mapNotNull null
        val id = call.string("call_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ChatCall(id, call.string("engine"))
    }
}

/** One call's turns; a turn with no words is skipped, and a missing time is simply unknown. */
internal fun parseTurns(body: JsonObject?): List<CallTurn> {
    val turns = body?.get("turns") as? JsonArray ?: return emptyList()
    return turns.mapNotNull { element ->
        val turn = element as? JsonObject ?: return@mapNotNull null
        val text = turn.string("text")?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        CallTurn(turn.string("role") ?: "user", text, turn.get("at")?.jsonPrimitive?.doubleOrNull)
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content
