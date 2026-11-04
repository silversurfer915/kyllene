package dev.hermeskotlin.core.voice.gemini

/**
 * Watches the voice's own words for a tool call read out loud — "<tool_call>…" — which it sometimes does instead of
 * calling the function: nothing runs, and the arguments get spoken to the user. [feed] answers true once per turn,
 * the first time a line carries markup, so the caller can cut the voice off there; [reset] it when the turn ends.
 *
 * The transcript arrives in deltas, so a marker can straddle two of them: the line is kept, tail-capped, and
 * searched whole.
 */
internal class SpokenMarkupGuard {

    private val line = StringBuilder()
    private var noticed = false

    /** True once per turn: [delta] completed a line that carries a tool call the model is reading out. */
    fun feed(delta: String): Boolean {
        if (noticed || delta.isEmpty()) return false
        line.append(delta)
        if (line.length > KEEP_CHARS) line.delete(0, line.length - KEEP_CHARS)
        if (GeminiLiveProtocol.spokenToolMarkupStart(line.toString()) == null) return false
        noticed = true
        return true
    }

    fun reset() {
        line.clear()
        noticed = false
    }

    private companion object {
        /** Longer than any marker, so one that starts across two deltas is still found. */
        const val KEEP_CHARS = 64
    }
}
