package dev.hermeskotlin.core.voice.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.put

class SetupOrderTest {
    @Test
    fun every_object_goes_out_with_its_keys_in_order() {
        val setup = GeminiLiveProtocol.setupMessage(systemInstruction = "be brief")
        val first = setup.indexOf("\"setup\"")
        assertTrue(first >= 0)
        // Nested objects are sorted too: generationConfig before realtimeInputConfig before sessionResumption.
        assertTrue(setup.indexOf("\"generationConfig\"") < setup.indexOf("\"realtimeInputConfig\""))
        assertTrue(setup.indexOf("\"realtimeInputConfig\"") < setup.indexOf("\"sessionResumption\""))
        assertTrue(setup.indexOf("\"systemInstruction\"") < setup.indexOf("\"tools\""))
        assertEquals(setup, GeminiLiveProtocol.setupMessage(systemInstruction = "be brief"))
    }

    @Test
    fun an_array_keeps_the_order_it_was_given() {
        val sorted = sortedKeys(
            kotlinx.serialization.json.buildJsonObject {
                put("z", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("b"))
                    add(kotlinx.serialization.json.JsonPrimitive("a"))
                })
                put("a", kotlinx.serialization.json.JsonPrimitive(1))
            },
        )
        assertTrue(sorted.indexOf("\"a\":1") < sorted.indexOf("\"z\""), sorted)
        // The array is not sorted: its order is the payload's, not the encoder's.
        assertTrue(sorted.contains("[\"b\",\"a\"]"), sorted)
    }
}
