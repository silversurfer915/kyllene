package dev.hermeskotlin.core.voice.gemini

import dev.hermeskotlin.core.voice.ChatHit
import dev.hermeskotlin.core.voice.ChatLookup
import dev.hermeskotlin.core.voice.JobStart
import dev.hermeskotlin.core.voice.VoiceJob

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What Hermes has in flight in this chat, as `list_jobs` reports it. */
data class JobSnapshot(
    /** What it is doing right now ("running terminal", "writing a reply"), or null when it is idle. */
    val running: String? = null,
    /** Prompts waiting their turn behind it, in the user's words. */
    val queued: List<String> = emptyList(),
    /** Subagents it delegated, with their state. */
    val subagents: List<String> = emptyList(),
    /** Background processes it started that are still alive. */
    val processes: List<String> = emptyList(),
) {
    val idle: Boolean get() = running == null && queued.isEmpty() && subagents.isEmpty() && processes.isEmpty()
}

/** What the voice gets back from a tool: the JSON body of its `toolResponse` and how to schedule it. */
data class ToolOutcome(
    val result: JsonObject,
    /** `WHEN_IDLE` / `SILENT` for a non-blocking call; null when the model should wait. */
    val scheduling: String? = null,
)

/**
 * What the voice may do to Hermes, one declaration per capability.
 *
 * Built the way Conduit builds `GeminiLiveToolBridge`: a tool is declared only when the app can really carry it
 * out, every call gets exactly one answer, and a refusal is worded for speaking aloud rather than for a log.
 */
