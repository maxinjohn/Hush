/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.hush.music.constants.WazeTargetApp
import timber.log.Timber

/**
 * Tells the installed Bridges that Hush is here, so a Waze session that began first is picked up.
 *
 * The Bridges are what Waze sees, but they hold no music and no player: every track, position and
 * transport command crosses to Hush. That crossing starts with the Bridge asking Hush for its
 * state, and until now the only triggers for asking were Waze's own bind and a "Reconnect" button
 * inside Hush's settings screen. Neither fires for the reported case - Waze opened before Hush, so
 * the Bridge started on its own, found no player yet, and kept showing a dead panel; opening Hush
 * afterwards changed nothing, because nothing told the Bridge that the player it had given up
 * waiting for now existed.
 *
 * So Hush announces itself instead: the process start and the playback service coming up both
 * broadcast the same reconnect request the button sends, to the same packages, with the same
 * permission. The Bridge re-binds to Waze's SDK, re-starts Hush's service and asks for a snapshot -
 * all idempotent, which is what makes this safe to fire whenever Hush becomes available rather
 * than only when a user is looking at a settings screen.
 */
object WazeBridgeAutoReconnect {

    /** The Bridge's reconnect action, shared with the manual button in Waze settings. */
    const val ACTION_RECONNECT = "app.hush.music.waze.ACTION_RECONNECT"

    /**
     * The permission a Bridge's reconnect receiver requires.
     *
     * Hush holds it (declared in its manifest), so only Hush - or an app the user granted it - can
     * make a Bridge rebind. Without it, an unrelated app could restart the Bridge's connection
     * state at will.
     */
    private const val CONTROL_PERMISSION = "app.hush.music.permission.WAZE_BRIDGE_CONTROL"

    /**
     * The Bridges installed on this device.
     *
     * A Bridge is recognised the same way the snapshot publisher recognises one: by the `SHIM`
     * meta-data flag it declares, or - for Bridges installed before that flag existed - by the
     * label its target app was built with. Hush must not broadcast to a package that merely shares
     * a name with one of the real music apps, because that app has no such receiver and, worse,
     * Hush would be sending it a control request it did not ask for.
     */
    fun installedBridgePackages(context: Context): List<String> {
        val packageManager = context.packageManager
        val found = mutableListOf<String>()
        for (target in WazeTargetApp.entries) {
            try {
                val appInfo = packageManager.getApplicationInfo(target.packageName, PackageManager.GET_META_DATA)
                val isCurrentShim = appInfo.metaData?.getBoolean(SHIM_META_DATA, false) == true
                val isLegacyShim =
                    appInfo.loadLabel(packageManager).toString() == legacyLabel(target)
                if (isCurrentShim || isLegacyShim) found.add(target.packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed: the common case, and not a problem.
            } catch (_: Exception) {
                // A package manager that refuses to describe a package tells us nothing either way;
                // skipping it is better than failing the whole sweep.
            }
        }
        return found
    }

    /**
     * Asks every installed Bridge to re-attach, returning how many were asked.
     *
     * Failures are per package and swallowed: one Bridge that cannot be woken must not stop the
     * others from being re-attached, and a device with no Bridges at all is simply a no-op.
     */
    fun reconnect(context: Context, reason: String): Int {
        val packages = installedBridgePackages(context)
        if (packages.isEmpty()) {
            android.util.Log.i(TAG, "reconnect ($reason): no Bridge installed")
            return 0
        }
        var sent = 0
        val outcomes = mutableListOf<String>()
        for (packageName in packages) {
            val byBroadcast = askByBroadcast(context, packageName)
            val reached = byBroadcast || askServiceDirectly(context, packageName)
            if (reached) sent++
            val route = when {
                byBroadcast -> "broadcast"
                reached -> "service"
                else -> "failed"
            }
            outcomes += "$packageName=$route"
        }
        // Logged through both channels on purpose: this is the one line that says whether a Bridge
        // that has been sitting on a dead panel was actually reached, and it is read from a device
        // log while a car is in front of the user - where the in-memory Timber buffer is not.
        android.util.Log.i(TAG, "reconnect ($reason): $sent of ${packages.size} reached [${outcomes.joinToString()}]")
        Timber.tag(TAG).d("Asked %d Bridge(s) to reconnect (%s)", sent, reason)
        return sent
    }

    /**
     * The preferred route: the Bridge's own protected receiver.
     *
     * Its permission is `signature` level, so the copy of Hush that can use this is the one signed
     * with the Bridges' key - which is every build that ships to a user, and deliberately not a
     * debug build signed with a throwaway key. That is why there is a second route below rather
     * than a swallowed failure here.
     */
    private fun askByBroadcast(context: Context, packageName: String): Boolean =
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_RECONNECT).apply { setPackage(packageName) },
                CONTROL_PERMISSION,
            )
            true
        }.getOrElse { error ->
            // Expected on a build that cannot hold the Bridges' signature-level permission (a debug
            // install signed with a throwaway key, or a Bridge from a different channel). Said out
            // loud, because the fallback below is best effort and this is the only trace of why.
            android.util.Log.w(TAG, "Broadcast to $packageName needs the bridge permission: ${error.message}")
            false
        }

    /**
     * The fallback: ask the Bridge's service directly, by component.
     *
     * The Bridges export exactly one service, the one Waze connects to, and it already handles this
     * action (that is what the receiver above starts). Reaching it by component needs no permission
     * because the action carries no authority the service does not already have: it re-attaches to
     * Waze and asks Hush for a snapshot, which is what it does on every Waze bind anyway.
     *
     * Best effort by nature - a background service start is refused outright on Android 12 and up
     * unless the caller is in the foreground. On the older head units this exists for (Android 11 on
     * the reporting car) it goes through, and where it does not, the protected route above was the
     * one that worked anyway.
     */
    private fun askServiceDirectly(context: Context, packageName: String): Boolean {
        val intent =
            Intent(ACTION_RECONNECT).setComponent(ComponentName(packageName, SHIM_SERVICE_CLASS))
        // Plain start first, exactly as the Bridge's own receiver does it: on the older head units
        // this exists for (Android 11 on the reporting car) that is what the platform allows from a
        // foreground app, and the Bridge promotes itself to a foreground service in onStartCommand
        // anyway. A platform that insists on the foreground form being requested up front is
        // covered by the second attempt.
        val started =
            runCatching { context.startService(intent); true }
                .recoverCatching { context.startForegroundService(intent); true }
        return started.fold(
            onSuccess = { true },
            onFailure = { error ->
                android.util.Log.w(TAG, "Could not ask $packageName to reconnect by any route", error)
                Timber.tag(TAG).w(error, "Could not ask %s to reconnect by any route", packageName)
                false
            },
        )
    }

    /** The one service every Bridge exports, which Waze itself connects to. */
    internal const val SHIM_SERVICE_CLASS = "app.hush.music.waze.WazeIntegrationService"

    /** The `SHIM` flag a Bridge declares in its manifest. */
    const val SHIM_META_DATA = "app.hush.music.waze.SHIM"

    /** The label a Bridge built before [SHIM_META_DATA] existed carries. */
    internal fun legacyLabel(target: WazeTargetApp): String =
        when (target) {
            WazeTargetApp.SPOTIFY -> "Hush (Spotify)"
            WazeTargetApp.YOUTUBE_MUSIC -> "Hush (YouTube Music)"
            WazeTargetApp.DEEZER -> "Hush (Deezer)"
        }

    private const val TAG = "WazeBridgeReconnect"
}
