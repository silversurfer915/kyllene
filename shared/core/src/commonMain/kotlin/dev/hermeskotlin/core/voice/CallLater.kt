package dev.hermeskotlin.core.voice

/**
 * "Call me when that's done": rings this phone once a chat's work ends, so the user can hang up and still hear how
 * it went.
 *
 * Android rings like an incoming call, opening the chat it rang for; a desktop has no phone to ring and never
 * registers this, so the voice tool is not declared there.
 */
interface CallLater {

    /**
     * Rings when [chatId] (a stored session id) and anything it started are no longer running. [title] names the
     * chat on the ring; [reason] is what the voice says once it's answered. [running] is what the caller already
     * knows: its own turn is under way, so a live list that hasn't caught up can't say otherwise.
     *
     * False when nothing is running to wait for: the promise would be a lie, so the voice says so instead.
     */
    suspend fun whenDone(chatId: String, title: String?, reason: String, running: Boolean): Boolean
}
