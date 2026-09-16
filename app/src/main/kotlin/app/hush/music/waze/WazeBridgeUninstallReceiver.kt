/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the outcome of a [WazeBridgeUninstall.request].
 *
 * The platform answers an uninstall request here rather than to the screen that asked, so this is
 * where the confirmation gets raised - [PackageInstaller.STATUS_PENDING_USER_ACTION] carries the
 * intent for it - and where the result is published for the bridge screen to act on.
 */
class WazeBridgeUninstallReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val packageName = WazeBridgeUninstall.packageNameOf(intent)
        when (WazeBridgeUninstall.statusOf(intent)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = confirmationIntent(intent)
                if (confirmation == null) {
                    WazeBridgeUninstall.publish(
                        packageName,
                        WazeBridgeUninstall.Status.FAILED,
                        "The confirmation dialog could not be opened",
                    )
                    return
                }
                WazeBridgeUninstall.publish(packageName, WazeBridgeUninstall.Status.PENDING_CONFIRMATION)
                try {
                    // The user tapped Uninstall/Repair in Hush, so Hush is in the foreground and this
                    // is allowed; the system raised this intent expecting exactly this launch.
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (error: Exception) {
                    WazeBridgeUninstall.publish(
                        packageName,
                        WazeBridgeUninstall.Status.FAILED,
                        error.message ?: "The confirmation dialog could not be opened",
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                WazeBridgeUninstall.publish(packageName, WazeBridgeUninstall.Status.SUCCEEDED)
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                WazeBridgeUninstall.publish(packageName, WazeBridgeUninstall.Status.FAILED, message)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
}
