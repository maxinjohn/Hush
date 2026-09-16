/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Entry point for the manual verification action.
 *
 * Triggered by the notification's "Verify in browser" action, and by Hush itself wherever a
 * one-tap manual repair is offered without a screen to host it. The work is handed to
 * [SpotiFLACBrowserVerification], which owns the challenge, the browser and the wait for the
 * grant, so this stays a thin adapter.
 */
class SpotiFLACManualVerifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_VERIFY_IN_BROWSER) return
        val extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID)
        SpotiFLACDiag.log("manual verification requested for ${extensionId ?: "?"} (notification)")
        SpotiFLACVerificationNotifier.clear(context, extensionId)
        SpotiFLACBrowserVerification.startInProcess(context, extensionId)
    }

    companion object {
        /** Hush's own action; the receiver is not exported, so nothing else can send it. */
        const val ACTION_VERIFY_IN_BROWSER = "app.hush.music.action.SPOTIFLAC_VERIFY_IN_BROWSER"

        const val EXTRA_EXTENSION_ID = "extension_id"
    }
}
