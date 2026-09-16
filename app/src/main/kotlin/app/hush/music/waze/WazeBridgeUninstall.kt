/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Removes an installed Bridge directly, through the platform's package installer.
 *
 * The alternative - sending the user to the App Info screen and waiting for them to find
 * "Uninstall" - is several taps and a screen away from the Bridge they are trying to repair, and it
 * was the only route the repair had. [PackageInstaller.uninstall] takes the same confirmation the
 * user would have given there, but it is raised by Hush as a dialog, and its result comes back to
 * [WazeBridgeUninstallReceiver] instead of to whatever screen happens to be on top.
 *
 * The request can legitimately be refused (an OEM build that restricts it, or a Hush that is not the
 * installer of record for that Bridge), which is why [request] reports whether it was accepted: the
 * caller then falls back to the App Info screen rather than leaving the user with nothing.
 */
object WazeBridgeUninstall {

    private const val TAG = "WazeBridge"

    /** Why the request ended, and for which package. */
    enum class Status {
        /** The platform is showing (or about to show) its own confirmation dialog. */
        PENDING_CONFIRMATION,

        /** The package is gone. */
        SUCCEEDED,

        /** Refused or cancelled; [Result.message] carries the platform's explanation when there is one. */
        FAILED,
    }

    /**
     * A single uninstall attempt's outcome.
     *
     * [sequence] exists so a screen can tell a fresh result from the last one it already handled: the
     * flow replays its current value to new collectors, and this receiver keeps its process alive
     * across configuration changes, so without it a recomposition could re-run a finished repair.
     */
    data class Result(
        val sequence: Long,
        val packageName: String?,
        val status: Status,
        val message: String? = null,
    )

    private val _results = MutableStateFlow<Result?>(null)
    val results: StateFlow<Result?> = _results.asStateFlow()

    private var sequence = 0L

    /**
     * Asks the platform to remove [packageName].
     *
     * Returns true when the request was accepted - the outcome then arrives through [results].
     * Returns false when the platform refused outright, in which case the caller must use the App
     * Info screen instead.
     */
    fun request(
        context: Context,
        packageName: String,
    ): Boolean {
        val installer = runCatching { context.packageManager.packageInstaller }.getOrNull() ?: return false

        // An explicit intent: a broadcast receiver registered in the manifest cannot be addressed by
        // an implicit action for a PendingIntent the system will send across processes.
        val statusIntent = Intent(context, WazeBridgeUninstallReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        val statusReceiver = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            statusIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return try {
            installer.uninstall(packageName, statusReceiver.intentSender)
            Timber.tag(TAG).d("Uninstall: requested %s through the package installer", packageName)
            true
        } catch (error: Exception) {
            // SecurityException when Hush is not the installer of record, and a couple of OEM
            // flavours throw their own types here; all of them mean "use the App Info screen".
            Timber.tag(TAG).w(error, "Uninstall: package installer refused %s; falling back to App Info", packageName)
            false
        }
    }

    internal fun publish(
        packageName: String?,
        status: Status,
        message: String? = null,
    ) {
        sequence += 1
        _results.value = Result(sequence, packageName, status, message)
        Timber.tag(TAG).d(
            "Uninstall: result package=%s status=%s message=%s",
            packageName ?: "?",
            status,
            message ?: "-",
        )
    }

    /** Resolves the package a status broadcast belongs to, preferring the platform's own extra. */
    internal fun packageNameOf(intent: Intent): String? =
        intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)

    /** The status extra, which the platform omits on some deliveries. */
    internal fun statusOf(intent: Intent): Int =
        intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
}
