package dev.hermeskotlin.core.voice.gemini

/**
 * The microphone and speaker a Gemini Live call owns. Gemini streams continuously in both directions, so this
 * is not Kyllene's record-then-send recorder: it hands back raw 16 kHz frames as they arrive and plays raw
 * 24 kHz frames back.
 *
 * Nothing here touches the network, so the desktop target can stay silent.
 */
interface LiveAudio {
    /** Opens the microphone; [onLevel] is called with 0..1 loudness, polled for the orb. */
    fun start(onLevel: (Float) -> Unit)

    /** The next frame of microphone audio, 16-bit little-endian mono at [sampleRate]; null when muted or stopped. */
    suspend fun read(): ByteArray?

    /** Plays one chunk of the model's speech. */
    fun play(pcm: ByteArray, sampleRate: Int)

    /** Drops what is buffered: the user started talking and the model must stop mid-word. */
    fun flush()

    fun setMuted(muted: Boolean)

    /**
     * Whether the phone cancels the speaker's echo out of the microphone. Where it doesn't, the microphone is
     * held while the voice plays — the call would otherwise interrupt itself and never finish a sentence.
     */
    val cancelsEcho: Boolean get() = true

    /** Releases the microphone and the speaker. Safe to call twice. */
    fun stop()
}

/** The microphone and speaker on this platform; null where the platform has no voice (the desktop app). */
expect fun createLiveAudio(): LiveAudio?

/** 16 kHz mono, the rate Gemini Live takes. */
internal const val LIVE_INPUT_SAMPLE_RATE = 16_000

/** 24 kHz mono, the rate Gemini Live answers in. */
internal const val LIVE_OUTPUT_SAMPLE_RATE = 24_000
