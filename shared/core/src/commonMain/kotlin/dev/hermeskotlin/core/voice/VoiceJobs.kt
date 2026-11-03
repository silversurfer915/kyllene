package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.LiveStatus
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One piece of work the voice started in a chat of its own, watched while the user talks about something else. */
data class VoiceJob(
    /** Local id the call refers to it by ("job 2"). */
    val id: String,
    /** The live id events carry, and the one `session.interrupt` takes. */
    val runtimeId: String?,
    /** The id it is stored under, which `session.active_list` and the transcript are keyed by. */
    val storedId: String,
    /** The request in the user's words, clipped for speaking. */
    val title: String,
    val status: Status = Status.Running,
    /** Hermes's final answer, once the turn ended and it could be read back. */
    val result: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    /** Whether its ending has already been spoken, so it isn't announced twice. */
    val reported: Boolean = false,
    /** The gateway it runs on, so its answer can be read back from the right host. */
    val gateway: GatewayUrl? = null,
) {
    enum class Status { Running, Waiting, Done, Failed, Cancelled }
}

/** What `start` managed: the job, or why there is none. */
data class JobStart(val job: VoiceJob? = null, val error: String? = null) {
    val ok: Boolean get() = job != null
}

/**
 * Background work for the voice: a Hermes chat of its own, started and watched without leaving the one on screen.
 *
 * Conduit's `VoiceBackgroundJobs` is the same shape — a job is a second session the phone created, polled for
 * liveness and read back when it ends. Kyllene already has every part: `session.create` starts the turn,
 * [ActiveSessions] says whether the session is still live, and the transcript gives the answer.
 *
 * ```
 * shortcut: liveness comes from ActiveSessions' 10 s poll, so a job is reported finished up to one poll late and
 * one that never shows up is treated as finished after GRACE_MS; upgrade to per-job event streams if the voice
 * needs second-level completion.
 * ```
 */
