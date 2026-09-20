/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.auto

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dagger.hilt.android.EntryPointAccessors
import app.hush.music.BuildConfig
import app.hush.music.constants.AndroidAutoSectionsOrderKey
import app.hush.music.constants.AndroidAutoSpotifyPlaylistsKey
import app.hush.music.constants.AndroidAutoTargetPlaylistKey
import app.hush.music.constants.AndroidAutoYouTubePlaylistsKey
import app.hush.music.constants.MediaSessionConstants
import app.hush.music.playback.AndroidAutoPlaylists
import app.hush.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.hush.music.utils.dataStore
import java.io.File

/**
 * Drives and dumps the media tree a car screen browses, from a shell. **Debug builds only.**
 *
 * The Android Auto library cannot be read on a phone: the browse tree only exists once a head unit
 * connects, and the one screen that shows it is in the car. This connects the app's own
 * [MediaBrowser] to its own [MusicService] - the same library session, the same media ids a head
 * unit receives - and writes what it returns to `files/debug/auto-library.txt` together with the
 * switches that produced it. So a missing Spotify folder, a switch that does nothing, or a track a
 * tap cannot play can be found without sitting in the vehicle.
 *
 * Invoke it by name; an implicit broadcast to a manifest receiver is not delivered on Android 8+,
 * and `am broadcast` reports success anyway:
 *
 * ```
 * # what the car is offered, and from which switches
 * adb shell am broadcast -n app.hush.music.debug/app.hush.music.auto.AutoLibraryDebugReceiver \
 *     -a app.hush.music.action.AUTO_LIBRARY_DEBUG --es op browse --es parent root
 *
 * # drive a switch the way the settings screen does, then dump again
 * adb shell am broadcast ... --es op switch --es name youtube --ez value false
 * adb shell am broadcast ... --es op switch --es name spotify --ez value true
 *
 * # arrange the sections, exactly as the reorder screen stores them
 * adb shell am broadcast ... --es op sections --es value "playlists:true,liked:false"
 *
 * # pick a quick-add destination: a playlist id, spotify_playlist/<id>, or auto for unset
 * adb shell am broadcast ... --es op target --es value LPxxxx
 *
 * # play a browsed item (the same call a car's tap makes)
 * adb shell am broadcast ... --es op play --es mediaId "liked/<videoId>"
 *
 * # play a browsed item and press the car's save button
 * adb shell am broadcast ... --es op quick-add --es mediaId "liked/<videoId>"
 * ```
 *
 * `quick-add` plays the item through the same callback a car's tap uses, then sends the quick-add
 * session command and reports both its result code and whether the track is in the destination
 * afterwards - the whole button, not just its wiring.
 *
 * `parent` is any media id a report prints, so the tree can be walked a level at a time: `root`,
 * `home`, `home_mixes_and_radios`, `spotify_playlist/<id>`, `playlist/<id>`.
 */
class AutoLibraryDebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_DEBUG) return

        val appContext = context.applicationContext
        val operation = intent.getStringExtra(EXTRA_OPERATION)?.trim().orEmpty().ifEmpty { OP_BROWSE }
        val pending = goAsync()
        // A MediaController is confined to the thread that built it and refuses a browse from
        // anywhere else, so this runs on the main looper and merely suspends while it waits.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            val report =
                try {
                    when (operation) {
                        OP_SWITCH -> setSwitch(appContext, intent)

                        OP_SECTIONS -> setSections(appContext, intent)

                        OP_TARGET -> setTarget(appContext, intent)

                        OP_QUICK_ADD -> quickAdd(appContext, intent.getStringExtra(EXTRA_MEDIA_ID).orEmpty())

                        OP_PLAY -> play(appContext, intent.getStringExtra(EXTRA_MEDIA_ID).orEmpty())

                        else -> browse(appContext, intent.getStringExtra(EXTRA_PARENT).orEmpty())
                    }
                } catch (error: Throwable) {
                    "failed: ${error::class.simpleName}: ${error.message}"
                }
            withContext(Dispatchers.IO) { writeReport(appContext, report) }
            pending.finish()
        }
    }

    // ── Operations ────────────────────────────────────────────────────────────

    private suspend fun setSwitch(
        context: Context,
        intent: Intent,
    ): String {
        val name = intent.getStringExtra(EXTRA_NAME)?.trim().orEmpty()
        val value = intent.getBooleanExtra(EXTRA_VALUE, true)
        val key =
            when (name) {
                NAME_YOUTUBE -> AndroidAutoYouTubePlaylistsKey
                NAME_SPOTIFY -> AndroidAutoSpotifyPlaylistsKey
                else -> return "failed: unknown switch '$name' (youtube|spotify)"
            }
        // Written through the same DataStore key the settings screen uses, so what is exercised is
        // the app's own state and not a copy of it.
        context.dataStore.edit { prefs -> prefs[key] = value }
        return "set switch: $name=$value"
    }

    private suspend fun setSections(
        context: Context,
        intent: Intent,
    ): String {
        val value = intent.getStringExtra(EXTRA_VALUE)?.trim().orEmpty()
        context.dataStore.edit { prefs -> prefs[AndroidAutoSectionsOrderKey] = value }
        return "set sections: stored='$value' published=${AndroidAutoPlaylists.enabledSections(value)}"
    }

    private suspend fun setTarget(
        context: Context,
        intent: Intent,
    ): String {
        val value = intent.getStringExtra(EXTRA_VALUE)?.trim().orEmpty()
        context.dataStore.edit { prefs -> prefs[AndroidAutoTargetPlaylistKey] = value }
        val spotify = AndroidAutoPlaylists.spotifyDestinationId(value)
        return "set quick-add target: '$value' (${if (spotify != null) "spotify playlist $spotify" else "local playlist"})"
    }

    /**
     * Plays one browsed item the way a car's tap does, and reports what the player settled on.
     *
     * Which source served it - the user's download, the SpotiFLAC cache, or a provider - is not in
     * this report: it is in the app's own diagnostic log (`files/spotiflac/diag.log`), which is
     * where the chain explains itself.
     */
    private suspend fun play(
        context: Context,
        mediaId: String,
    ): String {
        if (mediaId.isBlank()) return "failed: play needs --es mediaId"
        val browser = browser(context)
        return try {
            buildList {
                add("play: $mediaId")
                addAll(playBrowsedItem(browser, mediaId))
            }.joinToString("\n")
        } finally {
            browser.release()
        }
    }

    /**
     * Plays a browsed item the way a car's tap does, then presses the save button.
     *
     * The command is sent over the same session a head unit holds, so a command that is not
     * advertised on connect, or advertised and then not handled, fails here rather than in the car.
     */
    private suspend fun quickAdd(
        context: Context,
        mediaId: String,
    ): String {
        val browser = browser(context)
        return try {
            val advertised =
                browser.availableSessionCommands.contains(MediaSessionConstants.CommandAddToTargetPlaylist)
            buildList {
                add("quick-add: command advertised on connect=$advertised")
                // What a car and the phone's notification actually show for this session.
                add(
                    "  custom layout: " +
                        browser.customLayout.joinToString(", ") { button ->
                            button.displayName?.toString().orEmpty()
                        },
                )
                if (mediaId.isNotBlank()) {
                    addAll(playBrowsedItem(browser, mediaId))
                }
                val current =
                    browser.currentMediaItem?.mediaId ?: "(nothing playing)"
                add("  playing: $current")
                val result =
                    withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                        browser.sendCustomCommand(MediaSessionConstants.CommandAddToTargetPlaylist, Bundle()).await()
                    }
                add("  sendCustomCommand result=${result?.resultCode ?: "no answer"}")
                addAll(describeMembership(context, browser))
            }.joinToString("\n")
        } finally {
            browser.release()
        }
    }

    private suspend fun playBrowsedItem(
        browser: MediaBrowser,
        mediaId: String,
    ): List<String> {
        val lines = mutableListOf<String>()
        // The library callback answers this asynchronously and the controller keeps what was sent
        // until it does, so reading the timeline straight away would report the browsed id the app
        // was asked for rather than the queue it actually built - and a save pressed at that moment
        // would name the wrong track. Wait for the resolved queue instead.
        browser.setMediaItems(listOf(MediaItem.Builder().setMediaId(mediaId).build()), 0, 0L)
        val settled =
            withTimeoutOrNull(PLAY_TIMEOUT_MS) {
                while (browser.mediaItemCount <= 1 && browser.currentMediaItem?.mediaId == mediaId) {
                    delay(POLL_MS)
                }
                true
            }
        browser.prepare()
        lines += "  tapped $mediaId: resolved=${settled == true} timeline=${browser.mediaItemCount} items"
        lines += "  playing state=${browser.playbackState} current=${browser.currentMediaItem?.mediaId}"
        return lines
    }

    /** Whether the playing track ended up in the chosen destination. */
    private suspend fun describeMembership(
        context: Context,
        browser: MediaBrowser,
    ): List<String> {
        val prefs = context.dataStore.data.first()
        val target = prefs[AndroidAutoTargetPlaylistKey].orEmpty()
        if (target.isBlank() || target == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
            return listOf("  destination: unset (nothing to check)")
        }
        // A Spotify destination cannot be listed through the library session - its playlist is on
        // Spotify's side - so the check names it and stops there.
        if (AndroidAutoPlaylists.spotifyDestinationId(target) != null) {
            return listOf("  destination: $target (Spotify; membership not readable from here)")
        }
        val playing = browser.currentMediaItem?.mediaId ?: return listOf("  destination: $target (nothing playing)")
        val children =
            withTimeoutOrNull(MEMBERSHIP_TIMEOUT_MS) {
                browser.getChildren(target, 0, BROWSE_LIMIT, null).await()
            }
        if (children == null) {
            return listOf("  destination: $target - no answer within ${MEMBERSHIP_TIMEOUT_MS}ms")
        }
        val ids = children.value.orEmpty().map { item -> item.mediaId }
        val present = ids.any { it == playing || it.endsWith("/$playing") }
        return listOf(
            "  destination: $target status=${children.resultCode} items=${ids.size} " +
                "playingTrackPresent=$present (playing=$playing)",
        )
    }

    private suspend fun browse(
        context: Context,
        requestedParent: String,
    ): String {
        val parent = requestedParent.trim().ifEmpty { ROOT }
        val repository =
            EntryPointAccessors
                .fromApplication(context, AutoDebugEntryPoint::class.java)
                .spotifyLibraryRepository()
        val browser = browser(context)
        return try {
            val children =
                withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    browser.getChildren(parent, 0, BROWSE_LIMIT, null).await()
                }
            buildList {
                add("parent=$parent")
                addAll(describeSwitches(context, repository.ensureConnected()))
                if (children == null) {
                    add("  children: no answer within ${BROWSE_TIMEOUT_MS}ms")
                } else {
                    add("  status=${children.resultCode} count=${children.value?.size ?: 0}")
                    children.value.orEmpty().forEach { item -> add(describe(item)) }
                }
            }.joinToString("\n")
        } finally {
            browser.release()
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * The state the tree was built from, reported with it.
     *
     * A dump without this cannot distinguish "the folder is missing because the switch is off" from
     * "the folder is missing because the playlist code is broken", which is the whole point of
     * looking.
     */
    private suspend fun describeSwitches(
        context: Context,
        spotifyConnected: Boolean,
    ): List<String> {
        val prefs: Preferences = context.dataStore.data.first()
        val youtube = prefs[AndroidAutoYouTubePlaylistsKey] ?: true
        val spotify = prefs[AndroidAutoSpotifyPlaylistsKey] ?: true
        val stored = prefs[AndroidAutoSectionsOrderKey].orEmpty()
        val sources =
            AndroidAutoPlaylists.sources(
                youtubeEnabled = youtube,
                spotifyEnabled = spotify,
                spotifyConnected = spotifyConnected,
            )
        return listOf(
            "  switches: youtube=$youtube spotify=$spotify spotifyConnected=$spotifyConnected",
            "  sources: youtube=${sources.youtube} spotify=${sources.spotify}",
            "  sections: stored='$stored' published=${AndroidAutoPlaylists.enabledSections(stored)}",
            "  quick-add: target='${prefs[AndroidAutoTargetPlaylistKey].orEmpty()}'",
        )
    }

    private fun describe(item: MediaItem): String {
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString().orEmpty()
        val subtitle = metadata.subtitle?.toString().orEmpty()
        return "  ${item.mediaId} | $title | $subtitle | playable=${metadata.isPlayable} browsable=${metadata.isBrowsable}"
    }

    private suspend fun browser(context: Context): MediaBrowser =
        MediaBrowser
            .Builder(
                context,
                SessionToken(context, ComponentName(context, MusicService::class.java)),
            ).buildAsync()
            .await()

    private fun writeReport(
        context: Context,
        report: String,
    ) {
        runCatching {
            val dir = File(context.filesDir, "debug").apply { mkdirs() }
            File(dir, REPORT_FILE).writeText(report)
        }
    }

    companion object {
        private const val ACTION_DEBUG = "app.hush.music.action.AUTO_LIBRARY_DEBUG"
        private const val EXTRA_OPERATION = "op"
        private const val EXTRA_PARENT = "parent"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VALUE = "value"
        private const val EXTRA_MEDIA_ID = "mediaId"
        private const val OP_BROWSE = "browse"
        private const val OP_SWITCH = "switch"
        private const val OP_SECTIONS = "sections"
        private const val OP_TARGET = "target"
        private const val OP_QUICK_ADD = "quick-add"
        private const val OP_PLAY = "play"
        private const val NAME_YOUTUBE = "youtube"
        private const val NAME_SPOTIFY = "spotify"
        private const val REPORT_FILE = "auto-library.txt"
        private const val ROOT = "root"
        private const val BROWSE_LIMIT = 200
        private const val BROWSE_TIMEOUT_MS = 20_000L
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private const val PLAY_TIMEOUT_MS = 30_000L
        private const val MEMBERSHIP_TIMEOUT_MS = 45_000L
        private const val POLL_MS = 250L
    }
}
