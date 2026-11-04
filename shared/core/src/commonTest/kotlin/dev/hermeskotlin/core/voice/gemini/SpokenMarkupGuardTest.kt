package dev.hermeskotlin.core.voice.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A model reading a tool call out loud instead of calling it. */
class SpokenMarkupGuardTest {

    @Test
    fun wordsWithoutACallAreLeftAlone() {
        val guard = SpokenMarkupGuard()
        assertFalse(guard.feed("The weather today is "))
        assertFalse(guard.feed("sunny, around 31 degrees."))
    }

    @Test
    fun aCallReadOutIsCaughtEvenAcrossTwoDeltas() {
        val guard = SpokenMarkupGuard()
        assertFalse(guard.feed("Let me check that. <tool"))
        assertTrue(guard.feed("_call name=\"phone_torch\">"))
        // Once per turn: the rest of the arguments don't prompt it again.
        assertFalse(guard.feed("action: on"))
    }

    @Test
    fun theNextTurnIsWatchedAgain() {
        val guard = SpokenMarkupGuard()
        assertTrue(guard.feed("<function_call"))
        guard.reset()
        assertFalse(guard.feed("anything at all"))
        assertTrue(guard.feed("and then <TOOL_CODE"))
    }

    @Test
    fun theMarkersAreFoundWhateverTheirCase() {
        assertEquals(3, GeminiLiveProtocol.spokenToolMarkupStart("ok <Tool_Call"))
        assertNotNull(GeminiLiveProtocol.spokenToolMarkupStart("x <|tool"))
        assertNull(GeminiLiveProtocol.spokenToolMarkupStart("plain words only"))
    }
}
