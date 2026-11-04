package dev.hermeskotlin.core.voice.gemini

import dev.hermeskotlin.core.voice.ChatLookup
import dev.hermeskotlin.core.voice.JobStart
import dev.hermeskotlin.core.voice.CallLater
import dev.hermeskotlin.core.voice.VoiceJobs
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.SendOutcome
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.voice.LiveCall
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Makes the Gemini Live call Kyllene's voice chat runs on, or null when this host can't mint a token. */
fun interface GeminiLiveCalls {
    suspend fun create(
        session: ChatSession?,
        gateway: GatewayUrl,
        profile: String?,
        jobs: VoiceJobs?,
        chats: ChatLookup?,
    ): LiveCall?
}

/**
 * What a live call may use beyond the bare model, each its own switch in Settings → Voice, as Conduit has them:
 * Google Search for quick facts, this host's memory for `recall_memory`, and the user's personality in the
 * instructions. All three on unless the user turned one off.
 */
data class VoiceOptions(
    val search: Boolean = true,
    val memory: Boolean = true,
    val personality: Boolean = true,
)

/** [GeminiLiveCalls] over the shared HTTP client. */
class GeminiLiveCallsImpl(
    private val client: HttpClient,
    /** Whose voice the call speaks with (Settings → Voice). */
    private val settings: SettingsStore,
    /** Rings the phone when the work finishes, for "call me when it's done"; null where there's no phone. */
    private val callLater: CallLater? = null,
) : GeminiLiveCalls {
    override suspend fun create(
        session: ChatSession?,
        gateway: GatewayUrl,
        profile: String?,
        jobs: VoiceJobs?,
        chats: ChatLookup?,
    ): LiveCall? {
        val status = GeminiLiveApi(client).status(gateway, profile)
        if (status !is ApiResult.Success || !status.value.available) return null
        val audio = createLiveAudio() ?: return null
        val current = settings.settings.value
        val voice = current?.geminiVoice?.takeIf { it.isNotBlank() }
        val options = VoiceOptions(
            search = current?.voiceSearch ?: true,
            memory = current?.voiceMemory ?: true,
            personality = current?.voicePersonality ?: true,
        )
        return GeminiLiveAdapter(client, gateway, profile, audio, voice, session, jobs, chats, callLater, options)
    }
}

/**
 * Gemini Live wearing Kyllene's voice-chat shape.
 *
 * The app's voice chat (`LiveConversation`) speaks the GPT-Live relay's dialect: it asks the voice to hand a
 * request over (`session.delegation.created`), then feeds the answer back as `session.commentary.append`. This
 * adapter translates that dialect to and from Google's wire protocol, so the same voice screen, the same
 * streaming replies, the same spoken approvals and the same Hermes integration work unchanged.
 */
