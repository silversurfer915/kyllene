package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCallHistoryTest {
    private fun json(text: String) = HermesJson.parseToJsonElement(text).jsonObject

    @Test
    fun calls_are_read_in_order_and_named() {
        val calls = parseCalls(json("""{"ok":true,"chat_id":"c1","calls":[{"call_id":"a","engine":"gemini-live"},{"call_id":"b"}]}"""))
        assertEquals(listOf("a", "b"), calls.map { it.callId })
        assertEquals("gemini-live", calls.first().engine)
        assertNull(calls.last().engine)
    }

    @Test
    fun a_call_without_an_id_is_left_out() {
        assertEquals(listOf("a"), parseCalls(json("""{"ok":true,"calls":[{"call_id":"a"},{},{"call_id":"  "}]}""")).map { it.callId })
    }

    @Test
    fun turns_keep_who_said_what_and_when() {
        val turns = parseTurns(json("""{"ok":true,"turns":[{"role":"user","text":"hi","at":10.5},{"role":"assistant","text":" hello "},{"role":"user","text":"  "}]}"""))
        assertEquals(listOf("hi", "hello"), turns.map { it.text })
        assertEquals(listOf("user", "assistant"), turns.map { it.role })
        assertEquals(10.5, turns.first().at)
        assertNull(turns.last().at)
    }

    @Test
    fun an_error_or_404_reply_reads_as_no_calls() {
        assertEquals(emptyList(), parseCalls(null))
        assertEquals(emptyList(), parseCalls(json("""{"detail":"Not Found"}""")))
        assertEquals(emptyList(), parseTurns(json("""{"ok":false}""")))
    }
}
