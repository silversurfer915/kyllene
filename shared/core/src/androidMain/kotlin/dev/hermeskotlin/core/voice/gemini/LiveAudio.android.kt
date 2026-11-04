package dev.hermeskotlin.core.voice.gemini

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

actual fun createLiveAudio(): LiveAudio? = AndroidLiveAudio()

/**
 * The phone's microphone and speaker for a Gemini Live call: 16 kHz up, 24 kHz down, both mono PCM16.
 *
 * Echo cancellation is the difference between "you can talk over it" and "it hears itself". It only exists on
 * the VOICE_COMMUNICATION source, and some devices don't offer it at all — the call still works, and the adapter
 * then holds the microphone while the voice plays.
 *
 * The call takes the phone's audio focus and switches it to call mode, the way a phone call does: the routing,
 * the canceller's reference, and getting out of the way of anything more important all come from that. Another
 * app taking the audio pauses both directions until it hands it back.
 */
private class AndroidLiveAudio : LiveAudio {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Bounded: a backlog of microphone frames is worse than a dropped one, it arrives late.
    private val frames = Channel<ByteArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val audioManager = LiveAudioContext.context?.getSystemService(AudioManager::class.java)

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var reader: Job? = null
    private var trackRate = 0
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    // Held so the effects aren't finalized — and quietly switched off — the moment they are created.
    private var echoCanceller: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile private var muted = false
    @Volatile private var paused = false

    /** True only where the phone's own canceller is on; otherwise the model's voice would reach the microphone. */
    @Volatile override var cancelsEcho = false
        private set

    @SuppressLint("MissingPermission") // The screen asks for RECORD_AUDIO before starting.
    override fun start(onLevel: (Float) -> Unit) {
        if (record != null) return
        takeAudio()
        val minBuffer = AudioRecord.getMinBufferSize(
            LIVE_INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val mic = AudioRecord(
            // VOICE_COMMUNICATION is the source that carries the echo canceller.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            LIVE_INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES * 4),
        )
        if (mic.state != AudioRecord.STATE_INITIALIZED) {
            mic.release()
            error("The microphone isn't available.")
        }
        echoCanceller = runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(mic.audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
        }.getOrNull()
        noiseSuppressor = runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(mic.audioSessionId)?.apply { enabled = true } else null
        }.getOrNull()
        cancelsEcho = echoCanceller?.enabled == true
        mic.startRecording()
        record = mic

        reader = scope.launch {
            val buffer = ByteArray(FRAME_BYTES)
            while (true) {
                val read = mic.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                onLevel(level(buffer, read))
                if (muted || paused) continue
                frames.trySend(buffer.copyOf(read))
            }
        }
    }

    override suspend fun read(): ByteArray? = frames.receiveCatching().getOrNull()

    override fun play(pcm: ByteArray, sampleRate: Int) {
        // Another app has the audio: none of the voice goes out until it hands it back.
        if (paused) return
        val out = track?.takeIf { trackRate == sampleRate } ?: openTrack(sampleRate) ?: return
        out.write(pcm, 0, pcm.size)
    }

    override fun flush() {
        val out = track ?: return
        runCatching {
            out.pause()
            out.flush()
            out.play()
        }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    override fun stop() {
        reader?.cancel()
        reader = null
        scope.cancel()
        frames.close()
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
        runCatching {
            echoCanceller?.release()
            noiseSuppressor?.release()
        }
        echoCanceller = null
        noiseSuppressor = null
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
        trackRate = 0
        releaseAudio()
    }

    /** The call's audio focus, mode and routing: a phone call's, so the speaker and the canceller behave as one. */
    private fun takeAudio() {
        val manager = audioManager ?: return
        previousMode = manager.mode
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        // Hands-free unless a headset is in, which is how a call on the phone is routed.
        runCatching { manager.isSpeakerphoneOn = !manager.isWiredHeadsetOn && !manager.isBluetoothScoOn }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(callAttributes())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        paused = true
                        // The user is on something else now: the voice must not talk under it.
                        flush()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> paused = false
                }
            }
            .build()
        focus = request
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun releaseAudio() {
        val manager = audioManager ?: return
        focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        focus = null
        runCatching { manager.isSpeakerphoneOn = false }
        runCatching { manager.mode = previousMode }
    }

    private fun callAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun openTrack(sampleRate: Int): AudioTrack? {
        runCatching {
            track?.stop()
            track?.release()
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val opened = AudioTrack.Builder()
            .setAudioAttributes(callAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (opened.state != AudioTrack.STATE_INITIALIZED) {
            opened.release()
            return null
        }
        opened.play()
        track = opened
        trackRate = sampleRate
        return opened
    }

    /** RMS of the frame, scaled so ordinary speech reads near 1 — the same scale Kyllene's recorder uses. */
    private fun level(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort().toInt()
            sum += (sample * sample).toDouble()
            index += 2
        }
        val samples = (length / 2).coerceAtLeast(1)
        return (sqrt(sum / samples) / 32768.0 * 4).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        /** 20 ms of 16 kHz mono PCM16: 320 samples. */
        const val FRAME_BYTES = 640
    }
}
