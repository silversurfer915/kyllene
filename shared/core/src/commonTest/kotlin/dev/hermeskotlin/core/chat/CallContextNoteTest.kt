package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallContextNoteTest {
    private fun user(text: String) = ChatMessage.User(key = "u", text = text)
    private fun assistant(text: String) = ChatMessage.Assistant(key = "a", text = text)

    @Test
    fun the_chat_it_was_started_from_is_named_in_order() {
        val note = callContextNote(listOf(user("fix the voice routing"), assistant("Done — 0.9.5 routes by one test.")))
        assertTrue(note.contains("They said: fix the voice routing"))
        assertTrue(note.contains("You answered: Done — 0.9.5 routes by one test."))
        assertTrue(note.indexOf("They said") < note.indexOf("You answered"))
    }

    @Test
    fun the_newest_lines_are_the_ones_kept() {
        val note = callContextNote(
            listOf(user("oldest thing said"), user("newest thing said")),
            limit = 40,
        )
        assertTrue(note.contains("newest thing said"))
        assertFalse(note.contains("oldest thing said"))
    }

    @Test
    fun nothing_said_means_no_note() {
        assertEquals("", callContextNote(listOf(assistant("   "))))
    }
}
