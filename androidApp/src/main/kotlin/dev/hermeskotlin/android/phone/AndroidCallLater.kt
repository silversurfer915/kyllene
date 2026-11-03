package dev.hermeskotlin.android.phone

import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.phone.CallReason
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.LiveSessions
import dev.hermeskotlin.core.voice.CallLater
import dev.hermeskotlin.core.voice.VoiceJob
import dev.hermeskotlin.core.voice.VoiceJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap

/**
 * "Call me when that's done" on this phone: one watch per chat, ringing like an incoming call once the chat's turn
 * — and anything it started — has ended.
 *
 * The ring is the same one `phone_call_me` uses ([ChatNotifications.ringForAgent]), so answering opens the chat it
 * rang for and the voice says why. Liveness comes from [ActiveSessions], which keeps polling the gateway while
 * something collects it; the chats a call started are [VoiceJobs], so a background job counts as work too.
 */
internal class AndroidCallLater(
    private val notifications: ChatNotifications,
    private val live: ActiveSessions,
    private val jobs: VoiceJobs,
    private val scope: CoroutineScope,
    private val api: PushApi,
) : CallLater {

    /** The watch armed for a chat, so asking again replaces it rather than ringing twice. */
    private val armed = ConcurrentHashMap<String, Job>()

    override suspend fun whenDone(chatId: String, title: String?, reason: String, running: Boolean): Boolean {
        // Ask the host first: its watch survives this app being closed or force-stopped, this one doesn't.
        if (runCatching { api.watchWhenDone(chatId, title, reason) }.getOrDefault(false)) return true
        // Nothing in flight: the ring could never come, so the voice is told not to promise one. The turn that asked
        // counts as running whatever a live list that nobody has been collecting says; otherwise ask for a fresh one.
        val jobsRunning = jobs.jobs.value.any {
            it.status == VoiceJob.Status.Running || it.status == VoiceJob.Status.Waiting
        }
        val working = running || jobsRunning || withTimeoutOrNull(ASK_MS) { live.live.first { busy(chatId, it) } } != null
        if (!working) return false
        lateinit var watch: Job
        watch = scope.launch {
            try {
                // A turn can be a beat behind the tool call that armed this; wait for it before waiting it out.
                withTimeoutOrNull(START_WAIT_MS) { live.live.first { busy(chatId, it) } }
                live.live.first { !busy(chatId, it) }
                // The last reply lands just after the turn stops.
                delay(SETTLE_MS)
                runCatching { notifications.ringForAgent(chatId, title, CallReason(reason)) }
            } finally {
                // Only this watch's own entry: a newer one for the same chat keeps its place.
                armed.remove(chatId, watch)
            }
        }
        armed.put(chatId, watch)?.cancel()
        return true
    }

    private fun busy(chatId: String, sessions: LiveSessions): Boolean {
        if (sessions.rows[chatId]?.status?.running == true) return true
        return jobs.jobs.value.any { it.status == VoiceJob.Status.Running || it.status == VoiceJob.Status.Waiting }
    }

    private companion object {
        /** How long to wait for the gateway's own answer on what is live before deciding nothing is. */
        const val ASK_MS = 5_000L

        /** How long a turn may take to show up on the gateway before the watch settles for whatever it sees. */
        const val START_WAIT_MS = 60_000L

        /** The last reply lands a moment after the turn stops, so the ring waits it out. */
        const val SETTLE_MS = 3_000L
    }
}

/** Koin definition: the notifier, the gateway's live sessions and the voice's jobs are all in the graph. */
val callLaterModule = module {
    single<CallLater> { AndroidCallLater(get(), get(), get(), get(), get()) }
}
