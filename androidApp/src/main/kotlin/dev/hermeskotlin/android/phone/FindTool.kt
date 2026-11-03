package dev.hermeskotlin.android.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * `phone_find`: makes the phone announce itself so the user can find it — the alarm sound at full volume, or a buzz —
 * for a few seconds. "Where's my phone" is a question about the device, so this takes no approval; it stops on its own,
 * and `action=stop` ends it early.
 *
 * The alarm usage is the one stream Do Not Disturb lets through by default, which is the point: a phone that is ringing
 * because it was asked to should be heard even in a silent room.
 */
internal class FindTool(private val context: Context) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "find-phone").apply { isDaemon = true }
    }
    private val lock = Any()
    private var tone: Ringtone? = null
    private var buzzer: Vibrator? = null
    private var stopper: ScheduledFuture<*>? = null

    fun run(args: JsonObject): JsonObject {
        val action = args.string("action")?.lowercase() ?: "ring"
        if (action == "stop") return stop()
        if (action != "ring" && action != "vibrate") throw PhoneToolException("action is ring, vibrate or stop.")
        val seconds = (args.int("seconds") ?: DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)
        // One at a time: a second "find" replaces the first instead of playing over it.
        stopNow()
        if (action == "ring") ring(seconds) else buzz(seconds)
        return buildJsonObject {
            put("finding", action)
            put("seconds", seconds)
            put("note", if (action == "ring") {
                "It's ringing at full volume for $seconds seconds; stop it with action=stop."
            } else {
                "It's buzzing for $seconds seconds; stop it with action=stop."
            })
        }
    }

    /** Answers at once: the sound runs on the scheduler and stops itself. */
    private fun ring(seconds: Int) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: throw PhoneToolException("This phone has no alarm sound set.")
        val sound = RingtoneManager.getRingtone(context, uri)
            ?: throw PhoneToolException("This phone has no alarm sound set.")
        sound.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) sound.isLooping = true
        synchronized(lock) {
            sound.play()
            tone = sound
            stopper = scheduler.schedule({ stopNow() }, seconds.toLong(), TimeUnit.SECONDS)
        }
    }

    private fun buzz(seconds: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        } ?: throw PhoneToolException("This phone can't vibrate.")
        // Repeating pulse: a buzz the user can hear from the next room, not one short blip.
        vibrator.vibrate(VibrationEffect.createWaveform(BUZZ_PATTERN, 0))
        synchronized(lock) {
            buzzer = vibrator
            stopper = scheduler.schedule({ stopNow() }, seconds.toLong(), TimeUnit.SECONDS)
        }
    }

    private fun stop(): JsonObject {
        val was = stopNow()
        return buildJsonObject {
            put("finding", "stopped")
            put("was_finding", was)
        }
    }

    /** True when something was going. Safe to call twice, and from the scheduler. */
    private fun stopNow(): Boolean {
        synchronized(lock) {
            stopper?.cancel(false)
            stopper = null
            val sound = tone
            tone = null
            val buzzerNow = buzzer
            buzzer = null
            runCatching { sound?.stop() }
            runCatching { buzzerNow?.cancel() }
            return sound != null || buzzerNow != null
        }
    }

    private companion object {
        const val DEFAULT_SECONDS = 10
        const val MAX_SECONDS = 60
        val BUZZ_PATTERN = longArrayOf(0, 600, 400)
    }
}