class VoiceJobs(
    /** The live gateway client, or null while disconnected. */
    private val client: () -> JsonRpcClient?,
    /** Status of every live session on the gateway, keyed by stored id ([ActiveSessions.live]). */
    private val liveStatuses: () -> Map<String, LiveStatus>,
    /** Hermes's last answer in a stored session on [gateway]; null when it can't be read. */
    private val lastReply: suspend (gateway: GatewayUrl, storedSessionId: String) -> String?,
    private val clock: () -> Long = { getTimeMillis() },
    private val maxConcurrent: Int = 3,
) {

    private val _jobs = MutableStateFlow<List<VoiceJob>>(emptyList())

    /** Every job this app started, oldest first. */
    val jobs: StateFlow<List<VoiceJob>> = _jobs.asStateFlow()

    private var seq = 0

    /** Whether a live-session answer has ever named a session; before that, an empty answer proves nothing. */
    private var seenLive = false

    /** Starts [prompt] in a chat of its own. The turn begins at once; nothing waits on the open chat. */
    suspend fun start(prompt: String, profile: String?, gateway: GatewayUrl): JobStart {
        val client = client() ?: return JobStart(error = "Hermes isn't connected right now.")
        val text = prompt.trim()
        if (text.isEmpty()) return JobStart(error = "There was nothing to start.")
        refresh()
        if (_jobs.value.count { it.status == VoiceJob.Status.Running || it.status == VoiceJob.Status.Waiting } >= maxConcurrent) {
            return JobStart(
                error = "There ${if (maxConcurrent == 1) "is" else "are"} already $maxConcurrent " +
                    "${if (maxConcurrent == 1) "job" else "jobs"} running — let one finish first.",
            )
        }
        val reply = try {
            client.request("session.create", buildJsonObject {
                profile?.let { put("profile", it) }
                put("source", CLIENT_SOURCE)
                put("cols", TERMINAL_COLUMNS)
                // The instruction is the first prompt: the turn starts with the session, in one call.
                put("instruction", text)
            }) as? JsonObject ?: return JobStart(error = "Hermes wouldn't start that chat.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            return JobStart(error = e.message.ifBlank { "Hermes wouldn't start that chat." })
        } catch (e: Exception) {
            return JobStart(error = "Hermes wouldn't start that chat.")
        }
        val stored = (reply["info"] as? JsonObject)?.get("stored_session_id")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: return JobStart(error = "Hermes started the chat but didn't say where.")
        val job = VoiceJob(
            id = "job_${++seq}",
            runtimeId = reply["session_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            storedId = stored,
            title = text.take(TITLE_CHARS),
            startedAt = clock(),
            gateway = gateway,
        )
        _jobs.update { it + job }
        return JobStart(job = job)
    }

    /**
     * Brings every running job up to date: a session [ActiveSessions] no longer lists has finished, and its
     * answer is read once. Cheap enough to call whenever the voice asks something.
     */
    suspend fun refresh() {
        val statuses = liveStatuses()
        // Collected and empty means nothing is running anywhere, which settles every job as finished — but only
        // once a non-empty answer has been seen. Before that, or while disconnected, an empty map means "unknown".
        if (statuses.isEmpty()) {
            if (!seenLive) return
        } else {
            seenLive = true
        }
        val now = clock()
        val running = _jobs.value.filter { it.status == VoiceJob.Status.Running || it.status == VoiceJob.Status.Waiting }
        for (job in running) {
            if (now - job.startedAt < GRACE_MS) continue
            when (val status = statuses[job.storedId]) {
                LiveStatus.Working, LiveStatus.Starting -> if (job.status != VoiceJob.Status.Running) {
                    update(job.id) { it.copy(status = VoiceJob.Status.Running) }
                }
                LiveStatus.Waiting -> if (job.status != VoiceJob.Status.Waiting) {
                    update(job.id) { it.copy(status = VoiceJob.Status.Waiting) }
                }
                // Absent or Idle after the grace window: the turn ended. The transcript still has the answer.
                null, LiveStatus.Idle -> {
                    val reply = runCatching {
                        job.gateway?.let { lastReply(it, job.storedId) }
                    }.getOrNull()
                    update(job.id) {
                        it.copy(
                            status = if (reply == null) VoiceJob.Status.Failed else VoiceJob.Status.Done,
                            result = reply,
                            finishedAt = now,
                        )
                    }
                }
            }
        }
    }

    /** Stops [id], or every running job when it is null. Returns the jobs it stopped. */
    suspend fun cancel(id: String?): List<String> {
        val client = client() ?: return emptyList()
        val targets = _jobs.value.filter {
            (id == null || it.id == id) && (it.status == VoiceJob.Status.Running || it.status == VoiceJob.Status.Waiting)
        }
        if (targets.isEmpty()) return emptyList()
        return targets.mapNotNull { job ->
            val stopped = try {
                // No runtime id yet means the create raced the cancel: the turn never started, so it is stopped.
                job.runtimeId?.let {
                    client.request("session.interrupt", buildJsonObject { put("session_id", it) })
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (stopped) {
                update(job.id) { it.copy(status = VoiceJob.Status.Cancelled, finishedAt = clock()) }
                job.title
            } else {
                null
            }
        }
    }

    /** The jobs still doing something, for `list_jobs`. */
    fun running(): List<VoiceJob> =
        _jobs.value.filter { it.status == VoiceJob.Status.Running || it.status == VoiceJob.Status.Waiting }

    /** The jobs that ended and haven't been told to the user yet; each is reported once. */
    fun takeFinished(): List<VoiceJob> {
        val done = _jobs.value.filter { it.status != VoiceJob.Status.Running && it.status != VoiceJob.Status.Waiting && !it.reported }
        if (done.isEmpty()) return emptyList()
        _jobs.update { list ->
            list.map { job -> if (done.any { it.id == job.id }) job.copy(reported = true) else job }
        }
        return done
    }

    private fun update(id: String, change: (VoiceJob) -> VoiceJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private companion object {
        const val CLIENT_SOURCE = "kyllene"
        const val TERMINAL_COLUMNS = 80
        const val TITLE_CHARS = 120

        /** A job started this recently may not be listed yet; calling it finished would cut it short. */
        const val GRACE_MS = 15_000L
    }
}
