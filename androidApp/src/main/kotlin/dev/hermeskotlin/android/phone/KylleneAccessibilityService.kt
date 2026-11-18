package dev.hermeskotlin.android.phone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The phone's screen, made reachable. Android allows an app to touch another app's screen only through a service the
 * user turns on themselves, in Settings → Accessibility — there is no quieter route, and this is it.
 *
 * It keeps [instance] while it is on, so a tool call can act at once; losing the instance is what makes
 * `phone_control` answer "screen control is off".
 */
class KylleneAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Listening is what keeps the service alive and the window tree fresh; nothing is recorded or reported.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: KylleneAccessibilityService? = null
            private set
    }
}
