package dev.hermeskotlin.core.voice.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The voice's tools, through the bridge: declarations, dispatch and the words a refusal uses. */
class VoiceToolBridgeTest {

    private fun bridge(
        lastReply: () -> String? = { null },
        steer: suspend (String) -> Boolean = { true },
        stop: suspend () -> List<String> = { emptyList() },
        jobs: suspend () -> JobSnapshot = { JobSnapshot() },
        recall: suspend (String) -> String? = { null },
        search: suspend (String) -> List<SearchHit>? = { null },
        chats: dev.hermeskotlin.core.voice.ChatLookup? = null,
        setAskFirst: (suspend (Boolean) -> Boolean)? = null,
        endCall: (() -> Unit)? = null,
        showCard: ((String, String) -> Unit)? = null,
        whenDone: (suspend (String) -> Boolean)? = null,
        openChat: ((String, String) -> Unit)? = null,
    ) = VoiceToolBridge(
        lastReply, steer, stop, jobs, recall, search,
        chats = chats,
        setAskFirst = setAskFirst,
        endCall = endCall,
        showCard = showCard,
        whenDone = whenDone,
        openChat = openChat,
    )

    private fun call(name: String, vararg args: Pair<String, String>) =
        GeminiLiveProtocol.FunctionCall(
            id = "call_$name",
            name = name,
            arguments = args.toMap(),
        )

    private fun ToolOutcome.status() = result["status"]!!.jsonPrimitive.content

    /** Stands in for the gateway's chat search and transcript. */
    private class FakeChats(
        private val hits: List<dev.hermeskotlin.core.voice.ChatHit> = listOf(
            dev.hermeskotlin.core.voice.ChatHit("s-1", "fixing the android build", "gradle daemon"),
            dev.hermeskotlin.core.voice.ChatHit("s-2", "the exam plan", null),
            dev.hermeskotlin.core.voice.ChatHit("s-3", "voice latency", null),
        ),
        private val reply: String? = "the build is fixed",
    ) : dev.hermeskotlin.core.voice.ChatLookup {
        override suspend fun find(query: String, limit: Int) = hits.take(limit)
        override suspend fun lastReply(sessionId: String) = reply
    }

    @Test
    fun declaresEveryToolWithItsScheduling() {
        val declarations = bridge().declarations()
        assertEquals(
            listOf(
                VoiceToolBridge.LIST_JOBS,
                VoiceToolBridge.RECALL_MEMORY,
                VoiceToolBridge.WEB_SEARCH,
                VoiceToolBridge.READ_LAST_REPLY,
                VoiceToolBridge.INTERRUPT_JOB,
                VoiceToolBridge.CANCEL_JOB,
            ),
            declarations.map { it.name },
        )
        val byName = declarations.associateBy { it.name }
        // Read-back and stop are answered at once; a steer lets the voice keep talking.
        assertEquals(GeminiLiveProtocol.FunctionDeclaration.Behavior.BLOCKING, byName.getValue(VoiceToolBridge.LIST_JOBS).behavior)
        assertEquals(GeminiLiveProtocol.FunctionDeclaration.Behavior.BLOCKING, byName.getValue(VoiceToolBridge.READ_LAST_REPLY).behavior)
        assertEquals(GeminiLiveProtocol.FunctionDeclaration.Behavior.BLOCKING, byName.getValue(VoiceToolBridge.CANCEL_JOB).behavior)
        assertEquals(
            GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
            byName.getValue(VoiceToolBridge.INTERRUPT_JOB).behavior,
        )
    }

