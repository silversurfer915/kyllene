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
        val voice = settings.settings.value?.geminiVoice?.takeIf { it.isNotBlank() }
        return GeminiLiveAdapter(client, gateway, profile, audio, voice, session, jobs, chats, callLater)
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
            recall = { query -> loadMemory()?.let { VoiceToolBridge.memoryMatches(it, query) } },
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
            delay(GOODBYE_MS)
            runCatching { frames.send("""{"type":"session.closed","reason":"closed"}""") }
            close()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = Channel<String>(Channel.BUFFERED)

    private var transport: GeminiLiveTransport? = null
    private var pump: Job? = null
    private var lastSpoken = 0L
    private var level = 0f
    private var muted = false

    /** The open function call per delegation id; a reply to it is what the voice says next. */
    private val openCalls = mutableMapOf<String, GeminiLiveProtocol.FunctionCall>()
    private var delegationSeq = 0

    override val events: ReceiveChannel<String> get() = frames

    override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
        val token = when (val result = api.token(gateway, profile)) {
            is ApiResult.Success -> result.value
            else -> {
                frames.send(error("Gemini Live could not start on this host."))
                frames.close()
                return
            }
        }
        val persona = api.personality(gateway, profile)
        val socket = KtorGeminiLiveTransport(client, scope)
        transport = socket
        try {
            socket.connect(token.connectionUrl(), token.token)
        } catch (e: Exception) {
            frames.send(error(e.message ?: "Could not reach Gemini."))
            frames.close()
            return
        }
        socket.send(
            GeminiLiveProtocol.setupMessage(
                model = token.model,
                systemInstruction = instructions(persona),
                functions = listOf(ASK_HERMES) + (tools?.declarations() ?: emptyList()),
                googleSearch = true,
                voice = voice ?: token.voice,
            ),
        )
        audio.start { value -> level = value }
        announceFinishedWork(socket)
        pump = scope.launch(Dispatchers.IO) {
            while (true) {
                val frame = audio.read() ?: break
                runCatching { socket.send(GeminiLiveProtocol.audioMessage(frame)) }.onFailure { break }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                val frame = socket.receive() ?: break
                for (event in GeminiLiveProtocol.decode(frame)) {
                    // A tool the app mishandles must cost that one turn, not the whole call: this loop closing is
                    // what "the voice went silent" looks like from the phone.
                    runCatching { handle(event) }.onFailure { cause ->
                        frames.trySend(error(cause.message ?: "Voice event failed."))
                    }
                }
            }
            frames.close()
        }
    }

    private suspend fun handle(event: GeminiLiveProtocol.ServerEvent) {
        when (event) {
            GeminiLiveProtocol.ServerEvent.SetupComplete ->
                frames.send("""{"type":"session.started"}""")
            is GeminiLiveProtocol.ServerEvent.Audio -> {
                audio.play(event.pcm, event.sampleRate)
                lastSpoken = System.currentTimeMillis()
            }
            GeminiLiveProtocol.ServerEvent.Interrupted -> audio.flush()
            is GeminiLiveProtocol.ServerEvent.InputTranscription ->
                frames.send(transcript("session.input_transcript.delta", event.text))
            is GeminiLiveProtocol.ServerEvent.OutputTranscription -> {
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
            GeminiLiveProtocol.ServerEvent.TurnComplete -> markup.reset()
            else -> Unit
        }
    }

    /** A tool the model called: the chat's own delegation, or one the bridge answers directly. */
    private fun dispatch(call: GeminiLiveProtocol.FunctionCall) {
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
    private fun announceFinishedWork(socket: GeminiLiveTransport) {
        val registry = jobs ?: return
        scope.launch(Dispatchers.IO) {
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
                    runCatching { socket.send(GeminiLiveProtocol.textTurnMessage(line)) }
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
            scope.launch {
                delay(ACK_GRACE_MS)
                // Anything said after the call went out is the line that was asked for.
                if (lastSpoken <= asked) {
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
        pump?.cancel()
        pump = null
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

    private fun instructions(persona: String?): String = buildString {
        append("You are Hermes's voice on the user's phone. You are not an assistant of your own: you have no ")
        append("facts, no memory and nothing of your own to answer with.\n\n")
        append("Every question or request — their name, where they live, what you remember, the time, the ")
        append("weather, anything at all — is answered only by calling ask_hermes with it and then saying its ")
        append("answer back in a sentence. Never answer from your own knowledge. Never guess.\n\n")
        append("You speak first only for a greeting, for \"can you hear me\", or to ask them to repeat ")
        append("something.\n\n")
        append("You are Hermes, not a separate assistant. Never call yourself an AI, never say what you can or ")
        append("cannot do, and never answer a question about this call — who rang whom, what you are, whether you ")
        append("can call — from your own head: those go to ask_hermes like anything else, and if you don't know, ")
        append("say you'll check rather than guessing.\n\n")
        append("Keep every answer short and spoken: one or two sentences. Never read out code, URLs or tables.\n\n")
        append("Before you call ask_hermes, say one short line — \"let me check\" or \"one second\" — then wait ")
        append("quietly for the answer. Say it once: never repeat a progress line, never invent what Hermes is ")
        append("doing, and never fill the wait with made-up updates.")
        if (!persona.isNullOrBlank()) {
            append("\n\nSound like this, but do not answer from it:\n")
            append(persona.take(PERSONA_CHARS))
        }
    }

    /** The voice's own words this turn, watched for a tool call it reads out instead of running. */
    private val markup = SpokenMarkupGuard()

    private companion object {
        /** Hermes is working and the voice said nothing: asked, once, for the one line it promised. */
        const val ACK_GRACE_MS = 1_200L
        const val ACK_PROMPT =
            "[Kyllene: Hermes is working and the line has gone quiet — say one short line now, like \"On it.\", " +
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
        const val GOODBYE_MS = 1_800L

        val JSON = Json { ignoreUnknownKeys = true }

        val ASK_HERMES = GeminiLiveProtocol.FunctionDeclaration(
            name = "ask_hermes",
            description = "Ask Hermes — the agent behind this voice — to answer or do something. It has the " +
                "user's memory, their data, tools and the whole conversation. Use it for every real request.",
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