class VoiceToolBridge(
    /** The last thing Hermes said in this chat, or null when it hasn't. */
    private val lastReply: () -> String?,
    /** Slips the user's words into the running turn; true when a turn took them. */
    private val steer: suspend (String) -> Boolean,
    /** Stops the running turn, returning what was dropped. */
    private val stop: suspend () -> List<String>,
    /** What Hermes has in flight, read at call time (processes cost a gateway round trip). */
    private val jobs: suspend () -> JobSnapshot,
    /** The host's memory provider for a query: null when it has none or couldn't answer. */
    private val recall: suspend (String) -> String? = { null },
    /** The host's search backend for a query: null when it couldn't search. */
    private val search: suspend (String) -> List<SearchHit>? = { null },
    /** Starts a piece of work in its own chat; null when this call can't start one. */
    private val startJob: (suspend (String) -> JobStart)? = null,
    /** Jobs started earlier, still running or just finished. */
    private val jobList: () -> List<VoiceJob> = { emptyList() },
    /** Stops a job by id, or every running one when null; returns the titles it stopped. */
    private val cancelJobs: suspend (String?) -> List<String> = { emptyList() },
    /** The user's other chats, searched and read; null when this call can't see them. */
    private val chats: ChatLookup? = null,
    /** Turns the chat's "ask before running anything" policy on or off; returns whether it now asks. */
    private val setAskFirst: (suspend (Boolean) -> Boolean)? = null,
    /** Ends the call, once the voice has had a moment to say goodbye. */
    private val endCall: (() -> Unit)? = null,
    /** Draws a card on the call's screen: title and body. Null where the call has no screen to draw on. */
    private val showCard: ((String, String) -> Unit)? = null,
    /** Rings this phone when the chat's work ends, with what the voice should say when it's answered. */
    private val whenDone: (suspend (String) -> Boolean)? = null,
    /** Brings another chat to the screen: id and title. Null where the call can't move the app. */
    private val openChat: ((String, String) -> Unit)? = null,
) {

    /** The declarations for this call, in the order the model should prefer them. */
    fun declarations(): List<GeminiLiveProtocol.FunctionDeclaration> = buildList {
        addAll(baseDeclarations())
        // Only offered when a job can really be started and watched from here.
        if (startJob != null) addAll(jobDeclarations())
        if (chats != null) addAll(chatDeclarations())
        if (setAskFirst != null) add(askFirstDeclaration())
        if (showCard != null) add(showScreenDeclaration())
        if (whenDone != null) add(callWhenDoneDeclaration())
        if (openChat != null && chats != null) add(openChatDeclaration())
        if (endCall != null) add(endConversationDeclaration())
    }

    private fun baseDeclarations(): List<GeminiLiveProtocol.FunctionDeclaration> = listOf(
        GeminiLiveProtocol.FunctionDeclaration(
            name = LIST_JOBS,
            description = "Tell the user what Hermes is doing right now: background jobs it is running, the " +
                "work in this chat, requests waiting their turn, subagents and background processes. Use it " +
                "when they ask what it's working on, whether something is done, or how long it will be.",
            parameters = buildJsonObject { put("type", "OBJECT") },
        ),
        GeminiLiveProtocol.FunctionDeclaration(
            name = RECALL_MEMORY,
            description = "Look something up in what Hermes remembers about the user across every chat: their " +
                "people, preferences, plans and past decisions. Use it when they ask what you remember or " +
                "something they told Hermes before.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "STRING")
                        put("description", "What to look for, in a few words.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        GeminiLiveProtocol.FunctionDeclaration(
            name = WEB_SEARCH,
            description = "Search the web through Hermes's own backend and get titles and snippets back. " +
                "Use it for anything recent or outside the user's own life — news, prices, dates, facts.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "STRING")
                        put("description", "A few words to search for, as a search box would take them.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        GeminiLiveProtocol.FunctionDeclaration(
            name = READ_LAST_REPLY,
            description = "Read back what Hermes last said in this chat, word for word. Use it when the user " +
                "asks what Hermes said, wants it repeated, or asks a follow-up about the last answer.",
            parameters = buildJsonObject { put("type", "OBJECT") },
        ),
        GeminiLiveProtocol.FunctionDeclaration(
            name = INTERRUPT_JOB,
            description = "Pass the user's words into the work Hermes is doing right now, when they correct it, " +
                "add to it, or call it off (\"wait, make it Alex\", \"never mind\", \"also check the other one\"). " +
                "Hermes reads them at once and keeps its work so far. Not for a new request — use ask_hermes.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("message") {
                        put("type", "STRING")
                        put("description", "The user's words about the work, as they said them.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("message")) }
            },
            // The call stays open: the voice may keep talking while Hermes takes the words in.
            behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
        ),
        GeminiLiveProtocol.FunctionDeclaration(
            name = CANCEL_JOB,
            description = "Stop what Hermes is doing, when the user tells it to stop, wait, or cancel. " +
                "Nothing else is affected; the chat keeps what it already produced.",
            parameters = buildJsonObject { put("type", "OBJECT") },
        ),
    )

    private fun jobDeclarations(): List<GeminiLiveProtocol.FunctionDeclaration> = listOf(
        GeminiLiveProtocol.FunctionDeclaration(
            name = START_JOB,
            description = "Start a long piece of work in a chat of its own, so the user can keep talking here " +
                "while Hermes does it (\"in the background\", \"while I revise\", \"run that separately\"). " +
                "Tell them you've started it. Not for anything they want answered here — that is ask_hermes.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("request") {
                        put("type", "STRING")
                        put("description", "The whole request, in the user's words, as Hermes should take it.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("request")) }
            },
            behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
        ),
    )

    private fun chatDeclarations(): List<GeminiLiveProtocol.FunctionDeclaration> = listOf(
        GeminiLiveProtocol.FunctionDeclaration(
            name = FIND_CHAT,
            description = "Find one of the user's other chats by what was said in it. Use it when they refer " +
                "to something from before (\"that chat about the build\", \"what did we decide about the app\").",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "STRING")
                        put("description", "Words from the chat or what it was about.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        GeminiLiveProtocol.FunctionDeclaration(
            name = READ_CHAT,
            description = "Read the last thing Hermes said in another chat, word for word. Use it after " +
                "find_chat, or when the user names the chat they mean and asks what was said there.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("chat") {
                        put("type", "STRING")
                        put("description", "The chat id from find_chat, or words describing it.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("chat")) }
            },
        ),
    )

    /** Runs one call the model made. Always returns an answer — an unanswered call wedges the conversation. */
    suspend fun handle(call: GeminiLiveProtocol.FunctionCall): ToolOutcome = when (call.name) {
        START_JOB -> startJobTool(call.arguments["request"])
        LIST_JOBS -> listJobs()
        RECALL_MEMORY -> recallMemory(call.arguments["query"])
        WEB_SEARCH -> webSearch(call.arguments["query"])
        FIND_CHAT -> findChat(call.arguments["query"])
        READ_CHAT -> readChat(call.arguments["chat"])
        READ_LAST_REPLY -> readLastReply()
        INTERRUPT_JOB -> interruptJob(call.arguments["message"])
        CANCEL_JOB -> cancelJobTool(call.arguments["job"])
        SET_ASK_FIRST -> setAskFirstTool(call.arguments["ask"])
        SHOW_ON_SCREEN -> showOnScreen(call.arguments["title"], call.arguments["body"])
        CALL_ME_WHEN_DONE -> callMeWhenDone(call.arguments["reason"])
        OPEN_CHAT -> openChatTool(call.arguments["chat"])
        END_CONVERSATION -> endConversation()
        else -> failure("That isn't something I can do on this call.", call.name)
    }

    private fun askFirstDeclaration(): GeminiLiveProtocol.FunctionDeclaration = GeminiLiveProtocol.FunctionDeclaration(
        name = SET_ASK_FIRST,
        description = "Turn Hermes's asking for approval on or off for this chat, when the user says " +
            "\"ask me before you do anything\", \"don't run anything without asking\", or \"just do it, stop asking\".",
        parameters = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("ask") {
                    put("type", "BOOLEAN")
                    put("description", "True to ask before running anything; false to go ahead without asking.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("ask")) }
        },
        behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
    )

    private fun showScreenDeclaration(): GeminiLiveProtocol.FunctionDeclaration =
        GeminiLiveProtocol.FunctionDeclaration(
            name = SHOW_ON_SCREEN,
            description = "Put something on the user's screen during this call, when it is better seen than heard: " +
                "a few steps, a short list, a small table, an address. Keep it brief — a title and a handful of " +
                "lines — and still answer out loud in a sentence.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "STRING")
                        put("description", "A few words naming it: tomorrow's plan, the timetable, an address.")
                    }
                    putJsonObject("body") {
                        put("type", "STRING")
                        put("description", "What to show: short lines, one to a line, at most a dozen.")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("title"))
                    add(JsonPrimitive("body"))
                }
            },
            behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
        )

    private fun callWhenDoneDeclaration(): GeminiLiveProtocol.FunctionDeclaration =
        GeminiLiveProtocol.FunctionDeclaration(
            name = CALL_ME_WHEN_DONE,
            description = "Ring the user's phone when the work being done now finishes — when they say \"call me " +
                "when it's done\" or want to hang up and hear the result later. Answering puts them on a call that " +
                "tells them why. Use it only when something is actually running.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("reason") {
                        put("type", "STRING")
                        put("description", "What to tell them when they answer, in a few words.")
                    }
                }
            },
            behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
        )

    private fun showOnScreen(title: String?, body: String?): ToolOutcome {
        val draw = showCard
            ?: return failure("I can't put anything on the screen from this call.", SHOW_ON_SCREEN, WHEN_IDLE)
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return failure("There was nothing to put on the screen.", SHOW_ON_SCREEN, WHEN_IDLE)
        val heading = title?.trim().orEmpty().ifBlank { "Hermes" }.take(MAX_CARD_TITLE_CHARS)
        draw(heading, text.take(MAX_CARD_CHARS))
        return ToolOutcome(
            buildJsonObject {
                put("status", "ok")
                put("message", "It's on their screen; still say the answer out loud.")
            },
            WHEN_IDLE,
        )
    }

    private suspend fun callMeWhenDone(reason: String?): ToolOutcome {
        val ring = whenDone
            ?: return failure("I can't ring this phone from this call.", CALL_ME_WHEN_DONE, WHEN_IDLE)
        val words = reason?.trim().orEmpty().ifBlank { DEFAULT_CALL_REASON }.take(MAX_REASON_CHARS)
        val armed = runCatching { ring(words) }.getOrElse {
            return failure("I couldn't set that up, so nothing will ring.", CALL_ME_WHEN_DONE, WHEN_IDLE)
        }
        if (!armed) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "nothing_running")
                    put("message", "Nothing is running to wait for — this already finished.")
                },
                WHEN_IDLE,
            )
        }
        return ToolOutcome(
            buildJsonObject {
                put("status", "armed")
                put("message", "I'll ring them when it's done, so they can hang up.")
            },
            WHEN_IDLE,
        )
    }

    private fun openChatDeclaration(): GeminiLiveProtocol.FunctionDeclaration =
        GeminiLiveProtocol.FunctionDeclaration(
            name = OPEN_CHAT,
            description = "Bring one of the user's other chats to the screen, when they ask to open or go back to " +
                "it. The call ends as it opens; say you're opening it, then stay quiet.",
            parameters = buildJsonObject {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("chat") {
                        put("type", "STRING")
                        put("description", "The chat id from find_chat, or words describing it.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("chat")) }
            },
            behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
        )

    private suspend fun openChatTool(chat: String?): ToolOutcome {
        val words = chat?.trim().orEmpty()
        if (words.isEmpty()) return failure("I didn't know which chat you meant.", OPEN_CHAT, WHEN_IDLE)
        val lookup = chats ?: return failure("I can't see your other chats from this call.", OPEN_CHAT, WHEN_IDLE)
        val open = openChat ?: return failure("I can't open a chat from this call.", OPEN_CHAT, WHEN_IDLE)
        val hit = runCatching { lookup.find(words, 1).firstOrNull() }.getOrNull()
            ?: return ToolOutcome(
                buildJsonObject {
                    put("status", "empty")
                    put("message", "I couldn't find that chat.")
                },
                WHEN_IDLE,
            )
        open(hit.id, hit.title)
        return ToolOutcome(
            buildJsonObject {
                put("status", "opening")
                put("chat", hit.id)
                put("message", "Opening \"${hit.title.take(MAX_JOB_CHARS)}\". The call ends as it opens.")
            },
            WHEN_IDLE,
        )
    }

    private fun endConversationDeclaration(): GeminiLiveProtocol.FunctionDeclaration =
        GeminiLiveProtocol.FunctionDeclaration(
            name = END_CONVERSATION,
            description = "Hang up, when the user says goodbye and clearly wants the call over (\"that's all, bye\"). " +
                "Say your goodbye first: the call ends a moment after you answer. Not for silence or a pause.",
            parameters = buildJsonObject { put("type", "OBJECT") },
            behavior = GeminiLiveProtocol.FunctionDeclaration.Behavior.NON_BLOCKING,
        )

    private suspend fun setAskFirstTool(ask: String?): ToolOutcome {
        val setter = setAskFirst
            ?: return failure("I can't change that from this call.", SET_ASK_FIRST, scheduling = WHEN_IDLE)
        val wanted = when (ask?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> return failure("I didn't catch whether you want to be asked.", SET_ASK_FIRST, scheduling = WHEN_IDLE)
        }
        val asking = runCatching { setter(wanted) }.getOrElse {
            return failure("Hermes wouldn't take that, so nothing changed.", SET_ASK_FIRST, scheduling = WHEN_IDLE)
        }
        return ToolOutcome(
            buildJsonObject {
                put("status", "ok")
                put("ask_first", asking)
                put(
                    "message",
                    if (asking) "Hermes will ask before it runs anything in this chat."
                    else "Hermes won't ask in this chat; it will go ahead.",
                )
            },
            WHEN_IDLE,
        )
    }

    private fun endConversation(): ToolOutcome {
        val end = endCall ?: return failure("I can't end the call from here.", END_CONVERSATION)
        end()
        return ToolOutcome(
            buildJsonObject {
                put("status", "ending")
                put("message", "Ending the call.")
            },
            WHEN_IDLE,
        )
    }

    private suspend fun recallMemory(query: String?): ToolOutcome {
        val words = query?.trim().orEmpty()
        if (words.isEmpty()) return failure("There was nothing to look up.", RECALL_MEMORY)
        val memory = runCatching { recall(words) }.getOrNull()
            ?: return ToolOutcome(
                buildJsonObject {
                    put("status", "unavailable")
                    put("message", "I can't reach Hermes's memory from this call.")
                },
            )
        if (memory.isBlank()) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "empty")
                    put("message", "I don't have anything about that in memory.")
                },
            )
        }
        return ToolOutcome(
            buildJsonObject {
                put("status", "ok")
                put("memory", JsonPrimitive(memory.take(MAX_MEMORY_CHARS)))
            },
        )
    }

    private suspend fun webSearch(query: String?): ToolOutcome {
        val words = query?.trim().orEmpty()
        if (words.isEmpty()) return failure("There was nothing to search for.", WEB_SEARCH)
        val hits = runCatching { search(words) }.getOrNull()
            ?: return ToolOutcome(
                buildJsonObject {
                    put("status", "unavailable")
                    put("message", "I can't search the web from this call.")
                },
            )
        if (hits.isEmpty()) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "empty")
                    put("message", "Nothing came up for that.")
                },
            )
        }
        val top = hits.take(MAX_SEARCH_HITS)
        return ToolOutcome(
            buildJsonObject {
                put("status", "ok")
                put("summary", spokenSummary(top))
                putJsonArray("results") {
                    top.forEach { hit ->
                        add(
                            buildJsonObject {
                                put("title", hit.title.take(MAX_TITLE_CHARS))
                                put("url", hit.url)
                                put("snippet", hit.snippet.take(MAX_SNIPPET_CHARS))
                            },
                        )
                    }
                }
            },
        )
    }

    /** The first result said out loud, then a count: the voice reads it, the screen has the rest. */
    private fun spokenSummary(hits: List<SearchHit>): String {
        val first = hits.first()
        val lead = first.snippet.ifBlank { first.title }.trim()
        val more = hits.size - 1
        val tail = if (more > 0) " There ${if (more == 1) "is 1 more" else "are $more more"}." else ""
        return (lead + tail).trim()
    }

    private suspend fun listJobs(): ToolOutcome {
        val snapshot = runCatching { jobs() }.getOrElse {
            return failure("I couldn't see what Hermes is doing just now.", LIST_JOBS)
        }
        val background = jobList().filter { it.status == VoiceJob.Status.Running || it.status == VoiceJob.Status.Waiting }
        return ToolOutcome(
            buildJsonObject {
                put("status", if (snapshot.idle && background.isEmpty()) "idle" else "busy")
                put("summary", summarize(snapshot, background))
                snapshot.running?.let { put("running", JsonPrimitive(it)) }
                putJsonArray("jobs") {
                    background.take(MAX_JOBS).forEach { job ->
                        add(
                            buildJsonObject {
                                put("id", job.id)
                                put("title", job.title.take(MAX_JOB_CHARS))
                            },
                        )
                    }
                }
                putJsonArray("queued") { snapshot.queued.take(MAX_JOBS).forEach { add(JsonPrimitive(it.take(MAX_JOB_CHARS))) } }
                putJsonArray("subagents") { snapshot.subagents.take(MAX_JOBS).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("processes") { snapshot.processes.take(MAX_JOBS).forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    /** The sentence the voice actually says: short, specific, and never an inventory read aloud. */
    private fun summarize(snapshot: JobSnapshot, background: List<VoiceJob> = emptyList()): String {
        if (snapshot.idle && background.isEmpty()) {
            return "Hermes isn't working on anything right now."
        }
        val parts = mutableListOf<String>()
        if (background.isNotEmpty()) {
            parts += if (background.size == 1) {
                "it's on \"${background.first().title}\" in the background"
            } else {
                "it has ${background.size} jobs running in the background"
            }
        }
        snapshot.running?.let { parts += "right now it's $it" }
        if (snapshot.queued.isNotEmpty()) {
            parts += "${snapshot.queued.size} request${if (snapshot.queued.size == 1) "" else "s"} waiting"
        }
        if (snapshot.subagents.isNotEmpty()) {
            parts += "${snapshot.subagents.size} subagent${if (snapshot.subagents.size == 1) "" else "s"} working"
        }
        if (snapshot.processes.isNotEmpty()) {
            parts += "${snapshot.processes.size} background process${if (snapshot.processes.size == 1) "" else "es"} running"
        }
        val head = parts.first().replaceFirstChar { it.uppercase() }
        return if (parts.size == 1) "$head." else "$head, with ${parts.drop(1).joinToString(", ")}."
    }

    private suspend fun findChat(query: String?): ToolOutcome {
        val words = query?.trim().orEmpty()
        if (words.isEmpty()) return failure("There was nothing to look for.", FIND_CHAT)
        val lookup = chats ?: return failure("I can't see your other chats from this call.", FIND_CHAT)
        val hits = runCatching { lookup.find(words, MAX_CHAT_HITS) }.getOrNull()
            ?: return failure("I couldn't search your chats just now.", FIND_CHAT)
        if (hits.isEmpty()) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "empty")
                    put("message", "Nothing in your other chats matches that.")
                },
            )
        }
        return ToolOutcome(
            buildJsonObject {
                put("status", "ok")
                put("summary", spokenChats(hits))
                putJsonArray("chats") {
                    hits.forEach { hit ->
                        add(
                            buildJsonObject {
                                put("id", hit.id)
                                put("title", hit.title.take(MAX_JOB_CHARS))
                                hit.snippet?.let { put("matched", it.take(MAX_SNIPPET_CHARS)) }
                            },
                        )
                    }
                }
            },
        )
    }

    private suspend fun readChat(chat: String?): ToolOutcome {
        val words = chat?.trim().orEmpty()
        if (words.isEmpty()) return failure("I didn't know which chat you meant.", READ_CHAT)
        val lookup = chats ?: return failure("I can't see your other chats from this call.", READ_CHAT)
        val sessionId = runCatching { lookup.find(words, 1).firstOrNull()?.id }.getOrNull()
        if (sessionId == null) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "empty")
                    put("message", "I couldn't find that chat.")
                },
            )
        }
        val reply = runCatching { lookup.lastReply(sessionId) }.getOrNull()
            ?: return ToolOutcome(
                buildJsonObject {
                    put("status", "empty")
                    put("message", "That chat has no answer from Hermes yet.")
                },
            )
        return ToolOutcome(
            buildJsonObject {
                put("status", "ok")
                put("reply", JsonPrimitive(reply.trim().take(MAX_REPLY_CHARS)))
            },
        )
    }

    /** The first chat named, then how many more: the voice reads this, the screen has the rest. */
    private fun spokenChats(hits: List<ChatHit>): String {
        val first = hits.first()
        val more = hits.size - 1
        val tail = if (more > 0) " There ${if (more == 1) "is 1 more" else "are $more more"}." else ""
        return ("\"${first.title}\"" + tail).trim()
    }

    private fun readLastReply(): ToolOutcome {
        val text = lastReply()?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "empty")
                    put("message", "Hermes hasn't said anything in this chat yet.")
                },
            )
        }
        return ToolOutcome(
            buildJsonObject {
                put("status", "ok")
                put("reply", JsonPrimitive(text.take(MAX_REPLY_CHARS)))
                if (text.length > MAX_REPLY_CHARS) put("truncated", true)
            },
        )
    }

    private suspend fun interruptJob(message: String?): ToolOutcome {
        val words = message?.trim().orEmpty()
        if (words.isEmpty()) {
            return failure("There were no words to pass on.", INTERRUPT_JOB, scheduling = WHEN_IDLE)
        }
        val steered = runCatching { steer(words) }.getOrElse {
            return failure("Hermes couldn't take that just now.", INTERRUPT_JOB, scheduling = WHEN_IDLE)
        }
        return ToolOutcome(
            buildJsonObject {
                put("status", if (steered) "delivered" else "nothing_running")
                put(
                    "message",
                    if (steered) "Hermes has it and will carry on from there."
                    else "Hermes isn't working on anything right now, so that became its next request.",
                )
            },
            WHEN_IDLE,
        )
    }

    private suspend fun startJobTool(request: String?): ToolOutcome {
        val text = request?.trim().orEmpty()
        if (text.isEmpty()) return failure("There was nothing to start.", START_JOB, scheduling = WHEN_IDLE)
        val starter = startJob
            ?: return failure("I can't start background work from this call.", START_JOB, scheduling = WHEN_IDLE)
        val started = runCatching { starter(text) }.getOrNull()
            ?: return failure("Hermes wouldn't start that.", START_JOB, scheduling = WHEN_IDLE)
        started.error?.let { return failure(it, START_JOB, scheduling = WHEN_IDLE) }
        val job = started.job!!
        return ToolOutcome(
            buildJsonObject {
                put("status", "started")
                put("job", job.id)
                put("title", job.title)
                put("message", "Started in its own chat. I'll tell you when it's done.")
            },
            WHEN_IDLE,
        )
    }

    /**
     * Stops a named job, every running job, or — when there are no jobs — the turn in this chat.
     *
     * The voice says "stop" without knowing which kind of work it means, so the tool has to be the one that
     * knows; asking it to pick a kind would leave the user's "stop" doing nothing.
     */
    private suspend fun cancelJobTool(job: String?): ToolOutcome {
        val named = job?.trim()?.takeIf { it.isNotEmpty() }
        val stopped = runCatching { cancelJobs(named) }.getOrDefault(emptyList())
        if (stopped.isNotEmpty()) {
            return ToolOutcome(
                buildJsonObject {
                    put("status", "stopped")
                    put("message", "Stopped ${if (stopped.size == 1) stopped.first() else "${stopped.size} jobs"}.")
                },
            )
        }
        val dropped = runCatching { stop() }.getOrElse {
            return failure("Hermes couldn't be stopped from here.", CANCEL_JOB)
        }
        return ToolOutcome(
            buildJsonObject {
                put("status", "stopped")
                put(
                    "message",
                    if (dropped.isEmpty()) "Stopped. Nothing was waiting in the queue."
                    else "Stopped, and let go of ${dropped.size} queued request(s).",
                )
            },
        )
    }

    private fun failure(message: String, tool: String, scheduling: String? = null) = ToolOutcome(
        buildJsonObject {
            put("status", "error")
            put("tool", tool)
            put("message", message)
        },
        scheduling,
    )

    companion object {
        const val LIST_JOBS = "list_jobs"
        const val RECALL_MEMORY = "recall_memory"
        const val START_JOB = "start_job"
        const val FIND_CHAT = "find_chat"
        const val READ_CHAT = "read_chat"
        const val WEB_SEARCH = "web_search"
        const val READ_LAST_REPLY = "read_last_reply"
        const val INTERRUPT_JOB = "interrupt_job"
        const val CANCEL_JOB = "cancel_job"
        const val SET_ASK_FIRST = "set_ask_first"
        const val END_CONVERSATION = "end_conversation"
        const val SHOW_ON_SCREEN = "show_on_screen"
        const val CALL_ME_WHEN_DONE = "call_me_when_done"
        const val OPEN_CHAT = "open_chat"

        /** A card is read at a glance, beside a moving call screen: a title and a dozen short lines at most. */
        const val MAX_CARD_TITLE_CHARS = 80
        const val MAX_CARD_CHARS = 1_600

        /** What the ring says when the voice gave no reason of its own. */
        const val MAX_REASON_CHARS = 300
        const val DEFAULT_CALL_REASON = "The work you asked me to call you about has finished."

        /**
         * The lines of [memory] that answer [query], or "" when none does.
         *
         * This host's memory arrives as the same block Hermes injects into its own prompt, so recall here is a
         * keyword pass over that block: enough to answer "what do you remember about X" without a turn.
         */
        fun memoryMatches(memory: String, query: String): String {
            val tokens = query.lowercase().split(NON_WORD).filter { it.length >= 3 }.distinct()
            if (tokens.isEmpty()) return ""
            val lines = memory.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("═") && !it.startsWith("§") }
                .toList()
            return lines
                .map { line -> line to tokens.count { line.contains(it, ignoreCase = true) } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(3)
                .joinToString(" • ") { it.first }
                .take(MAX_MEMORY_CHARS)
        }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** A reply longer than this is clipped: the live session speaks a summary, not a transcript. */
        const val MAX_REPLY_CHARS = 2_000

        /** How many entries of each kind `list_jobs` reports: the voice speaks a summary, not an inventory. */
        const val MAX_JOBS = 5

        /** One queued prompt as the model sees it. */
        const val MAX_JOB_CHARS = 200

        /** Memory text is spoken, so it is clipped to what a sentence or two can carry. */
        const val MAX_MEMORY_CHARS = 1_500

        /** How many results the voice reports at once: one is spoken, the rest are there if it needs them. */
        const val MAX_SEARCH_HITS = 3
        const val MAX_TITLE_CHARS = 200
        const val MAX_SNIPPET_CHARS = 300

        /** How many chats one search may name aloud. */
        const val MAX_CHAT_HITS = 3

        private const val WHEN_IDLE = "WHEN_IDLE"
    }
}
