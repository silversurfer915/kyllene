package dev.hermeskotlin.core.voice.gemini

/**
 * The call's own words, kept for the session that takes over after Google rotates the old one: a model dropped into a
 * fresh session knows nothing of the conversation, and would greet the user again or lose the thread.
 *
 * Only the tail is kept ([maxChars]): what a rejoin needs is the last thing said, not the whole call.
 */
internal class CallContinuity(
    private val maxChars: Int = 2_000,
    private val rejoinChars: Int = 900,
) {

    private val said = StringBuilder()
    private var lastRole: String? = null

    /** One transcript fragment, from "them" or "you"; a speaker's run is labelled once. */
    fun note(role: String, text: String) {
        if (text.isEmpty()) return
        if (lastRole != role) {
            if (said.isNotEmpty()) said.append('\n')
            said.append(if (role == "them") "Them: " else "You: ")
            lastRole = role
        }
        said.append(text)
        if (said.length > maxChars) said.delete(0, said.length - maxChars)
    }

    /** The turn is over: the next speaker starts a new line, even if it is the same one. */
    fun turnEnded() {
        lastRole = null
    }

    /**
     * What a fresh session is told, quietly, before anything else goes out: the interruption, the last of the call,
     * what Hermes is still working on, and that a request in flight may have gone with the connection.
     */
    fun rejoinNote(running: List<String>): String = buildString {
        append("[Kyllene: the call's connection dropped and is back on a new session. Don't mention the ")
        append("interruption unless they ask; carry on where you were.]")
        val before = said.toString().trim().takeLast(rejoinChars)
        if (before.isNotEmpty()) append("\n\nWhat was said before it went:\n").append(before)
        if (running.isNotEmpty()) append("\n\nHermes is still working on: ").append(running.joinToString("; "))
        append("\n\nA request in flight may have been lost with the connection: if you were waiting on one, ")
        append("ask again.")
    }
}
