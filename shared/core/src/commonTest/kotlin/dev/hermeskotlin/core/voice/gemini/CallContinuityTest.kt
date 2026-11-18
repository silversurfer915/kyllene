package dev.hermeskotlin.core.voice.gemini

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a session that takes over after a rotation is told about the call it never heard. */
class CallContinuityTest {

    @Test
    fun eachSpeakerIsLabelledOncePerRun() {
        val call = CallContinuity()
        call.note("them", "what's the weather")
        call.note("them", " in Coimbatore")
        call.note("you", "Let me check.")
        val note = call.rejoinNote(emptyList())
        assertTrue("Them: what's the weather in Coimbatore" in note, note)
        assertTrue("You: Let me check." in note, note)
    }

    @Test
    fun theNextTurnStartsANewLine() {
        val call = CallContinuity()
        call.note("them", "one")
        call.turnEnded()
        call.note("them", "two")
        assertTrue("Them: one\nThem: two" in call.rejoinNote(emptyList()))
    }

    @Test
    fun onlyTheTailIsKept() {
        val call = CallContinuity(maxChars = 40, rejoinChars = 20)
        repeat(20) { call.note("them", "a long sentence $it") }
        val note = call.rejoinNote(emptyList())
        assertFalse("a long sentence 0" in note, "the start must have been dropped")
        assertTrue("a long sentence 19" in note, note)
    }

    @Test
    fun aRejoinSaysWhatIsStillRunningAndToAskAgain() {
        val call = CallContinuity()
        call.note("them", "start the build")
        val note = call.rejoinNote(listOf("the Android build"))
        assertTrue("still working on: the Android build" in note, note)
        assertTrue("ask again" in note, note)
    }

    @Test
    fun a_reconnect_while_hermes_answers_says_the_answer_is_coming() {
        val call = CallContinuity()
        val note = call.rejoinNote(running = emptyList(), waiting = true)
        assertTrue(note.contains("still on its way"))
        assertTrue(note.contains("don't ask again"))
        assertFalse(note.contains("ask again."))
    }

    @Test
    fun a_reconnect_with_nothing_out_still_offers_to_ask_again() {
        assertTrue(CallContinuity().rejoinNote(running = emptyList()).contains("ask again"))
    }
}
