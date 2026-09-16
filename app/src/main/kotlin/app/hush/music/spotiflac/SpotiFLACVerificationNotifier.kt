/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hush.music.R

/**
 * The manual verification offer, as a notification.
 *
 * Automatic verification is unchanged: when the in-app WebView can run Cloudflare's check the
 * challenge solves itself and nobody sees this. It appears when that route could not finish - a
 * car head unit whose embedded WebView is older than Cloudflare supports can never produce a
 * token, so an automatic run there can only time out.
 *
 * On such a device the app UI may also not be in front (the user is in their music app, in
 * navigation, anywhere), so a notification is the one place a manual action stays reachable:
 * one tap opens the challenge in the device's browser, and the grant comes back to Hush by
 * itself - no code to copy, which is the part that never worked on a car.
 */
object SpotiFLACVerificationNotifier {

    private const val CHANNEL_ID = "spotiflac_verification"

    /** One notification per source, so two sources cannot overwrite each other's action. */
    private fun notificationId(extensionId: String): Int = NOTIFICATION_ID_BASE + extensionId.hashCode()

    private const val NOTIFICATION_ID_BASE = 8_100

    /** Shows (or refreshes) the manual verification offer for one source. */
    fun notify(context: Context, extensionId: String?) {
        val id = extensionId?.trim().orEmpty()
        if (id.isEmpty()) return
        runCatching {
            val appContext = context.applicationContext
            ensureChannel(appContext)

            // Straight to the manual route, not to the in-app dialog: this notification exists
            // for the case where the in-app route is the thing that failed.
            val verifyIntent = Intent(appContext, SpotiFLACManualVerifyReceiver::class.java)
                .setAction(SpotiFLACManualVerifyReceiver.ACTION_VERIFY_IN_BROWSER)
                .putExtra(SpotiFLACManualVerifyReceiver.EXTRA_EXTENSION_ID, id)
            val verifyPendingIntent = PendingIntent.getBroadcast(
                appContext,
                notificationId(id),
                verifyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle("Verify $id for SpotiFLAC")
                .setContentText("Automatic verification failed. Tap to solve the check in your browser.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Automatic verification failed on this device, so $id needs one manual " +
                            "check. It opens in your browser and Hush finishes on its own - there " +
                            "is nothing to copy back.",
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addAction(0, "Verify in browser", verifyPendingIntent)
                .setContentIntent(verifyPendingIntent)
                .build()

            // A denied notification is not an error here: the in-app notice carries the same
            // browser button, so the offer is never lost - it just lives somewhere else.
            val permitted =
                NotificationManagerCompat.from(appContext).areNotificationsEnabled() &&
                    (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(
                                appContext,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        )
            if (!permitted) {
                SpotiFLACDiag.log(
                    "manual verification for $id stays in the app: notifications are not permitted",
                )
                return@runCatching
            }

            NotificationManagerCompat.from(appContext).notify(notificationId(id), notification)
            SpotiFLACDiag.log("manual verification offered for $id (notification)")
        }.onFailure { SpotiFLACDiag.log("verification notification failed for $id: ${it.message}") }
    }

    /** Removes the offer once the source no longer needs it. */
    fun clear(context: Context, extensionId: String?) {
        val id = extensionId?.trim().orEmpty()
        if (id.isEmpty()) return
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(id))
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SpotiFLAC verification",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Offers a manual verification when automatic verification fails."
            },
        )
    }
}
