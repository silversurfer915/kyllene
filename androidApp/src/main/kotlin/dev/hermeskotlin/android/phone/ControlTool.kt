package dev.hermeskotlin.android.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `phone_control`: drives the phone's screen the way a hand would — tap, type, swipe, scroll, back, home, recents — and
 * reads what is on it. It works through the accessibility service, which is the only way an app may touch another
 * app's screen.
 *
 * A locked phone is the wall: an accessibility service cannot get past the keyguard. `wake` turns the screen on and
 * asks the lock screen to step aside, but the PIN or fingerprint is the user's to give.
 */
internal class ControlTool(private val context: android.content.Context) {

    fun run(args: JsonObject): JsonObject {
        val service = KylleneAccessibilityService.instance
            ?: throw PhoneToolException(
                "Screen control is off. Turn Kyllene on in Settings → Accessibility, then ask again.",
            )
        return when (val action = args.string("action")?.lowercase() ?: "read") {
            "read" -> read(service, args.int("limit") ?: DEFAULT_LINES)
            "tap" -> tap(service, args)
            "long_press" -> tap(service, args, long = true)
            "swipe" -> swipe(service, args)
            "scroll" -> scroll(service, args)
            "type" -> type(service, args)
            "back" -> global(service, AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> global(service, AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> global(service, AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> global(service, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "wake" -> wake()
            else -> throw PhoneToolException(
                "action is read, tap, long_press, swipe, scroll, type, back, home, recents, notifications or wake.",
            )
        }
    }

    /** What is on screen now: a line per thing that says something, with where it is. */
    private fun read(service: AccessibilityService, limit: Int): JsonObject {
        val root = service.rootInActiveWindow ?: throw PhoneToolException("Nothing is on screen to read.")
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || lines.size >= limit || depth > MAX_DEPTH) return
            val label = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (label != null) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val kind = node.className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: "View"
                lines += "${rect.centerX()},${rect.centerY()} $kind \"${label.take(MAX_LABEL_CHARS)}\""
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val pkg = root.packageName?.toString()
        return buildJsonObject {
            put("app", pkg ?: "unknown")
            putJsonArray("lines") { lines.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("truncated", lines.size >= limit)
        }
    }

    private fun tap(service: AccessibilityService, args: JsonObject, long: Boolean = false): JsonObject {
        val x = args.float("x") ?: throw PhoneToolException("x is missing.")
        val y = args.float("y") ?: throw PhoneToolException("y is missing.")
        val path = Path().apply { moveTo(x, y) }
        val duration = if (long) LONG_PRESS_MS else TAP_MS
        dispatch(service, path, duration)
        return done(if (long) "long-pressed" else "tapped", x, y)
    }

    private fun swipe(service: AccessibilityService, args: JsonObject): JsonObject {
        val x1 = args.float("x") ?: throw PhoneToolException("x is missing.")
        val y1 = args.float("y") ?: throw PhoneToolException("y is missing.")
        val x2 = args.float("x2") ?: throw PhoneToolException("x2 is missing.")
        val y2 = args.float("y2") ?: throw PhoneToolException("y2 is missing.")
        val ms = (args.int("duration_ms") ?: SWIPE_MS).coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(service, path, ms.toLong())
        return buildJsonObject {
            put("gesture", "swipe")
            put("from", "$x1,$y1")
            put("to", "$x2,$y2")
            put("duration_ms", ms)
            put("note", "Read the screen again to see where it landed.")
        }
    }

    /** The nearest list that scrolls, moved one page at a time. */
    private fun scroll(service: AccessibilityService, args: JsonObject): JsonObject {
        val direction = args.string("direction")?.lowercase() ?: "down"
        val back = direction == "up"
        val root = service.rootInActiveWindow ?: throw PhoneToolException("Nothing is on screen to scroll.")
        val target = findScrollable(root) ?: throw PhoneToolException("Nothing on this screen scrolls.")
        val moved = target.performAction(
            if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        )
        return buildJsonObject {
            put("scrolled", moved)
            put("direction", direction)
            if (!moved) put("note", "It refused: that list may already be at the end.")
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun type(service: AccessibilityService, args: JsonObject): JsonObject {
        val text = args.string("text") ?: throw PhoneToolException("text is missing.")
        val root = service.rootInActiveWindow ?: throw PhoneToolException("Nothing is on screen to type into.")
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw PhoneToolException("No field has the cursor. Tap one first, then type.")
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val typed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!typed) throw PhoneToolException("That field wouldn't take the text.")
        return buildJsonObject {
            put("typed", text.length)
            put("note", "It's in the field, not sent: tap the send button when it should go.")
        }
    }

    private fun global(service: AccessibilityService, action: Int): JsonObject {
        val name = when (action) {
            AccessibilityService.GLOBAL_ACTION_BACK -> "back"
            AccessibilityService.GLOBAL_ACTION_HOME -> "home"
            AccessibilityService.GLOBAL_ACTION_RECENTS -> "recents"
            else -> "notifications"
        }
        val ok = service.performGlobalAction(action)
        return buildJsonObject {
            put("did", name)
            put("worked", ok)
        }
    }

    /**
     * Turns the screen on and opens the phone over the lock screen, asking the keyguard to step aside. The PIN or
     * fingerprint is the user's: an app cannot give it.
     */
    private fun wake(): JsonObject {
        WakeActivity.start(context)
        return buildJsonObject {
            put("woken", true)
            put("note", "The screen is on over the lock screen. If it asks to unlock, only they can.")
        }
    }

    private fun dispatch(service: AccessibilityService, path: Path, ms: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        // The callback is asynchronous; a refused gesture is rare (another service or a secure window) and the read
        // that follows says what happened anyway.
        service.dispatchGesture(gesture, null, null)
    }

    private fun done(what: String, x: Float, y: Float): JsonObject = buildJsonObject {
        put("gesture", what)
        put("at", "$x,$y")
        put("note", "Read the screen again to see what it did.")
    }

    private companion object {
        const val DEFAULT_LINES = 40
        const val MAX_DEPTH = 40
        const val MAX_LABEL_CHARS = 120
        const val TAP_MS = 40L
        const val LONG_PRESS_MS = 600L
        const val SWIPE_MS = 300
        const val MIN_SWIPE_MS = 100
        const val MAX_SWIPE_MS = 2_000
    }
}