private class GeminiLiveAdapter(
    private val client: HttpClient,
    private val gateway: GatewayUrl,
    private val profile: String?,
    private val audio: LiveAudio,
    /** The prebuilt voice the call speaks with; null leaves Google's own default. */
    private val voice: String?,
    /** The chat this call belongs to; null for a call with nothing behind it (no tools beyond the chat ones). */
    private val session: ChatSession?,
    /** Background work this call can start and hear about; null when the app has no registry. */
    private val jobs: VoiceJobs? = null,
    /** The user's other chats, for find_chat / read_chat; null when this call can't see them. */
    private val chats: ChatLookup? = null,
    /** Rings this phone when the chat's work ends; null on a host with no phone to ring. */
    private val callLater: CallLater? = null,
    /** What the call may use beyond the bare model: Conduit's switches, read from this app's settings. */
    private val options: VoiceOptions = VoiceOptions(),
) : LiveCall {
    private val api = GeminiLiveApi(client)

    /** The memory block Hermes injects into its own prompt, fetched once per call. */
    private var memory: String? = null
    private var memoryLoaded = false

    private suspend fun loadMemory(): String? {
        if (!memoryLoaded) {
            memory = api.memoryContext(gateway, profile)
            memoryLoaded = true
        }
        return memory
    }

    /** The tools the voice may use on this call: none without a chat to act on. */
    private val tools: VoiceToolBridge? = session?.let { chat ->
        VoiceToolBridge(
            lastReply = {
                chat.state.value.messages.filterIsInstance<ChatMessage.Assistant>().lastOrNull()?.text
            },
            steer = { words -> runCatching { chat.steer(words) }.getOrNull() != SendOutcome.NotSent },
            stop = { runCatching { chat.interrupt() }.getOrDefault(emptyList()) },
            jobs = { snapshot(chat) },
            // Both routes live on the host's `conduit_push` plugin; a missing route is an honest "unavailable".
            recall = { query -> if (!options.memory) null else loadMemory()?.let { VoiceToolBridge.memoryMatches(it, query) } },
            search = { query -> api.webSearch(gateway, profile, query) },
            startJob = jobs?.let { registry -> { request -> registry.start(request, profile, gateway) } },
            jobList = { jobs?.jobs?.value.orEmpty() },
            cancelJobs = { id -> jobs?.cancel(id).orEmpty() },
            chats = chats,
            setAskFirst = { ask -> chat.setAskFirst(ask) },
            endCall = { endAfterGoodbye() },
            showCard = { title, body -> sendCard(title, body) },
            whenDone = { reason -> ringWhenDone(chat, reason) },
            openChat = { id, title -> sendOpenChat(id, title) },
        )
    }

    /** A card for the call's screen, as the event the call UI reads. */
    private fun sendCard(title: String, body: String) {
        scope.launch {
            runCatching {
                frames.send(
                    buildJsonObject {
                        put("type", "screen.card")
                        put("title", title)
                        put("body", body)
                    }.toString(),
                )
            }
        }
    }

    /** Asks the call's UI to bring another chat up; the call ends there. */
    private fun sendOpenChat(id: String, title: String) {
        scope.launch {
            runCatching {
                frames.send(
                    buildJsonObject {
                        put("type", "screen.open_chat")
                        put("chat", id)
                        put("title", title)
                    }.toString(),
                )
            }
        }
    }

    /** Arms the ring for when this chat's work ends. False when a ring can't be promised. */
    private suspend fun ringWhenDone(chat: ChatSession, reason: String): Boolean {
        val later = callLater ?: return false
        val state = chat.state.value
        val chatId = state.storedSessionId ?: return false
        return later.whenDone(chatId, state.title, reason, running = state.running)
    }

    /** Hangs up once the voice has said goodbye: the frame the call's UI reads as "ended", then the socket. */
    private fun endAfterGoodbye() {
        scope.launch {
            // The reply is owed to the user: hang up once the voice has said it and gone quiet, not on a timer.
            val asked = System.currentTimeMillis()
            val deadline = asked + GOODBYE_MAX_MS
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (lastSpoken >= asked && now - lastSpoken >= GOODBYE_QUIET_MS) break
                delay(GOODBYE_POLL_MS)
            }
            runCatching { frames.send("""{"type":"session.closed","reason":"closed"}""") }
            close()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = Channel<String>(Channel.BUFFERED)

    @Volatile private var transport: GeminiLiveTransport? = null
    private var pump: Job? = null
    private var receiver: Job? = null
    private var announcer: Job? = null
    @Volatile private var lastSpoken = 0L
    /** Calls Gemini withdrew (the user spoke over a lookup): their answer must not go out afterwards. */
    private val withdrawn = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** When the user last said something: what an acknowledgement is measured against. */
    @Volatile private var lastHeard = 0L

    /** The user hung up: no session is opened again. */
    @Volatile private var hungUp = false
    /** What Google needs to take this call over after it rotates the session. */
    @Volatile private var resumeHandle: String? = null
    /** The call's own words, kept short, for the session that takes over after a rotation. */
    private val continuity = CallContinuity(SAID_CHARS, REJOIN_CHARS)
    private var level = 0f
    private var muted = false

    /** The open function call per delegation id; a reply to it is what the voice says next. */
    private val openCalls = mutableMapOf<String, GeminiLiveProtocol.FunctionCall>()
    private var delegationSeq = 0

    override val events: ReceiveChannel<String> get() = frames

    override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
        audio.start { value -> level = value }
        // The microphone is read once for the whole call: each session's socket takes its frames while it is up,
        // so a reconnect doesn't restart it (and doesn't ask for the microphone again).
        pump = scope.launch(Dispatchers.IO) {
            while (true) {
                val frame = audio.read() ?: break
                val live = transport ?: continue
                // A phone that can't cancel the speaker's echo would hear the voice through the microphone and
                // cut it off for ever: there the microphone is held while it plays, as Conduit does.
                if (!audio.cancelsEcho && System.currentTimeMillis() - lastSpoken < ECHO_TAIL_MS) continue
                runCatching { live.send(GeminiLiveProtocol.audioMessage(frame)) }
            }
        }
        announcer = announceFinishedWork()
        // The first session has to be up before this returns: the screen leaves "Connecting" on it, and reads the
        // event stream only afterwards.
        val first = openSession(rejoining = false)
        if (first == null) {
            frames.trySend(error("Couldn't start the live call."))
            frames.close()
            return
        }
        // The rest of the call looks after itself: Google's rotations and dropped sockets are met with a new
        // session, so the user keeps their call.
        scope.launch(Dispatchers.IO) { supervise(first) }
    }

    /** Keeps a session open for as long as the call lasts: each one that ends is followed by another. */
    private suspend fun supervise(first: CompletableDeferred<Unit>) {
        var ended: CompletableDeferred<Unit>? = first
        var misses = 0
        while (!hungUp) {
            ended?.await() ?: return
            ended = null
            if (hungUp) return
            delay(RECONNECT_PAUSE_MS)
            while (ended == null && !hungUp) {
                val next = openSession(rejoining = true)
                if (next != null) {
                    ended = next
                    break
                }
                if (++misses > MAX_RECONNECTS) {
                    frames.trySend(error("The call lost its connection."))
                    frames.close()
                    return
                }
                delay(RECONNECT_BACKOFF_MS * misses)
            }
            misses = 0
        }
    }

    /** One Gemini session. Null when it couldn't be opened at all; otherwise it completes when the session ends. */
    private suspend fun openSession(rejoining: Boolean): CompletableDeferred<Unit>? {
        val token = when (val result = api.token(gateway, profile)) {
            is ApiResult.Success -> result.value
            else -> return null
        }
        val persona = if (options.personality) api.personality(gateway, profile) else null
        val socket = KtorGeminiLiveTransport(client, scope)
        try {
            socket.connect(token.connectionUrl(), token.token)
        } catch (e: Exception) {
            if (!rejoining) frames.trySend(error(e.message ?: "Could not reach Gemini."))
            return null
        }
        transport = socket
        socket.send(
            GeminiLiveProtocol.setupMessage(
                model = token.model,
                systemInstruction = instructions(persona),
                functions = listOf(ASK_HERMES) + (tools?.declarations() ?: emptyList()),
                googleSearch = options.search,
                voice = voice ?: token.voice,
                resumptionHandle = resumeHandle,
            ),
        )
        // A fresh session knows nothing of the call so far: told quietly, before anything else goes out.
        if (rejoining) rejoin(socket)
        val done = CompletableDeferred<Unit>()
        receiver = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val frame = socket.receive() ?: break
                    for (event in GeminiLiveProtocol.decode(frame)) {
                        // A tool the app mishandles must cost that one turn, not the whole call.
                        runCatching { handle(event) }.onFailure { cause ->
                            frames.trySend(error(cause.message ?: "Voice event failed."))
                        }
                    }
                }
            } finally {
                if (transport === socket) transport = null
                done.complete(Unit)
            }
        }
        return done
    }

    /** What a session that just took over is told: the call's recent words, and what Hermes is still doing. */
    private suspend fun rejoin(socket: GeminiLiveTransport) {
        val running = runCatching { jobs?.running().orEmpty().map { it.title } }.getOrDefault(emptyList())
        runCatching { socket.send(GeminiLiveProtocol.contextNoteMessage(continuity.rejoinNote(running))) }
    }

    /** Ends this session so the loop opens the next one with [resumeHandle]. */
    private fun rotate() {
        val socket = transport ?: return
        transport = null
        scope.launch(Dispatchers.IO) { runCatching { socket.close() } }
    }

    private suspend fun handle(event: GeminiLiveProtocol.ServerEvent) {
        when (event) {
            GeminiLiveProtocol.ServerEvent.SetupComplete ->
                frames.send("""{"type":"session.started"}""")
            is GeminiLiveProtocol.ServerEvent.Audio -> {
                audio.play(event.pcm, event.sampleRate)
                lastSpoken = System.currentTimeMillis()
            }
            GeminiLiveProtocol.ServerEvent.Interrupted -> {
                audio.flush()
                // The turn it was reading is over: whatever it says next starts clean.
                markup.reset()
            }
            is GeminiLiveProtocol.ServerEvent.InputTranscription -> {
                lastHeard = System.currentTimeMillis()
                continuity.note("them", event.text)
                frames.send(transcript("session.input_transcript.delta", event.text))
            }
            is GeminiLiveProtocol.ServerEvent.OutputTranscription -> {
                continuity.note("you", event.text)
                // A tool call read out loud is cut off at the markup: nothing ran, and the arguments would be read
                // next. The model is asked for the call it meant, once the line is over.
                if (markup.feed(event.text)) {
                    audio.flush()
                    transport?.let { socket ->
                        scope.launch { runCatching { socket.send(GeminiLiveProtocol.textTurnMessage(SPOKEN_CALL_PROMPT)) } }
                    }
                }
                frames.send(transcript("session.output_transcript.delta", event.text))
            }
            is GeminiLiveProtocol.ServerEvent.ToolCall -> event.calls.forEach(::dispatch)
            is GeminiLiveProtocol.ServerEvent.ToolCallCancellation -> withdrawn.addAll(event.ids)
            GeminiLiveProtocol.ServerEvent.TurnComplete -> {
                markup.reset()
                continuity.turnEnded()
            }
            is GeminiLiveProtocol.ServerEvent.GoAway -> {
                // Google is about to close this session: the next one is opened with the handle, at once.
                val left = event.timeLeftSeconds ?: 0.0
                if (left <= GOAWAY_SECONDS) rotate()
            }
            is GeminiLiveProtocol.ServerEvent.ResumptionUpdate ->
                if (event.resumable) event.handle?.takeIf { it.isNotBlank() }?.let { resumeHandle = it }
            else -> Unit
        }
    }

    /** A tool the model called: the chat's own delegation, or one the bridge answers directly. */
    private fun dispatch(call: GeminiLiveProtocol.FunctionCall) {
        if (withdrawn.remove(call.id)) return
        if (call.name == ASK_HERMES.name) return delegate(call)
        val bridge = tools ?: return answer(call, failure("That isn't something I can do on this call."))
        scope.launch {
            val outcome = bridge.handle(call)
            val socket = transport ?: return@launch
            runCatching {
                socket.send(
                    GeminiLiveProtocol.toolResponseMessage(
                        call.id, call.name, outcome.result, scheduling = outcome.scheduling,
                    ),
                )
            }
        }
    }

    private fun answer(call: GeminiLiveProtocol.FunctionCall, result: JsonObject) {
        if (withdrawn.remove(call.id)) return
        val socket = transport ?: return
        scope.launch {
            runCatching {
                socket.send(GeminiLiveProtocol.toolResponseMessage(call.id, call.name, result))
            }
        }
    }

    /**
     * Says a background job out loud when it ends, for as long as this call is up.
     *
     * The voice promises "I'll tell you when it's done" the moment it starts one, so something has to keep that
     * promise: every [ANNOUNCE_MS] the registry is brought up to date and anything that ended is sent in as a
     * line the model reads back in a sentence. Each job is announced once ([VoiceJobs.takeFinished]).
     */
    private fun announceFinishedWork(): Job? {
        val registry = jobs ?: return null
        return scope.launch(Dispatchers.IO) {
            while (true) {
                delay(ANNOUNCE_MS)
                runCatching { registry.refresh() }
                val finished = runCatching { registry.takeFinished() }.getOrDefault(emptyList())
                for (job in finished) {
                    val line = buildString {
                        append("(Background job finished: \"")
                        append(job.title)
                        append("\". ")
                        job.result?.take(ANNOUNCE_RESULT_CHARS)?.let { append("Hermes answered: $it ") }
                        append("Tell the user in one short sentence.)")
                    }
                    // The socket may have been replaced by a reconnect in between: the line goes to whichever is
                    // up now, and is retried by the next announcement if there is none.
                    val live = transport ?: continue
                    runCatching { live.send(GeminiLiveProtocol.textTurnMessage(line)) }
                }
            }
        }
    }

    /** What this chat has in flight, read the way the chat screen reads it. */
    private suspend fun snapshot(chat: ChatSession): JobSnapshot {
        val state = chat.state.value
        val assistant = state.messages.filterIsInstance<ChatMessage.Assistant>().lastOrNull()
        val tool = assistant?.tools?.lastOrNull { it.running }
        return JobSnapshot(
            running = when {
                !state.running -> null
                tool != null -> "running ${tool.detail ?: tool.name}"
                else -> "writing a reply"
            },
            queued = state.messages.filterIsInstance<ChatMessage.User>()
                .filter { it.queued }
                .map { it.text.take(120) },
            subagents = state.subagents
                .filter { it.status == SubagentStatus.Running }
                .map { it.goal.ifBlank { "a subagent" }.take(120) },
            // One gateway round trip, and only when the voice asks: a failure means "nothing to report", not an error.
            processes = runCatching { chat.processes() }.getOrNull().orEmpty()
                .filter { it.running }
                .map { it.command.take(120) },
        )
    }

    private fun failure(message: String) = buildJsonObject {
        put("status", "error")
        put("message", message)
    }

    /**
     * The voice wants Hermes. Kyllene's voice chat takes over from here: it sends the request as a turn in the
     * chat and streams the reply back through [send].
     */
    private fun delegate(call: GeminiLiveProtocol.FunctionCall) {
        val id = "gem-${++delegationSeq}"
        scope.launch {
            // Answer the call at once, as Conduit does: the voice gets to say "let me check" and keep the line
            // open, instead of going quiet until Hermes's first words arrive.
            transport?.let { socket ->
                runCatching {
                    socket.send(
                        GeminiLiveProtocol.toolResponseMessage(
                            call.id,
                            call.name,
                            buildJsonObject {
                                put("status", "sent")
                                put("message", "Hermes has it. Say one short line now, then stay quiet until the answer arrives.")
                            },
                            scheduling = "WHEN_IDLE",
                        ),
                    )
                }
            }
            // It sometimes stays silent instead of saying the line it was asked for, and the call then sounds
            // dead while Hermes works. A second later, if nothing was said, it is told once, plainly.
            val asked = System.currentTimeMillis()
            val requestedAt = minOf(asked, lastHeard)
            scope.launch {
                delay(ACK_GRACE_MS)
                // Anything said since the user asked is the line that was asked for.
                if (lastSpoken <= requestedAt) {
                    transport?.let { socket -> runCatching { socket.send(GeminiLiveProtocol.textTurnMessage(ACK_PROMPT)) } }
                }
            }
            frames.send(
                buildJsonObject {
                    put("type", "session.delegation.created")
                    put("delegation", buildJsonObject { put("id", id) })
                }.toString(),
            )
        }
    }

    override fun send(event: String): Boolean {
        val root = runCatching { JSON.parseToJsonElement(event).jsonObject }.getOrNull() ?: return false
        val type = (root["type"] as? JsonPrimitive)?.contentOrNull ?: return false
        val socket = transport ?: return false
        return when (type) {
            "session.commentary.append" -> {
                val delegation = (root["delegation_id"] as? JsonPrimitive)?.contentOrNull
                val content = (root["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                scope.launch {
                    // The open call is answered once, and the voice keeps going: the reply is spoken as it streams
                    // rather than held back until Hermes finishes.
                    val call = delegation?.let { openCalls.remove(it) }
                    if (call != null) {
                        runCatching {
                            socket.send(
                                GeminiLiveProtocol.toolResponseMessage(
                                    call.id,
                                    call.name,
                                    buildJsonObject {
                                        put("status", "sent")
                                        put("message", "Hermes is answering; the rest is spoken as it arrives.")
                                    },
                                    scheduling = "WHEN_IDLE",
                                ),
                            )
                        }
                    }
                    if (content.isNotEmpty()) {
                        runCatching { socket.send(GeminiLiveProtocol.textTurnMessage(content)) }
                    }
                }
                true
            }
            "session.thinking.append" -> true
            "session.instructions.append" -> {
                val content = (root["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (content.isNotEmpty()) {
                    scope.launch {
                        runCatching { socket.send(GeminiLiveProtocol.contextNoteMessage(content)) }
                    }
                }
                true
            }
            "session.input_audio.mute" -> {
                setMuted(true)
                true
            }
            "session.input_audio.unmute" -> {
                setMuted(false)
                true
            }
            "session.close" -> {
                close()
                true
            }
            else -> false
        }
    }

    override fun speaking(): Boolean = System.currentTimeMillis() - lastSpoken < SPEAKING_WINDOW_MS

    override fun micLevel(): Float = if (muted) 0f else level

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        audio.setMuted(muted)
    }

    override fun close() {
        hungUp = true
        pump?.cancel()
        pump = null
        receiver?.cancel()
        receiver = null
        announcer?.cancel()
        announcer = null
        val socket = transport
        transport = null
        scope.cancel()
        if (socket != null) CoroutineScope(Dispatchers.IO).launch { runCatching { socket.close() } }
        audio.stop()
        frames.close()
    }

    private fun transcript(type: String, text: String): String = buildJsonObject {
        put("type", type)
        put("delta", text)
        put("start_ms", 0)
        put("end_ms", 0)
    }.toString()

    private fun error(message: String): String = buildJsonObject {
        put("type", "error")
        put("error", buildJsonObject { put("message", message) })
    }.toString()

    /**
     * What the voice is told, written the way Conduit writes it: answer the conversation yourself, and spend a
     * Hermes round-trip only on what only Hermes knows. Kyllene first told it to route everything — greetings, the
     * time, the weather, "how are you" — through ask_hermes, which put two to twenty seconds in front of every
     * single thing the user said.
     */
    private fun instructions(persona: String?): String = buildString {
        append("You are the voice of the user's Hermes agent, talking with them on their phone. This is speech, not ")
        append("text: talk naturally, in full spoken sentences.\n\n")
        append("Before you answer, ask yourself one question: does this depend on anything that is theirs — their ")
        append("apps, projects, code, files, chats, memory, jobs, calendar, accounts, phone, or this call? If it does, ")
        append("call ask_hermes, always, even when it sounds like news or a general question: \"what's new in my app\", ")
        append("\"how is that release going\", \"what did I ask about earlier\" are theirs, not the world's.\n\n")
        append("If it depends on nothing of theirs — how you are, what a word means, and, through Google Search, ")
        append("weather, scores, prices, public releases of other people's products and other facts about the world — ")
        append("answer it yourself, once, in a sentence or two.\n\n")
        append("When you truly cannot tell which it is, ask_hermes: it can search too, so a wrong guess costs them a ")
        append("wrong answer while asking costs a few seconds. Pass the whole request in their words. Never guess a ")
        append("personal fact: ask Hermes, or say you don't know.\n\n")
        append("Just before ask_hermes, say one short line that names what you're looking up, in their words — ")
        append("\"I'm talking about the 0.9.2 update — checking on that release now\", not a bare \"one second\". ")
        append("Say it once: never repeat a progress line, never invent what Hermes is doing. When the answer comes ")
        append("back, give it in a sentence or two.\n\n")
        append("Beyond ask_hermes you have tools of your own, all of them answered on this phone: start_job for work ")
        append("that runs in the background (say you've started it, then carry on), list_jobs and read_last_reply ")
        append("when they ask about it, recall_memory for what Hermes remembers, find_chat and read_chat for their ")
        append("earlier conversations, show_on_screen for anything better seen than heard — a table, a list, a place — ")
        append("set_ask_first when they say to stop asking, and call_me_when_done to ring them when the work ends.\n\n")
        append("Never speak while they are speaking: if they interrupt, stop and listen. If you have nothing useful ")
        append("to add, stay quiet rather than filling the pause. You speak first only to greet them, to answer ")
        append("\"can you hear me\", or to ask them to repeat something.\n\n")
        append("You are Hermes, not a separate assistant: never call yourself an AI and never say what you can't do. ")
        append("Keep every answer spoken — no code, no URLs read out, no tables, no markdown.")
        if (!persona.isNullOrBlank()) {
            append("\n\nSound like this, but never answer a fact from it:\n")
            append(persona.take(PERSONA_CHARS))
        }
    }

    /** The voice's own words this turn, watched for a tool call it reads out instead of running. */
    private val markup = SpokenMarkupGuard()

    private companion object {
        /** How long after the voice speaks the microphone stays held on a phone with no echo canceller. */
        const val ECHO_TAIL_MS = 350L
        /** Sessions opened in a row before the call gives up. */
        const val MAX_RECONNECTS = 3
        const val RECONNECT_BACKOFF_MS = 800L
        /** A breath between a session ending and the next one, so Google isn't hammered. */
        const val RECONNECT_PAUSE_MS = 250L
        /** Google says how long a session has left before closing it; this close is taken over at once. */
        const val GOAWAY_SECONDS = 20.0
        /** The last stretch of the call a fresh session is told about. */
        const val REJOIN_CHARS = 900
        const val SAID_CHARS = 2_000
        /** A goodbye waits for the reply it is owed, but not for ever. */
        const val GOODBYE_MAX_MS = 12_000L
        const val GOODBYE_QUIET_MS = 800L
        const val GOODBYE_POLL_MS = 50L

        /** Hermes is working and the voice said nothing: asked, once, for the one line it promised. */
        const val ACK_GRACE_MS = 2_600L
        const val ACK_PROMPT =
            "[Kyllene: Hermes is working and the line has gone quiet — say one short line now that names what " +
            "you're looking up, in their words (\"Checking on that release now\"), not a bare \"On it.\", " +
                "then stay quiet until the answer arrives.]"
        const val SPOKEN_CALL_PROMPT =
            "[Kyllene: your last reply read a tool call out loud instead of calling it, so nothing ran. Kyllene cut " +
                "the voice off there. Don't speak tool names or arguments: if you meant to use a tool, call it now " +
                "as a function, and don't say it's done until its result comes back. Otherwise just carry on.]"

        const val SPEAKING_WINDOW_MS = 400L
        const val MAX_ANSWER_CHARS = 4_000
        const val PERSONA_CHARS = 6_000

        /** How often a live call checks whether a background job it started has ended. */
        const val ANNOUNCE_MS = 5_000L
        const val ANNOUNCE_RESULT_CHARS = 300

        /** Time the voice gets to say goodbye after `end_conversation` before the call is closed. */

        val JSON = Json { ignoreUnknownKeys = true }

        val ASK_HERMES = GeminiLiveProtocol.FunctionDeclaration(
            name = "ask_hermes",
            description = "Ask Hermes — the agent behind this voice — to answer or do something. It has the " +
                "user's memory, their data, tools and the whole conversation. Use it for anything that depends on " +
                "what is theirs — their apps, projects, code, files, chats, memory, jobs, calendar, accounts, this " +
                "phone, this call — including 'what's new in X' when X is theirs, and for anything to start or " +
                "change. Google Search is only for public facts about the world. When unsure, use this.",
            // NON_BLOCKING, as Conduit declares start_job: the call stays open, so the voice can say "let me check"
            // and keep talking while Hermes works instead of going silent.
            behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
            parameters = buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("request", buildJsonObject {
                        put("type", "STRING")
                        put("description", "What the user asked, in their own words.")
                    })
                })
                put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("request")) })
            },
        )
    }
}
