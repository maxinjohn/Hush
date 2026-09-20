/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
private const val YOUTUBE_MUSIC_HOME_URL = "https://music.youtube.com"

/**
 * Every install of Hush declares a `youtube.com/watch` filter - it is what makes a shared link
 * play in the app - so Hush is itself one of the activities that resolve a YouTube Music link.
 *
 * The release build and a debug build can both be installed, and a debug build's own package
 * name is only known at runtime, so both spellings are listed here and the app's own
 * `packageName` is added on top.
 */
private val HUSH_PACKAGES = setOf("app.hush.music", "app.hush.music.debug")

/**
 * The first resolver of a YouTube Music link that is not Hush, or null when Hush is the only
 * one.
 *
 * The order matters more than it looks. The two known packages are tried explicitly first, but
 * they are not always the ones that answer: a patched YouTube Music build can be installed
 * under a different package name and still declare the app's deep-link activity, and the
 * YouTube app is often not installed at all. In that case this fallback is the only route that
 * reaches a music player - and if Hush were preferred here, tapping "Open YouTube Music" would
 * re-open the app the user is already looking at (or the release build sitting beside this
 * debug one), which reads as the button doing nothing.
 */
internal fun firstExternalOpener(
    activities: List<Pair<String, String>>,
    ownPackages: Set<String>,
): Pair<String, String>? = activities.firstOrNull { (packageName, _) -> packageName !in ownPackages }

fun Context.openYouTubeMusicUrl(targetUrl: String): Boolean {
    val uri =
        targetUrl
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: Uri.parse(YOUTUBE_MUSIC_HOME_URL)

    val baseIntent =
        Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    val externalResolvedIntent =
        firstExternalOpener(
            activities =
                packageManager
                    .queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapNotNull { it.activityInfo }
                    .map { it.packageName to it.name },
            ownPackages = HUSH_PACKAGES + packageName,
        )?.let { (packageName, activityName) ->
            Intent(baseIntent).setClassName(packageName, activityName)
        }

    return sequenceOf(
        Intent(baseIntent).setPackage(YOUTUBE_MUSIC_PACKAGE),
        Intent(baseIntent).setPackage(YOUTUBE_PACKAGE),
        externalResolvedIntent,
    ).filterNotNull().any(::tryStartActivity)
}

private fun Context.tryStartActivity(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
