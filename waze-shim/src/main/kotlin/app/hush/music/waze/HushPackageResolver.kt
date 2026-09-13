/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log

/**
 * Resolves the Hush package the shim should send commands to.
 *
 * The shim historically hardcoded the release package ("app.hush.music"),
 * which silently breaks bridge commands (play/pause/next/previous) when only
 * the debug build ("app.hush.music.debug") is installed.
 *
 * Resolution order:
 *  1. A Hush variant that currently has a running process (the app the user
 *     is actually using). Debug wins if both are running, matching the dev
 *     workflow.
 *  2. Whichever variant is installed (debug preferred over release).
 *  3. Release as a last-resort default.
 *
 * The result is cached briefly so per-command resolution stays cheap, but the
 * cache expires so the target follows the user if they switch variants.
 */
object HushPackageResolver {
    private const val RELEASE_PACKAGE = "app.hush.music"
    private const val DEBUG_PACKAGE = "app.hush.music.debug"
    private const val TAG = "HushPackageResolver"
    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var resolved: String? = null
    @Volatile
    private var resolvedAtMs = 0L

    fun resolve(context: Context): String {
        val now = SystemClock.elapsedRealtime()
        val cached = resolved
        if (cached != null && now - resolvedAtMs < CACHE_TTL_MS) {
            return cached
        }
        val value = compute(context)
        resolved = value
        resolvedAtMs = now
        Log.d(TAG, "Resolved Hush package: $value (installed debug=${isInstalled(context, DEBUG_PACKAGE)}, installed release=${isInstalled(context, RELEASE_PACKAGE)})")
        return value
    }

    private fun compute(context: Context): String {
        val running = runningHushProcesses(context)
        if (DEBUG_PACKAGE in running) return DEBUG_PACKAGE
        if (RELEASE_PACKAGE in running) return RELEASE_PACKAGE

        return when {
            isInstalled(context, DEBUG_PACKAGE) -> DEBUG_PACKAGE
            isInstalled(context, RELEASE_PACKAGE) -> RELEASE_PACKAGE
            else -> RELEASE_PACKAGE
        }
    }

    private fun runningHushProcesses(context: Context): Set<String> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptySet()
            am.runningAppProcesses
                ?.mapNotNull { it.processName }
                ?.filter { name ->
                    name == DEBUG_PACKAGE || name.startsWith("$DEBUG_PACKAGE:") ||
                        name == RELEASE_PACKAGE || name.startsWith("$RELEASE_PACKAGE:")
                }
                ?.toSet()
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: Exception) {
        false
    }
}