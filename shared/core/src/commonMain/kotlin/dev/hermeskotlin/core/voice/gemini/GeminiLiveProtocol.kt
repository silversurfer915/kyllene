package dev.hermeskotlin.core.voice.gemini

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini Live over a raw WebSocket, the way Conduit does it on iOS: the phone talks straight to
 * Google with a single-use ephemeral token the Hermes host minted, so audio never transits the
 * gateway and a turn costs one hop instead of three.
 *
 * Ported from `hermes-conduit` `Shared/GeminiLive/GeminiLiveProtocol.swift`; every wire shape below
 * is that file's, not guessed.
 */
@OptIn(ExperimentalEncodingApi::class)
object GeminiLiveProtocol {
    const val MODEL = "models/gemini-3.8-live"
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000
    const val INPUT_MIME_TYPE = "audio/pcm;rate=16000"

    /** How much silence ends the user's turn: short enough to feel immediate, long enough not to clip. */
    const val SILENCE_MS = 400

    /** How one function the model may call is described to it. */
    data class FunctionDeclaration(
        val name: String,
        val description: String,
        /** OpenAPI-subset schema object for the arguments. */
        val parameters: JsonObject,
        val behavior: Behavior = Behavior.BLOCKING,
    ) {
        enum class Behavior(val wire: String) {
            BLOCKING("BLOCKING"),
            NON_BLOCKING("NON_BLOCKING"),
        }

        fun json(): JsonObject = buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
            put("behavior", behavior.wire)
        }
    }

    /** A call the model made. [arguments] are the model's JSON values, flattened to strings. */
    data class FunctionCall(
        val id: String,
        val name: String,
        val arguments: Map<String, String>,
    )

    /** `gemini-3.8-live` -> `models/gemini-3.8-live`. */
    fun qualifiedModel(model: String): String =
        if (model.startsWith("models/")) model else "models/$model"

    fun setupMessage(
        model: String = MODEL,
        systemInstruction: String,
        functions: List<FunctionDeclaration> = emptyList(),
        googleSearch: Boolean = true,
        voice: String? = null,
        resumptionHandle: String? = null,
        silenceMs: Int = SILENCE_MS,
    ): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", qualifiedModel(model))

            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                if (!voice.isNullOrEmpty()) {
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) }
                        }
                    }
                }
            }

            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
            }

            putJsonArray("tools") {
                if (functions.isNotEmpty()) {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") { functions.forEach { add(it.json()) } }
                    })
                }
                // Gemini answers quick lookups itself instead of spending a Hermes turn on them.
                if (googleSearch) add(buildJsonObject { putJsonObject("googleSearch") {} })
            }

            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("disabled", false)
                    // Snappier than the defaults: a short pause counts as the end of a turn, and the first
                    // syllable isn't clipped, so the reply starts sooner after the user stops.
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                    put("prefixPaddingMs", 40)
                    put("silenceDurationMs", silenceMs)
                }
                // The user's speech always interrupts the model: it must never talk over them.
                put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            }

            // An empty object still opts in: the server then sends resumption handles to reconnect with.
            putJsonObject("sessionResumption") {
                if (!resumptionHandle.isNullOrEmpty()) put("handle", resumptionHandle)
            }
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }.toString()

    fun audioMessage(pcm16: ByteArray): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("mimeType", INPUT_MIME_TYPE)
                put("data", Base64.encode(pcm16))
            }
        }
    }.toString()

    /** Sent when the microphone is muted mid-utterance so the server stops waiting for more audio. */
    fun audioStreamEndMessage(): String = buildJsonObject {
        putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
    }.toString()

    /** A text turn from the client, for updates whose function call is no longer open. */
    /** What a model sometimes says instead of calling a tool: the call itself, read out loud. */
    private val SPOKEN_TOOL_MARKERS = listOf("<tool_call", "</tool_call", "<function_call", "<|tool", "<tool_code")

    /** Where the first spoken tool call starts in [text], if it has one; matched without case, as models vary. */
    fun spokenToolMarkupStart(text: String): Int? =
        SPOKEN_TOOL_MARKERS.mapNotNull { marker -> text.indexOf(marker, ignoreCase = true).takeIf { it >= 0 } }.minOrNull()

    fun textTurnMessage(text: String): String = clientContent(text, turnComplete = true)

    /** Context added without asking for a reply. */
    fun contextNoteMessage(text: String): String = clientContent(text, turnComplete = false)

    private fun clientContent(text: String, turnComplete: Boolean): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                })
            }
            put("turnComplete", turnComplete)
        }
    }.toString()

    fun toolResponseMessage(
        id: String,
        name: String,
        result: JsonObject,
        /** `WHEN_IDLE` / `SILENT` for a NON_BLOCKING call: the voice keeps talking. */
        scheduling: String? = null,
    ): String = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                add(buildJsonObject {
                    put("id", id)
                    put("name", name)
                    put("response", result)
                    if (scheduling != null) put("scheduling", scheduling)
                })
            }
        }
    }.toString()

    // ---------------------------------------------------------------- server -> client

    sealed interface ServerEvent {
        data object SetupComplete : ServerEvent

        /** Model speech: 16-bit little-endian PCM at [sampleRate]. Byte arrays compare by identity. */
        class Audio(val pcm: ByteArray, val sampleRate: Int) : ServerEvent

        data class InputTranscription(val text: String) : ServerEvent
        data class OutputTranscription(val text: String) : ServerEvent

        /** The user started speaking: stop playback now. */
        data object Interrupted : ServerEvent
        data object TurnComplete : ServerEvent
        data class ToolCall(val calls: List<FunctionCall>) : ServerEvent
        data class ToolCallCancellation(val ids: List<String>) : ServerEvent
        data class GoAway(val timeLeftSeconds: Double?) : ServerEvent
        data class ResumptionUpdate(val handle: String?, val resumable: Boolean) : ServerEvent
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** One frame can carry several events (audio, a transcription, turnComplete). Unknown fields are ignored. */
    fun decode(frame: String): List<ServerEvent> {
        val root = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
            ?: return emptyList()
        val events = mutableListOf<ServerEvent>()

        if (root.containsKey("setupComplete")) events += ServerEvent.SetupComplete

        root["serverContent"]?.asObject()?.let { content ->
            content["modelTurn"]?.asObject()?.get("parts")?.asArray()?.forEach { part ->
                val inline = part.asObject()?.get("inlineData")?.asObject() ?: return@forEach
                val mime = inline["mimeType"]?.asString() ?: return@forEach
                if (!mime.startsWith("audio/pcm")) return@forEach
                val base64 = inline["data"]?.asString() ?: return@forEach
                val pcm = runCatching { Base64.decode(base64) }.getOrNull() ?: return@forEach
                events += ServerEvent.Audio(pcm, sampleRateFromMime(mime) ?: OUTPUT_SAMPLE_RATE)
            }
            content["inputTranscription"]?.asObject()?.get("text")?.asString()
                ?.takeIf { it.isNotEmpty() }?.let { events += ServerEvent.InputTranscription(it) }
            content["outputTranscription"]?.asObject()?.get("text")?.asString()
                ?.takeIf { it.isNotEmpty() }?.let { events += ServerEvent.OutputTranscription(it) }
            if (content["interrupted"]?.asBoolean() == true) events += ServerEvent.Interrupted
            if (content["turnComplete"]?.asBoolean() == true) events += ServerEvent.TurnComplete
        }

        root["toolCall"]?.asObject()?.get("functionCalls")?.asArray()?.let { calls ->
            val parsed = calls.mapNotNull { call ->
                val obj = call.asObject() ?: return@mapNotNull null
                val id = obj["id"]?.asString() ?: return@mapNotNull null
                val name = obj["name"]?.asString() ?: return@mapNotNull null
                FunctionCall(id, name, stringArguments(obj["args"]?.asObject()))
            }
            if (parsed.isNotEmpty()) events += ServerEvent.ToolCall(parsed)
        }

        root["toolCallCancellation"]?.asObject()?.get("ids")?.asArray()?.let { ids ->
            val parsed = ids.mapNotNull { it.asString() }
            if (parsed.isNotEmpty()) events += ServerEvent.ToolCallCancellation(parsed)
        }

        root["goAway"]?.asObject()?.let { goAway ->
            events += ServerEvent.GoAway(durationSeconds(goAway["timeLeft"]))
        }

        root["sessionResumptionUpdate"]?.asObject()?.let { update ->
            val handle = update["newHandle"]?.asString()?.takeIf { it.isNotEmpty() }
            events += ServerEvent.ResumptionUpdate(handle, update["resumable"]?.asBoolean() ?: false)
        }

        return events
    }

    /** `audio/pcm;rate=24000` -> 24000. */
    fun sampleRateFromMime(mime: String): Int? {
        for (parameter in mime.split(';').drop(1)) {
            val pair = parameter.split('=', limit = 2)
            if (pair.size != 2) continue
            if (pair[0].trim().lowercase() != "rate") continue
            val rate = pair[1].trim().toDoubleOrNull() ?: continue
            if (rate > 0) return rate.toInt()
        }
        return null
    }

    /** Protobuf Duration in JSON: `"10s"` / `"1.5s"`, or `{seconds, nanos}`. */
    fun durationSeconds(value: JsonElement?): Double? {
        value ?: return null
        (value as? JsonPrimitive)?.contentOrNull?.let { text ->
            if (!text.endsWith("s")) return null
            return text.dropLast(1).toDoubleOrNull()
        }
        val obj = value.asObject() ?: return null
        val seconds = obj["seconds"]?.let { it.asString()?.toDoubleOrNull() ?: it.asDouble() } ?: 0.0
        val nanos = obj["nanos"]?.asDouble() ?: 0.0
        return seconds + nanos / 1_000_000_000
    }

    /** Function-call arguments arrive as JSON values; the bridge wants them as strings. */
    private fun stringArguments(args: JsonObject?): Map<String, String> {
        if (args == null) return emptyMap()
        return args.mapValues { (_, value) -> value.asString() ?: value.toString() }
    }
}

private fun JsonElement.asObject(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun JsonElement.asArray(): JsonArray? = runCatching { jsonArray }.getOrNull()
private fun JsonElement.asString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonElement.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
