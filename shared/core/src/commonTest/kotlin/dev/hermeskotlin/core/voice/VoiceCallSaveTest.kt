package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the host's plugin is sent when a call ends: the shape its `voice/sessions` route reads. */
class VoiceCallSaveTest {

    private val saver = VoiceCallSave(createHttpClient(), GatewayRepository(InMemoryKeyValueStore()))

    @Test
    fun aCallGoesToItsChatAsAGeminiCall() {
        val body = saver.body(
            chatId = "chat-1",
            callId = "a".repeat(32),
            turns = listOf(CallTurn("user", "what's the weather"), CallTurn("assistant", "Sunny, 31.")),
        )
        assertEquals("chat-1", body["chat_id"]!!.jsonPrimitive.content)
        assertEquals("gemini-live", body["engine"]!!.jsonPrimitive.content)
        val turns = body["turns"]!!.jsonArray
        assertEquals(2, turns.size)
        assertEquals(0, turns[0].jsonObject["index"]!!.jsonPrimitive.content.toInt())
        assertEquals("user", turns[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Sunny, 31.", turns[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLongTurnIsCutToWhatTheRouteTakes() {
        val body = saver.body("chat-1", "b".repeat(32), listOf(CallTurn("assistant", "x".repeat(10_000))))
        assertEquals(4_000, body["turns"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.length)
    }
}
