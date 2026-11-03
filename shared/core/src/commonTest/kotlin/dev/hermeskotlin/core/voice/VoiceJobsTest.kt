package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.LiveStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Background jobs: started in their own chat, ended when the gateway stops listing them, read back once. */
class VoiceJobsTest {

    private class Harness(scope: kotlinx.coroutines.CoroutineScope) {
        val fake = FakeGateway(scope, reconnects = false).apply { ready = { FakeGateway.QUIET_READY } }
        var statuses: Map<String, LiveStatus> = emptyMap()
        var reply: String? = "the build is fixed"
        var now = 1_000_000L
        var created = 0
        var interrupted = mutableListOf<String>()
        var answer = """{"session_id":"rt-1","info":{"stored_session_id":"s-1","running":true,"title":"fix the build"}}"""

        val jobs = VoiceJobs(
            client = { (fake.connection.state.value as? ConnectionState.Connected)?.client },
            liveStatuses = { statuses },
            lastReply = { _, _ -> reply },
            clock = { now },
        )

        suspend fun start(): Harness {
            fake.answer = { call ->
                when (call.method) {
                    "session.create" -> {
                        created++
                        // Each start gets its own stored id, as the gateway gives.
                        """{"session_id":"rt-$created","info":{"stored_session_id":"s-$created","running":true,"title":""}}"""
                    }
                    "session.interrupt" -> {
                        call.param("session_id")?.let { interrupted += it }
                        "{}"
                    }
                    else -> "{}"
                }
            }
            fake.start()
            return this
        }
    }

    private val url = FakeGateway.URL

    @Test
    fun startCreatesAChatWhoseFirstPromptIsTheRequest() = runTest {
        val h = Harness(backgroundScope).start()
        val started = h.jobs.start("fix the build", profile = null, gateway = url)

        assertTrue(started.ok, started.error)
        assertEquals("s-1", started.job!!.storedId)
        assertEquals(VoiceJob.Status.Running, started.job!!.status)
        val create = h.fake.socket.sent.value.single { it.isCall("session.create") }
        assertEquals("fix the build", create.param("instruction"))
        assertNull(create.param("model"), "the profile's own model applies")
    }

    @Test
    fun aFourthJobIsRefusedRatherThanQueued() = runTest {
        val h = Harness(backgroundScope).start()
        repeat(3) { h.jobs.start("job $it", profile = null, gateway = url) }

        val fourth = h.jobs.start("one more", profile = null, gateway = url)
        assertEquals(false, fourth.ok)
        assertTrue(fourth.error!!.contains("already 3 jobs"), fourth.error)
        assertEquals(3, h.created)
    }

    @Test
    fun anEmptyRequestIsRefused() = runTest {
        val h = Harness(backgroundScope).start()
        assertEquals(false, h.jobs.start("   ", profile = null, gateway = url).ok)
        assertEquals(0, h.created)
    }

    @Test
    fun aJobEndsWhenTheGatewayStopsListingItAndItsAnswerIsReadOnce() = runTest {
        val h = Harness(backgroundScope).start()
        h.jobs.start("fix the build", profile = null, gateway = url)

        // Right after starting: not listed yet, but the grace window keeps it alive.
        h.jobs.refresh()
        assertEquals(VoiceJob.Status.Running, h.jobs.jobs.value.single().status)

        // Listed, then gone after the turn.
        h.now += 20_000
        h.statuses = mapOf("s-1" to LiveStatus.Working)
        h.jobs.refresh()
        assertEquals(VoiceJob.Status.Running, h.jobs.jobs.value.single().status)

        h.now += 20_000
        h.statuses = emptyMap()
        h.reply = "the build is fixed"
        h.jobs.refresh()

        val done = h.jobs.jobs.value.single()
        assertEquals(VoiceJob.Status.Done, done.status)
        assertEquals("the build is fixed", done.result)
    }

    @Test
    fun nothingIsCalledFinishedBeforeALiveAnswerHasBeenSeen() = runTest {
        val h = Harness(backgroundScope).start()
        h.jobs.start("fix the build", profile = null, gateway = url)

        // Never listed and past the grace window: an empty map from an uncollected flow proves nothing.
        h.now += 60_000
        h.jobs.refresh()
        assertEquals(VoiceJob.Status.Running, h.jobs.jobs.value.single().status)
    }

    @Test
    fun aJobWhoseAnswerCannotBeReadIsFailedNotDone() = runTest {
        val h = Harness(backgroundScope).start()
        h.jobs.start("fix the build", profile = null, gateway = url)
        h.now += 20_000
        h.statuses = mapOf("s-1" to LiveStatus.Working)
        h.jobs.refresh()
        h.now += 20_000
        h.statuses = emptyMap()
        h.reply = null
        h.jobs.refresh()

        assertEquals(VoiceJob.Status.Failed, h.jobs.jobs.value.single().status)
    }

    @Test
    fun cancelStopsTheNamedJobAndLeavesTheOthers() = runTest {
        val h = Harness(backgroundScope).start()
        val first = h.jobs.start("fix the build", profile = null, gateway = url).job!!
        val second = h.jobs.start("read the diffs", profile = null, gateway = url).job!!

        val stopped = h.jobs.cancel(first.id)

        assertEquals(listOf("fix the build"), stopped)
        assertEquals(listOf("rt-1"), h.interrupted)
        assertEquals(VoiceJob.Status.Cancelled, h.jobs.jobs.value.first { it.id == first.id }.status)
        assertEquals(VoiceJob.Status.Running, h.jobs.jobs.value.first { it.id == second.id }.status)
    }

    @Test
    fun cancelWithNoNameStopsEveryRunningJob() = runTest {
        val h = Harness(backgroundScope).start()
        h.jobs.start("one", profile = null, gateway = url)
        h.jobs.start("two", profile = null, gateway = url)

        assertEquals(2, h.jobs.cancel(null).size)
        assertEquals(listOf("rt-1", "rt-2"), h.interrupted)
        assertTrue(h.jobs.running().isEmpty())
    }

    @Test
    fun cancellingSomethingThatIsNotRunningStopsNothing() = runTest {
        val h = Harness(backgroundScope).start()
        assertEquals(emptyList(), h.jobs.cancel("job_99"))
        assertTrue(h.interrupted.isEmpty())
    }

    @Test
    fun aFinishedJobIsReportedToTheVoiceExactlyOnce() = runTest {
        val h = Harness(backgroundScope).start()
        h.jobs.start("fix the build", profile = null, gateway = url)
        h.now += 20_000
        h.statuses = mapOf("s-1" to LiveStatus.Working)
        h.jobs.refresh()
        h.now += 20_000
        h.statuses = emptyMap()
        h.jobs.refresh()

        val first = h.jobs.takeFinished()
        assertEquals(1, first.size)
        assertNotNull(first.single().result)
        assertEquals(emptyList(), h.jobs.takeFinished(), "a second call would say it again")
    }
}
