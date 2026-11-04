package dev.hermeskotlin.core.voice.gemini

import android.content.Context

/**
 * Holds the application context the call's audio needs (the AudioManager), set once from the platform module at
 * startup. Null in host tests and before startup: the call then works without audio focus or call routing.
 */
internal object LiveAudioContext {
    @Volatile var context: Context? = null
}
