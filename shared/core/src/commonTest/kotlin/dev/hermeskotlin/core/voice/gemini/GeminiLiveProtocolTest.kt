package dev.hermeskotlin.core.voice.gemini

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The wire shapes Conduit's Swift defines, asserted against the Kotlin port. */
@OptIn(ExperimentalEncodingApi::class)
class GeminiLiveProtocolTest {

    @Test
    fun setupCarriesTheLiveContract() {
        val setup = Json.parseToJsonElement(
            GeminiLiveProtocol.setupMessage(
                systemInstruction = "be terse",
                voice = "Aoede",
            ),
        ).jsonObject["setup"]!!.jsonObject

        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        val generation = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", generation["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(
            "Aoede",
            generation["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!
                .jsonObject["voiceName"]!!.jsonPrimitive.content,
        )

        val realtime = setup["realtimeInputConfig"]!!.jsonObject
        assertEquals(
            "START_OF_ACTIVITY_INTERRUPTS",
            realtime["activityHandling"]!!.jsonPrimitive.content,
        )
        assertEquals(false, realtime["automaticActivityDetection"]!!.jsonObject["disabled"]!!.jsonPrimitive.content.toBoolean())

        // Opting in with empty objects: the server only sends these when the keys are present.
        for (key in listOf("sessionResumption", "contextWindowCompression", "inputAudioTranscription", "outputAudioTranscription")) {
            assertTrue(setup.containsKey(key), "setup is missing $key")
        }
        val tools = setup["tools"]!!.jsonArray
        assertEquals(1, tools.size, "googleSearch is off in this call")
    }

    @Test
    fun declarationsRideInsideTools() {
        val setup = Json.parseToJsonElement(
            GeminiLiveProtocol.setupMessage(
                systemInstruction = "x",
                functions = listOf(
                    GeminiLiveProtocol.FunctionDeclaration(
                        name = "get_time",
                        description = "now",
                        parameters = buildJsonObject { put("type", "OBJECT") },
                    ),
                ),
                googleSearch = false,
            ),
        ).jsonObject["setup"]!!.jsonObject
        val declarations = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, declarations.size)
        assertEquals("get_time", declarations[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("BLOCKING", declarations[0].jsonObject["behavior"]!!.jsonPrimitive.content)
    }

    @Test
    fun audioFrameRoundTrips() {
        val pcm = byteArrayOf(1, 0, 2, 0, -1, -1)
        val frame = Json.parseToJsonElement(GeminiLiveProtocol.audioMessage(pcm)).jsonObject
        val audio = frame["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertTrue(pcm.contentEquals(Base64.decode(audio["data"]!!.jsonPrimitive.content)))
    }

    @Test
    fun decodesSetupComplete() {
        val events = GeminiLiveProtocol.decode("""{"setupComplete":{}}""")
        assertEquals(listOf(GeminiLiveProtocol.ServerEvent.SetupComplete), events)
    }

    @Test
    fun decodesModelAudioAndTranscriptionFromOneFrame() {
        val pcm = byteArrayOf(9, 8, 7, 6)
        val frame = """
            {"serverContent":{
              "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${Base64.encode(pcm)}"}}]},
              "outputTranscription":{"text":"hello"},
              "turnComplete":true
            }}
        """.trimIndent()

        val events = GeminiLiveProtocol.decode(frame)
        val audio = events.filterIsInstance<GeminiLiveProtocol.ServerEvent.Audio>().single()
        assertTrue(pcm.contentEquals(audio.pcm))
        assertEquals(24_000, audio.sampleRate)
        assertEquals("hello", events.filterIsInstance<GeminiLiveProtocol.ServerEvent.OutputTranscription>().single().text)
        assertTrue(events.contains(GeminiLiveProtocol.ServerEvent.TurnComplete))
    }

    @Test
    fun ignoresNonAudioInlineData() {
        val frame = """
            {"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AAAA"}}]}}}
        """.trimIndent()
        assertTrue(GeminiLiveProtocol.decode(frame).isEmpty())
    }

    @Test
    fun decodesInterruptedAndInputTranscription() {
        val frame = """{"serverContent":{"inputTranscription":{"text":"stop"},"interrupted":true}}"""
        val events = GeminiLiveProtocol.decode(frame)
        assertTrue(events.contains(GeminiLiveProtocol.ServerEvent.Interrupted))
        assertEquals("stop", events.filterIsInstance<GeminiLiveProtocol.ServerEvent.InputTranscription>().single().text)
    }

    @Test
    fun decodesToolCallsWithStringifiedArguments() {
        val frame = """
            {"toolCall":{"functionCalls":[
              {"id":"call_1","name":"get_time","args":{}},
              {"id":"call_2","name":"start_job","args":{"instructions":"plan the trip","urgent":true}}
            ]}}
        """.trimIndent()

        val call = GeminiLiveProtocol.decode(frame)
            .filterIsInstance<GeminiLiveProtocol.ServerEvent.ToolCall>().single()
        assertEquals(2, call.calls.size)
        assertEquals("call_1", call.calls[0].id)
        assertEquals(emptyMap(), call.calls[0].arguments)
        assertEquals("plan the trip", call.calls[1].arguments["instructions"])
        assertEquals("true", call.calls[1].arguments["urgent"])
    }

    @Test
    fun decodesGoAwayAndResumption() {
        val goAway = GeminiLiveProtocol.decode("""{"goAway":{"timeLeft":"9.5s"}}""")
            .filterIsInstance<GeminiLiveProtocol.ServerEvent.GoAway>().single()
        assertEquals(9.5, goAway.timeLeftSeconds)

        val update = GeminiLiveProtocol.decode("""{"sessionResumptionUpdate":{"newHandle":"h-1","resumable":true}}""")
            .filterIsInstance<GeminiLiveProtocol.ServerEvent.ResumptionUpdate>().single()
        assertEquals("h-1", update.handle)
        assertTrue(update.resumable)
    }

    @Test
    fun garbageIsIgnored() {
        assertTrue(GeminiLiveProtocol.decode("not json").isEmpty())
        assertTrue(GeminiLiveProtocol.decode("""{"unknown":{"a":1}}""").isEmpty())
    }

    @Test
    fun sampleRateParsing() {
        assertEquals(24_000, GeminiLiveProtocol.sampleRateFromMime("audio/pcm;rate=24000"))
        assertEquals(16_000, GeminiLiveProtocol.sampleRateFromMime("audio/pcm; rate=16000"))
        assertEquals(null, GeminiLiveProtocol.sampleRateFromMime("audio/pcm"))
        assertEquals(null, GeminiLiveProtocol.sampleRateFromMime("audio/pcm;rate=nope"))
    }

    @Test
    fun tokenParsesAndAttachesItsCredential() {
        val token = GeminiLiveToken.parse(
            """{"ok":true,"token":"tok-1","model":"gemini-3.8-live","voice":"Aoede",""" +
                """"websocket_url":"wss://example.test/BidiGenerateContentConstrained",""" +
                """"expires_at":"2026-11-02T12:20:46Z","new_session_expires_at":"2026-11-02T11:51:46Z"}""",
        )
        assertEquals("gemini-3.8-live", token.model)
        assertEquals("Aoede", token.voice)
        assertEquals(
            "wss://example.test/BidiGenerateContentConstrained?access_token=tok-1",
            token.connectionUrl(),
        )
        // A URL the host already scoped keeps its query and a single credential.
        val scoped = token.copy(webSocketUrl = "wss://example.test/x?profile=default")
        assertEquals("wss://example.test/x?profile=default&access_token=tok-1", scoped.connectionUrl())
    }

    @Test
    fun tokenRefusesAHostThatCannotMint() {
        val error = assertFailsWith<IllegalStateException> {
            GeminiLiveToken.parse("""{"ok":false,"reason":"no_api_key"}""")
        }
        assertEquals("no_api_key", error.message)
        assertNotNull(error.message)
    }
}
