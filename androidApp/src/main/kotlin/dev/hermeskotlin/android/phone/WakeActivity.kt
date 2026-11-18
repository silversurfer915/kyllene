package dev.hermeskotlin.android.phone

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * The screen, turned on from a tool call. An app cannot power the panel directly; what it can do is open an activity
 * that asks for the screen (`setTurnScreenOn`) and to be shown over the lock screen (`setShowWhenLocked`). It draws
 * nothing and closes at once, leaving the phone awake with whatever is behind it still locked.
 *
 * When the call asks for the keyguard to step aside, `requestDismissKeyguard` shows the phone's own unlock prompt —
 * PIN, pattern, fingerprint, face. That prompt is the point: the app never learns the credential.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val dismiss = intent?.getBooleanExtra(EXTRA_DISMISS, false) == true
        if (savedInstanceState == null && dismiss && keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = finish()
                    override fun onDismissCancelled() = finish()
                    override fun onDismissError() = finish()
                },
            )
        } else {
            finish()
        }
    }

    companion object {
        private const val EXTRA_DISMISS = "dismiss"

        /** Turn the screen on. [dismiss] also asks the lock screen to step aside, which needs the user's unlock. */
        fun start(context: Context, dismiss: Boolean = false) {
            val intent = Intent(context, WakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_DISMISS, dismiss)
            runCatching { context.startActivity(intent) }
        }
    }
}