    @Test
    fun declarationsCarryJsonSchemasTheModelCanUse() {
        val json = Json.parseToJsonElement(
            buildString {
                append("""{"tools":[""")
                append(bridge().declarations().joinToString(",") { it.json().toString() })
                append("]}")
            },
        ).jsonObject["tools"]!!.let { it as kotlinx.serialization.json.JsonArray }

        val interrupt = json.first { it.jsonObject["name"]!!.jsonPrimitive.content == VoiceToolBridge.INTERRUPT_JOB }
            .jsonObject
        val required = interrupt["parameters"]!!.jsonObject["required"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals("message", required[0].jsonPrimitive.content)
        assertEquals("NON_BLOCKING", interrupt["behavior"]!!.jsonPrimitive.content)
    }

    @Test
    fun readLastReplyReturnsWhatHermesSaid() = runTest {
        val outcome = bridge(lastReply = { "  The exam is on Friday.  " }).handle(call(VoiceToolBridge.READ_LAST_REPLY))
        assertEquals("ok", outcome.status())
        assertEquals("The exam is on Friday.", outcome.result["reply"]!!.jsonPrimitive.content)
        assertEquals(null, outcome.scheduling)
    }

    @Test
    fun readLastReplySaysWhenThereIsNothing() = runTest {
        val outcome = bridge(lastReply = { "   " }).handle(call(VoiceToolBridge.READ_LAST_REPLY))
        assertEquals("empty", outcome.status())
        assertTrue(outcome.result["message"]!!.jsonPrimitive.content.contains("hasn't said"))
    }

    @Test
    fun readLastReplyClipsALongAnswer() = runTest {
        val long = "x".repeat(VoiceToolBridge.MAX_REPLY_CHARS + 50)
        val outcome = bridge(lastReply = { long }).handle(call(VoiceToolBridge.READ_LAST_REPLY))
        assertEquals(VoiceToolBridge.MAX_REPLY_CHARS, outcome.result["reply"]!!.jsonPrimitive.content.length)
        assertEquals(true, outcome.result["truncated"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun interruptJobPassesTheWordsIntoTheRunningTurn() = runTest {
        var heard: String? = null
        val outcome = bridge(steer = { words -> heard = words; true })
            .handle(call(VoiceToolBridge.INTERRUPT_JOB, "message" to " wait, make it Alex "))
        assertEquals("delivered", outcome.status())
        assertEquals("wait, make it Alex", heard)
        assertEquals("WHEN_IDLE", outcome.scheduling)
    }

    @Test
    fun interruptJobSaysWhenNothingWasRunning() = runTest {
        val outcome = bridge(steer = { false }).handle(call(VoiceToolBridge.INTERRUPT_JOB, "message" to "never mind"))
        assertEquals("nothing_running", outcome.status())
        // Still scheduled idle: the voice is not left waiting on a call that will never end.
        assertEquals("WHEN_IDLE", outcome.scheduling)
    }

    @Test
    fun interruptJobRefusesEmptyWordsWithoutCallingSteer() = runTest {
        var called = false
        val outcome = bridge(steer = { called = true; true }).handle(call(VoiceToolBridge.INTERRUPT_JOB))
        assertEquals("error", outcome.status())
        assertEquals(false, called)
        assertEquals("WHEN_IDLE", outcome.scheduling)
    }

    @Test
    fun interruptJobSurvivesASteerThatThrows() = runTest {
        val outcome = bridge(steer = { error("gateway gone") })
            .handle(call(VoiceToolBridge.INTERRUPT_JOB, "message" to "stop"))
        assertEquals("error", outcome.status())
        assertEquals("WHEN_IDLE", outcome.scheduling)
    }

    @Test
    fun cancelJobReportsWhatItDropped() = runTest {
        val outcome = bridge(stop = { listOf("first", "second") }).handle(call(VoiceToolBridge.CANCEL_JOB))
        assertEquals("stopped", outcome.status())
        assertTrue(outcome.result["message"]!!.jsonPrimitive.content.contains("2 queued"))
    }

    @Test
    fun cancelJobWithNothingRunningStillSucceeds() = runTest {
        val outcome = bridge(stop = { emptyList() }).handle(call(VoiceToolBridge.CANCEL_JOB))
        assertEquals("stopped", outcome.status())
        assertTrue(outcome.result["message"]!!.jsonPrimitive.content.contains("Nothing was waiting"))
    }

    @Test
    fun listJobsSaysWhenHermesIsIdle() = runTest {
        val outcome = bridge().handle(call(VoiceToolBridge.LIST_JOBS))
        assertEquals("idle", outcome.status())
        assertEquals("Hermes isn't working on anything right now.", (outcome.result["summary"]!!).jsonPrimitive.content)
    }

    @Test
    fun listJobsReadsBackEverythingInFlight() = runTest {
        val outcome = bridge(
            jobs = {
                JobSnapshot(
                    running = "running terminal",
                    queued = listOf("check the other repo"),
                    subagents = listOf("measure the diff"),
                    processes = listOf("npm run dev", "./gradlew"),
                )
            },
        ).handle(call(VoiceToolBridge.LIST_JOBS))

        assertEquals("busy", outcome.status())
        assertEquals(
            "Right now it's running terminal, with 1 request waiting, 1 subagent working, " +
                "2 background processes running.",
            (outcome.result["summary"]!!).jsonPrimitive.content,
        )
        val processes = outcome.result["processes"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(2, processes.size)
    }

    @Test
    fun listJobsSpeaksPluralsCorrectly() = runTest {
        val outcome = bridge(jobs = { JobSnapshot(running = "writing a reply", queued = listOf("a", "b")) })
            .handle(call(VoiceToolBridge.LIST_JOBS))
        assertEquals(
            "Right now it's writing a reply, with 2 requests waiting.",
            (outcome.result["summary"]!!).jsonPrimitive.content,
        )
    }

    @Test
    fun listJobsCapsHowMuchItReports() = runTest {
        val outcome = bridge(
            jobs = { JobSnapshot(queued = List(20) { "prompt $it" }) },
        ).handle(call(VoiceToolBridge.LIST_JOBS))
        val queued = outcome.result["queued"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(VoiceToolBridge.MAX_JOBS, queued.size)
    }

    @Test
    fun listJobsAnswersWhenTheGatewayWontSay() = runTest {
        val outcome = bridge(jobs = { error("no session") }).handle(call(VoiceToolBridge.LIST_JOBS))
        assertEquals("error", outcome.status())
        assertEquals("error", outcome.result["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun recallMemoryReturnsWhatTheHostRemembers() = runTest {
        val outcome = bridge(recall = { "Hareesh is preparing for JEE Main 2027." })
            .handle(call(VoiceToolBridge.RECALL_MEMORY, "query" to " exam "))
        assertEquals("ok", outcome.status())
        assertEquals("Hareesh is preparing for JEE Main 2027.", outcome.result["memory"]!!.jsonPrimitive.content)
    }

    @Test
    fun recallMemoryClipsALongMemory() = runTest {
        val outcome = bridge(recall = { "y".repeat(5_000) })
            .handle(call(VoiceToolBridge.RECALL_MEMORY, "query" to "anything"))
        assertEquals(VoiceToolBridge.MAX_MEMORY_CHARS, outcome.result["memory"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun recallMemorySaysWhenItFoundNothing() = runTest {
        val outcome = bridge(recall = { "" }).handle(call(VoiceToolBridge.RECALL_MEMORY, "query" to "pokemon"))
        assertEquals("empty", outcome.status())
        assertTrue(outcome.result["message"]!!.jsonPrimitive.content.contains("don't have anything"))
    }

    @Test
    fun memoryMatchesRankTheLinesWithTheMostWords() {
        val memory = """
            ══════════════════════════════════════════════
            MEMORY (your personal notes)
            ══════════════════════════════════════════════
            Ponytail is natively Hermes-compatible but its root plugin.json lacks a schema.
            §
            Android builds here: SDK ~/android-sdk. 7 GB RAM — Gradle daemon ≤2.2 GB heap.
            Cron schedules are interpreted in Asia/Kolkata on this host.
        """.trimIndent()

        val hit = VoiceToolBridge.memoryMatches(memory, "gradle heap on android")
        assertTrue(hit.contains("Gradle daemon"), hit)
        assertTrue(!hit.contains("═"), "separator lines are not memory: $hit")
        assertEquals("", VoiceToolBridge.memoryMatches(memory, "quantum chromodynamics"))
        assertEquals("", VoiceToolBridge.memoryMatches(memory, "a"), "one-letter words match nothing")
    }

    @Test
    fun recallMemorySaysWhenTheHostHasNone() = runTest {
        val outcome = bridge().handle(call(VoiceToolBridge.RECALL_MEMORY, "query" to "my exam"))
        assertEquals("unavailable", outcome.status())
        assertTrue(outcome.result["message"]!!.jsonPrimitive.content.contains("can't reach"))
    }

    @Test
    fun recallMemoryRefusesAnEmptyQuery() = runTest {
        var asked = false
        val outcome = bridge(recall = { asked = true; "x" }).handle(call(VoiceToolBridge.RECALL_MEMORY))
        assertEquals("error", outcome.status())
        assertEquals(false, asked)
    }

    @Test
    fun webSearchSpeaksTheFirstResultAndCountsTheRest() = runTest {
        val outcome = bridge(
            search = {
                listOf(
                    SearchHit("JEE Main 2027 dates", "https://nta.ac.in/a", "Session 1 runs 20 to 28 January."),
                    SearchHit("JEE Main session 2", "https://nta.ac.in/b", "Session 2 runs 2 to 7 April."),
                )
            },
        ).handle(call(VoiceToolBridge.WEB_SEARCH, "query" to "jee main 2027 dates"))

        assertEquals("ok", outcome.status())
        assertEquals(
            "Session 1 runs 20 to 28 January. There is 1 more.",
            outcome.result["summary"]!!.jsonPrimitive.content,
        )
        val results = outcome.result["results"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(2, results.size)
    }

    @Test
    fun webSearchReportsNothingFoundRatherThanFailing() = runTest {
        val outcome = bridge(search = { emptyList() }).handle(call(VoiceToolBridge.WEB_SEARCH, "query" to "zzz"))
        assertEquals("empty", outcome.status())
        assertEquals("Nothing came up for that.", outcome.result["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun webSearchSaysWhenTheBackendIsDown() = runTest {
        val outcome = bridge(search = { null }).handle(call(VoiceToolBridge.WEB_SEARCH, "query" to "news"))
        assertEquals("unavailable", outcome.status())
    }

    @Test
    fun webSearchCapsResultsAndClipsSnippets() = runTest {
        val outcome = bridge(
            search = { List(10) { SearchHit("t$it", "https://x/$it", "s".repeat(900)) } },
        ).handle(call(VoiceToolBridge.WEB_SEARCH, "query" to "anything"))
        val results = outcome.result["results"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(VoiceToolBridge.MAX_SEARCH_HITS, results.size)
        val snippet = results[0].jsonObject["snippet"]!!.jsonPrimitive.content
        assertEquals(VoiceToolBridge.MAX_SNIPPET_CHARS, snippet.length)
    }

    @Test
    fun chatToolsAreOfferedOnlyWhenTheOtherChatsAreVisible() {
        val names = bridge().declarations().map { it.name }
        assertTrue(VoiceToolBridge.FIND_CHAT !in names)
        assertTrue(VoiceToolBridge.READ_CHAT !in names)

        val withChats = bridge(chats = FakeChats()).declarations().map { it.name }
        assertTrue(VoiceToolBridge.FIND_CHAT in withChats)
        assertTrue(VoiceToolBridge.READ_CHAT in withChats)
    }

    @Test
    fun findChatNamesTheFirstMatchAndCountsTheRest() = runTest {
        val outcome = bridge(chats = FakeChats()).handle(call(VoiceToolBridge.FIND_CHAT, "query" to "the build"))
        assertEquals("ok", outcome.status())
        assertEquals("\"fixing the android build\" There are 2 more.", outcome.result["summary"]!!.jsonPrimitive.content)
        val hits = outcome.result["chats"]!!.let { it as JsonArray }
        assertEquals(3, hits.size)
        assertEquals("s-1", hits[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun findChatSaysWhenNothingMatches() = runTest {
        val outcome = bridge(chats = FakeChats(hits = emptyList()))
            .handle(call(VoiceToolBridge.FIND_CHAT, "query" to "unicycles"))
        assertEquals("empty", outcome.status())
    }

    @Test
    fun readChatReadsTheChatItFound() = runTest {
        val outcome = bridge(chats = FakeChats(reply = "  the build is fixed on dev  "))
            .handle(call(VoiceToolBridge.READ_CHAT, "chat" to "the build"))
        assertEquals("ok", outcome.status())
        assertEquals("the build is fixed on dev", outcome.result["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun readChatSaysWhenTheChatHasNoAnswer() = runTest {
        val outcome = bridge(chats = FakeChats(reply = null))
            .handle(call(VoiceToolBridge.READ_CHAT, "chat" to "the build"))
        assertEquals("empty", outcome.status())
        assertTrue(outcome.result["message"]!!.jsonPrimitive.content.contains("no answer"))
    }

    @Test
    fun unknownToolGetsAnAnswerRatherThanSilence() = runTest {
        val outcome = bridge().handle(call("delete_everything"))
        assertEquals("error", outcome.status())
        assertEquals("delete_everything", outcome.result["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun theCallOnlyToolsAreDeclaredOnlyWhenTheChatBacksThem() {
        val plain = bridge().declarations().map { it.name }
        assertTrue(VoiceToolBridge.SET_ASK_FIRST !in plain)
        assertTrue(VoiceToolBridge.END_CONVERSATION !in plain)

        val backed = bridge(setAskFirst = { true }, endCall = {}).declarations().map { it.name }
        assertTrue(VoiceToolBridge.SET_ASK_FIRST in backed)
        assertTrue(VoiceToolBridge.END_CONVERSATION in backed)
    }

    @Test
    fun askFirstFlipsTheChatAndSaysWhichWayItIs() = runTest {
        var asked: Boolean? = null
        val bridge = bridge(setAskFirst = { on -> asked = on; on })

        val outcome = bridge.handle(call(VoiceToolBridge.SET_ASK_FIRST, "ask" to "false"))

        assertEquals(false, asked)
        assertEquals("ok", outcome.status())
        assertEquals("false", outcome.result["ask_first"]!!.jsonPrimitive.content)
        assertTrue(outcome.result["message"]!!.jsonPrimitive.content.contains("won't ask"))
    }

    @Test
    fun askFirstRefusesWhenItCannotReadTheWish() = runTest {
        val outcome = bridge(setAskFirst = { true }).handle(call(VoiceToolBridge.SET_ASK_FIRST, "ask" to "maybe"))
        assertEquals("error", outcome.status())
    }

    @Test
    fun endingTheCallRunsTheEndHookOnce() = runTest {
        var ended = 0
        val outcome = bridge(endCall = { ended++ }).handle(call(VoiceToolBridge.END_CONVERSATION))
        assertEquals("ending", outcome.status())
        assertEquals(1, ended)
    }

    @Test
    fun theScreenAndRingToolsAreDeclaredOnlyWhenWired() {
        val plain = bridge().declarations().map { it.name }
        assertTrue(VoiceToolBridge.SHOW_ON_SCREEN !in plain)
        assertTrue(VoiceToolBridge.CALL_ME_WHEN_DONE !in plain)

        val wired = bridge(showCard = { _, _ -> }, whenDone = { true }).declarations().map { it.name }
        assertTrue(VoiceToolBridge.SHOW_ON_SCREEN in wired)
        assertTrue(VoiceToolBridge.CALL_ME_WHEN_DONE in wired)
    }

    @Test
    fun showOnScreenDrawsTheCardItWasGiven() = runTest {
        var drawn: Pair<String, String>? = null
        val outcome = bridge(showCard = { title, body -> drawn = title to body })
            .handle(call(VoiceToolBridge.SHOW_ON_SCREEN, "title" to "Tomorrow", "body" to "Maths 6am\nPhysics 8am"))

        assertEquals("ok", outcome.status())
        assertEquals("Tomorrow", drawn?.first)
        assertEquals("Maths 6am\nPhysics 8am", drawn?.second)
    }

    @Test
    fun showOnScreenRefusesAnEmptyCard() = runTest {
        val outcome = bridge(showCard = { _, _ -> })
            .handle(call(VoiceToolBridge.SHOW_ON_SCREEN, "title" to "Tomorrow", "body" to "   "))
        assertEquals("error", outcome.status())
    }

    @Test
    fun callMeWhenDoneArmsTheRing() = runTest {
        var reason: String? = null
        val outcome = bridge(whenDone = { words -> reason = words; true })
            .handle(call(VoiceToolBridge.CALL_ME_WHEN_DONE, "reason" to "The notes are ready."))

        assertEquals("armed", outcome.status())
        assertEquals("The notes are ready.", reason)
    }

    @Test
    fun callMeWhenDoneSaysWhenNothingIsRunning() = runTest {
        val outcome = bridge(whenDone = { false }).handle(call(VoiceToolBridge.CALL_ME_WHEN_DONE))
        assertEquals("nothing_running", outcome.status())
    }

    @Test
    fun callMeWhenDoneUsesItsOwnReasonWhenNoneWasGiven() = runTest {
        var reason: String? = null
        bridge(whenDone = { words -> reason = words; true }).handle(call(VoiceToolBridge.CALL_ME_WHEN_DONE))
        assertEquals(VoiceToolBridge.DEFAULT_CALL_REASON, reason)
    }

    @Test
    fun openChatIsOfferedOnlyWithAChatListToSearch() {
        assertTrue(VoiceToolBridge.OPEN_CHAT !in bridge(openChat = { _, _ -> }).declarations().map { it.name })
        val wired = bridge(chats = FakeChats(), openChat = { _, _ -> }).declarations().map { it.name }
        assertTrue(VoiceToolBridge.OPEN_CHAT in wired)
    }

    @Test
    fun openChatBringsUpTheChatItFound() = runTest {
        var opened: Pair<String, String>? = null
        val outcome = bridge(chats = FakeChats(), openChat = { id, title -> opened = id to title })
            .handle(call(VoiceToolBridge.OPEN_CHAT, "chat" to "the build"))

        assertEquals("opening", outcome.status())
        assertEquals("s-1" to "fixing the android build", opened)
    }

    @Test
    fun openChatSaysWhenNothingMatches() = runTest {
        val outcome = bridge(chats = FakeChats(hits = emptyList()), openChat = { _, _ -> })
            .handle(call(VoiceToolBridge.OPEN_CHAT, "chat" to "unicycles"))
        assertEquals("empty", outcome.status())
    }
}
