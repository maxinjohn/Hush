/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import app.hush.music.BuildConfig
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives a SpotiFLAC source verification from a shell. **Debug builds only.**
 *
 * Verification on a device whose WebView is older than Cloudflare supports (a car head unit) can
 * only be completed in a real browser, so proving it by hand needs a browser tap on a small screen.
 * This receiver exposes each step of that route to `adb` instead, so the routing can be exercised -
 * and its outcome read back - without touching the unit.
 *
 * The property it exists to prove is where a grant is delivered: a grant redeems only for the
 * extension whose challenge raised it, and the extension runtime's challenge is *not* a relay
 * credential (the relay exchange answers it with HTTP 403, which is what the session card used to
 * show on a car). Every step below therefore reports the challenge's owner as well as the source it
 * was asked about.
 *
 * Registered in `src/debug/AndroidManifest.xml`, so it exists in nothing that ships.
 *
 * ```
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op state
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op challenge --es source deezer
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op recover   --es source deezer
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op browser   --es source deezer
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op resolve   \
 *     --es source amazon --es title "Africa" --es artist "Toto"
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op bytes --es path /data/.../file.flac
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op verify  --es source amazon
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op toggle  --es source youtube
 * adb shell am broadcast -a app.hush.music.action.SPOTIFLAC_DEBUG --es op play    --es path /data/.../file.flac
 * ```
 *
 * `verify` drives the automatic in-app route and waits for its verdict, so the path that must work
 * with nobody in front of the device can be proven from a shell.
 *
 * `resolve` runs a real download through one source and names the container of the file it
 * produced, which is how a download that is the right size but silent gets identified; `bytes`
 * does the same for a file that is already on disk.
 *
 * ## The verdict contract
 *
 * Each operation logs its human-readable result plus exactly one machine-readable line
 * (`spotiflac-debug step=<name> source=<id> verdict=<verdict> …`), so a script can gate on it. A step
 * that cannot be decided is `SKIPPED` together with the reason, never a silent pass.
 */
class SpotiFLACDebugReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "received action=${intent.action} debug=${BuildConfig.DEBUG}")
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_DEBUG) return

        val source = intent.getStringExtra(EXTRA_SOURCE)?.trim().orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (val operation = intent.getStringExtra(EXTRA_OPERATION) ?: OP_STATE) {
                    OP_STATE -> logState()
                    OP_CHALLENGE -> challenge(source)
                    OP_RECOVER -> recover(source)
                    OP_BROWSER -> browser(context.applicationContext, source)
                    OP_GRANT -> deliver(
                        source = source,
                        grant = intent.getStringExtra(EXTRA_GRANT).orEmpty(),
                    )

                    OP_RESOLVE -> resolve(
                        source = source,
                        title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                        artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                        quality = intent.getStringExtra(EXTRA_QUALITY)?.trim().orEmpty()
                            .ifEmpty { "LOSSLESS" },
                    )

                    OP_BYTES -> reportBytes(intent.getStringExtra(EXTRA_PATH).orEmpty())

                    OP_PLAY -> playFile(context.applicationContext, intent.getStringExtra(EXTRA_PATH).orEmpty())

                    OP_VERIFY -> verifyAutomatically(source)

                    OP_TOGGLE -> toggleSource(context.applicationContext, source)

                    else -> log("unknown operation=%s", operation)
                }
            } catch (error: Throwable) {
                log("step=%s source=%s verdict=FAIL reason=%s", intent.getStringExtra(EXTRA_OPERATION) ?: OP_STATE, source, error.message)
            } finally {
                pending.finish()
            }
        }
    }

    /** Every enabled source's auth state, the runtime's pending challenge and the relay session. */
    private suspend fun logState() {
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            log("state verdict=FAIL reason=runtime-unavailable")
            return
        }
        val sources = runCatching {
            ExtensionRepositoryManager.getInstance().getEnabledSourceIds()
        }.getOrDefault(emptyList())
        // Prepare before classifying. A source's auth state is read from its extracted manifest, so
        // asking before the packages exist reports UNKNOWN for every source - which is exactly the
        // state a fresh install is in, and the thing this probe has to be able to show.
        runCatching { bridge.prepareForPlayback(sources) }
        val runtimePending = bridge.pendingRuntimeAuth()
        log(
            "state sources=%s relaySession=%b pendingRuntimeAuth=%s owner=%s",
            sources.joinToString(","),
            SpotiFLACSessionManager.getInstance().hasActiveSession(),
            runtimePending?.authUrl?.take(120) ?: "none",
            runtimePending?.extensionId ?: "none",
        )
        for (source in sources) {
            val state = bridge.sourceAuthState(source)
            val pending = bridge.pendingAuthFor(source)
            log(
                "state source=%s auth=%s verified=%b challengeOwner=%s challenge=%s",
                source,
                state,
                bridge.isSourceVerified(source),
                pending?.extensionId ?: "none",
                pending?.authUrl?.take(120) ?: "none",
            )
            log(
                "spotiflac-debug step=state source=%s verdict=ok auth=%s verified=%b owner=%s",
                source,
                state,
                bridge.isSourceVerified(source),
                pending?.extensionId ?: "none",
            )
        }
        log("spotiflac-debug step=state source=- verdict=ok sources=%d", sources.size)
    }

    /** Raises (or reads) the runtime's challenge for a source, naming the extension that owns it. */
    private suspend fun challenge(source: String) {
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            return log("spotiflac-debug step=challenge source=%s verdict=FAIL reason=runtime-unavailable", source)
        }
        if (source.isEmpty()) {
            return log("spotiflac-debug step=challenge source=- verdict=FAIL reason=no-source")
        }
        val pending = bridge.ensureChallenge(source)
        if (pending == null) {
            return log("spotiflac-debug step=challenge source=%s verdict=FAIL reason=no-challenge", source)
        }
        val pageState = SpotiFLACChallengeRoute.readPageState(pending.authUrl)
        log(
            "challenge source=%s owner=%s pageState=%s url=%s",
            source,
            pending.extensionId,
            pageState?.let { it::class.simpleName } ?: "unreadable",
            pending.authUrl,
        )
        log(
            "spotiflac-debug step=challenge source=%s verdict=%s owner=%s pageState=%s",
            source,
            when (pageState) {
                is SpotiFLACChallengeRoute.PageState.Grant -> "GRANT_READY"
                SpotiFLACChallengeRoute.PageState.Spent -> "SPENT"
                else -> "PENDING"
            },
            pending.extensionId,
            pageState?.let { it::class.simpleName } ?: "unreadable",
        )
    }

    /**
     * Flips an audio-source switch the same way its settings row does.
     *
     * A source toggle changes routing for everything already queued, and the property that has to
     * hold is that it changes *nothing else* - the queue included. Proving that by hand needs the
     * settings screen, which a headless run cannot reach, so `source` names the switch (`youtube`
     * or `spotiflac`) and this writes the preference the switch writes.
     */
    private suspend fun toggleSource(
        context: Context,
        source: String,
    ) {
        val key =
            when (source.lowercase()) {
                "youtube", "yt" -> YoutubeStreamingEnabledKey
                "spotiflac" -> SpotiFLACEnabledKey
                else ->
                    return log(
                        "spotiflac-debug step=toggle source=%s verdict=FAIL reason=unknown-switch",
                        source,
                    )
            }
        val before = context.dataStore.data.first()[key] ?: true
        context.dataStore.edit { it[key] = !before }
        val after = context.dataStore.data.first()[key] ?: true
        log("toggle source=%s %b -> %b", source, before, after)
        log(
            "spotiflac-debug step=toggle source=%s verdict=%s enabled=%b",
            source,
            if (after != before) "PASS" else "FAIL",
            after,
        )
    }

    /**
     * Delivers the grant an already-solved challenge publishes, to the extension that owns it.
     *
     * This is the whole point of the fix this receiver was written for: the delivery target is the
     * challenge's owner, so a check solved in a browser lands on the source that can actually use
     * it, and it never touches Hush's relay session.
     */
    private suspend fun recover(source: String) {
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            return log("spotiflac-debug step=recover source=%s verdict=FAIL reason=runtime-unavailable", source)
        }
        val pending = bridge.pendingAuthFor(source)
        if (pending == null) {
            return log("spotiflac-debug step=recover source=%s verdict=SKIPPED reason=no-pending-challenge", source)
        }
        val owner = pending.extensionId.takeIf { it.isNotBlank() } ?: source
        val grant = SpotiFLACChallengeRoute.grantFromChallengePage(
            SpotiFLACChallengeRoute.readChallengePage(pending.authUrl),
        )
        if (grant == null) {
            return log(
                "spotiflac-debug step=recover source=%s verdict=SKIPPED reason=challenge-not-solved owner=%s",
                source,
                owner,
            )
        }
        bridge.deliverGrant(grant, listOf(owner))
        val verified = bridge.isSourceVerified(owner)
        log(
            "recover source=%s owner=%s grantLen=%d verified=%b",
            source,
            owner,
            grant.length,
            verified,
        )
        log(
            "spotiflac-debug step=recover source=%s verdict=%s owner=%s verified=%b",
            source,
            if (verified) "PASS" else "FAIL",
            owner,
            verified,
        )
    }

    /**
     * Starts the manual browser route exactly as the notification action does.
     *
     * Deliberately [SpotiFLACBrowserVerification.startInProcess] rather than `run`: that is the car's
     * real entry point (a notification action, with no screen), and it runs on the app's own
     * process scope so it outlives this receiver while the user solves the check in a browser. The
     * outcome is read from `SpotiFLACDiag` (`manual verification for …: authenticated=…`) and from
     * [SpotiFLACBrowserVerification.status], not from a return value.
     */
    private suspend fun browser(
        context: Context,
        source: String,
    ) {
        if (source.isEmpty()) {
            return log("spotiflac-debug step=browser source=- verdict=FAIL reason=no-source")
        }
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        val owner = withContext(Dispatchers.IO) { bridge?.challengeOwnerFor(source) } ?: source
        val pending = withContext(Dispatchers.IO) { bridge?.ensureChallenge(source) }
        SpotiFLACBrowserVerification.startInProcess(context, source)
        log(
            "browser started source=%s owner=%s challenge=%s",
            source,
            owner,
            pending?.authUrl ?: "none",
        )
        log("spotiflac-debug step=browser source=%s verdict=STARTED owner=%s", source, owner)
    }

    /** Hands a grant the shell obtained (e.g. read off the browser page) to its challenge's owner. */
    private suspend fun deliver(
        source: String,
        grant: String,
    ) {
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            return log("spotiflac-debug step=grant source=%s verdict=FAIL reason=runtime-unavailable", source)
        }
        if (grant.isBlank()) {
            return log("spotiflac-debug step=grant source=%s verdict=FAIL reason=no-grant", source)
        }
        val owner = bridge.pendingRuntimeAuth()?.extensionId
            ?: source.takeIf { it.isNotEmpty() }
            ?: return log("spotiflac-debug step=grant source=- verdict=FAIL reason=no-owner")
        bridge.deliverGrant(grant, listOf(owner))
        val verified = bridge.isSourceVerified(owner)
        log("grant owner=%s verified=%b", owner, verified)
        log(
            "spotiflac-debug step=grant source=%s verdict=%s owner=%s verified=%b",
            source,
            if (verified) "PASS" else "FAIL",
            owner,
            verified,
        )
    }

    /**
     * Runs one real resolve through the runtime and reports what it actually produced.
     *
     * This exists for the failure mode where a source downloads a file of the right size that
     * then plays with no audio: every line Hush already logs says the download succeeded, because
     * nothing looks at the bytes. Naming the container of whatever the runtime wrote - and
     * printing its first bytes - is what turns that into a checkable fact instead of a guess.
     */
    private suspend fun resolve(
        source: String,
        title: String,
        artist: String,
        quality: String,
    ) {
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            return log("spotiflac-debug step=resolve source=%s verdict=FAIL reason=runtime-unavailable", source)
        }
        if (title.isBlank()) {
            return log("spotiflac-debug step=resolve source=%s verdict=FAIL reason=no-title", source)
        }
        val started = System.currentTimeMillis()
        val result = bridge.resolve(
            title = title,
            artist = artist,
            album = null,
            durationMs = 0L,
            isrc = null,
            spotifyTrackId = null,
            quality = quality,
            sourceIds = listOf(source),
            coverUrl = null,
            // Null on purpose: a probe must not claim a media id in the cache index, which is what
            // playback uses to find a track's file.
            mediaId = null,
        )
        val elapsed = System.currentTimeMillis() - started
        val resolved = result.getOrNull()
        if (resolved == null) {
            log(
                "resolve source=%s verdict=FAIL elapsed=%dms error=%s",
                source,
                elapsed,
                result.exceptionOrNull()?.message?.take(200) ?: "unknown error",
            )
            return log(
                "spotiflac-debug step=resolve source=%s verdict=FAIL elapsed=%dms error=%s",
                source,
                elapsed,
                result.exceptionOrNull()?.message?.take(200) ?: "unknown error",
            )
        }
        val head = SpotiFLACFileIntegrity.readHead(resolved.file)
        val container = head?.let { SpotiFLACFileIntegrity.containerOf(it) } ?: "unreadable"
        val byteVerdict = head?.let {
            SpotiFLACFileIntegrity.verdict(resolved.file.length(), 0L, it)
        } ?: SpotiFLACFileIntegrity.Verdict.MISSING
        log(
            "resolve source=%s file=%s bytes=%d container=%s integrity=%s codec=%s bits=%s rate=%s quality=%s fromCache=%b elapsed=%dms",
            resolved.sourceId,
            resolved.file.absolutePath,
            resolved.file.length(),
            container,
            byteVerdict,
            resolved.codec ?: "-",
            resolved.bitDepth ?: -1,
            resolved.sampleRate ?: -1,
            resolved.quality ?: "-",
            resolved.fromCache,
            elapsed,
        )
        val probe = SpotiFLACFileIntegrity.readProbe(resolved.file)
        val encrypted = probe?.let { SpotiFLACFileIntegrity.isEncryptedStream(it) } == true
        val dolby = probe?.let { SpotiFLACFileIntegrity.dolbyFormatOf(it) }
            ?: SpotiFLACFileIntegrity.dolbyFormatOfCodecName(resolved.codec)
        log("resolve head=%s", head?.joinToString("") { "%02x".format(it) } ?: "-")
        // The two conditions that make a correctly-sized file silent are named explicitly, so a
        // probe says why a track would not play rather than only that it did not.
        log("resolve encrypted=%b dolby=%s", encrypted, dolby ?: "none")
        log(
            "spotiflac-debug step=resolve source=%s verdict=OK container=%s integrity=%s codec=%s bytes=%d encrypted=%b dolby=%s file=%s",
            resolved.sourceId,
            container,
            byteVerdict,
            resolved.codec ?: "-",
            resolved.file.length(),
            encrypted,
            dolby ?: "none",
            resolved.file.absolutePath,
        )
    }

    /**
     * Runs the automatic in-app verification for one source and waits for its outcome.
     *
     * The automatic route is the one that must work with nobody in front of the device: the
     * challenge is solved in an invisible WebView by Cloudflare itself. Proving it from a shell is
     * what separates "verification does nothing" from "verification works, the state was misread" -
     * and the state being misread is precisely the bug this probe was written to find, so the verdict
     * reports the source's auth state before and after rather than only the outcome.
     */
    private suspend fun verifyAutomatically(source: String) {
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            return log("spotiflac-debug step=verify source=%s verdict=FAIL reason=runtime-unavailable", source)
        }
        if (source.isEmpty()) {
            return log("spotiflac-debug step=verify source=- verdict=FAIL reason=no-source")
        }
        runCatching { bridge.prepareForPlayback(listOf(source)) }
        val before = bridge.sourceAuthState(source)
        if (before == SpotiFLACSourceAuthState.NOT_REQUIRED) {
            return log(
                "spotiflac-debug step=verify source=%s verdict=SKIPPED reason=needs-no-verification",
                source,
            )
        }
        if (before == SpotiFLACSourceAuthState.VERIFIED) {
            return log(
                "spotiflac-debug step=verify source=%s verdict=SKIPPED reason=already-verified",
                source,
            )
        }
        SpotiFLAutoVerifier.enqueue(listOf("$source"), "debug", force = true)
        // Bounded well inside what a broadcast may hold: the platform reclaims a receiver that
        // overruns, and a reclaimed run logs no verdict at all - which reads exactly like the route
        // silently doing nothing. A challenge that solves does so in about fifteen seconds (measured
        // on a phone), and one that cannot solve is reported as undecided by the caller instead.
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(2_000)
            if (bridge.sourceAuthState(source) == SpotiFLACSourceAuthState.VERIFIED) break
        }
        val after = bridge.sourceAuthState(source)
        log(
            "verify source=%s before=%s after=%s active=%s",
            source,
            before,
            after,
            SpotiFLAutoVerifier.active.value ?: "none",
        )
        log(
            "spotiflac-debug step=verify source=%s verdict=%s before=%s after=%s",
            source,
            if (after == SpotiFLACSourceAuthState.VERIFIED) "PASS" else "FAIL",
            before,
            after,
        )
    }

    /**
     * Names a file's container from a path the shell can read.
     *
     * Used against a file the app already downloaded (a user download, or a cached playback file)
     * when the interesting question is what is on disk rather than what a fresh resolve returns.
     */
    private fun reportBytes(path: String) {
        if (path.isBlank()) {
            return log("spotiflac-debug step=bytes verdict=FAIL reason=no-path")
        }
        val file = java.io.File(path)
        // The probe window, not just the signature: a still-encrypted stream looks like an ordinary
        // MP4 in its first bytes, which is exactly why the byte check has to read far enough to see
        // the sample entry. Reporting the head alone would declare such a file playable.
        val head = SpotiFLACFileIntegrity.readProbe(file)
        val container = head?.let { SpotiFLACFileIntegrity.containerOf(it) } ?: "unreadable"
        val encrypted = head?.let { SpotiFLACFileIntegrity.isEncryptedStream(it) } == true
        val dolby = head?.let { SpotiFLACFileIntegrity.dolbyFormatOf(it) }
        val verdict = head?.let { SpotiFLACFileIntegrity.verdict(file.length(), 0L, it) }
            ?: SpotiFLACFileIntegrity.Verdict.MISSING
        log(
            "bytes file=%s exists=%b len=%d container=%s encrypted=%b dolby=%s verdict=%s",
            path,
            file.isFile,
            file.length(),
            container,
            encrypted,
            dolby ?: "none",
            verdict,
        )
        log("bytes head=%s", head?.take(32)?.joinToString("") { "%02x".format(it) } ?: "-")
        log(
            "spotiflac-debug step=bytes file=%s verdict=%s container=%s encrypted=%b len=%d",
            path,
            if (verdict == SpotiFLACFileIntegrity.Verdict.OK) "OK" else verdict.name,
            container,
            encrypted,
            file.length(),
        )
    }

    /**
     * Plays a file through the app's own player and reports whether it actually rendered.
     *
     * The whole point of decrypting a provider's stream is that the result plays, and every check
     * short of rendering can be satisfied by a file that decodes to nothing: a container probe reads
     * bytes, a duration comes from metadata, and a decoder that is handed silence produces silence
     * without complaint. This drives the same media3 stack playback uses - prepare, play, and the
     * position the player reports after a couple of seconds - so a file is called playable only when
     * the audio sink consumed it. The codec media3 selected is reported too, which is what turns
     * "the file is recognised" into "the file is played as FLAC/Opus".
     */
    @kotlin.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun playFile(
        context: Context,
        path: String,
    ) = withContext(Dispatchers.Main) {
        if (path.isBlank()) {
            return@withContext log("spotiflac-debug step=play verdict=FAIL reason=no-path")
        }
        val file = java.io.File(path)
        if (!file.isFile || file.length() <= 0L) {
            return@withContext log("spotiflac-debug step=play verdict=FAIL reason=missing file=%s", path)
        }
        var failure: String? = null
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            player.addListener(
                object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        failure = error.message?.take(160) ?: error.errorCodeName
                    }
                },
            )
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            player.prepare()
            player.play()
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline &&
                failure == null &&
                player.playbackState != androidx.media3.common.Player.STATE_READY
            ) {
                kotlinx.coroutines.delay(250)
            }
            val ready = failure == null && player.playbackState == androidx.media3.common.Player.STATE_READY
            val positionBefore = player.currentPosition
            // Long enough that a player which prepared but cannot decode falls over inside this
            // window, and that the position it reports is real progress rather than the seek point.
            kotlinx.coroutines.delay(2_000)
            val positionAfter = player.currentPosition
            val duration = player.duration
            val format = player.currentTracks.groups
                .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                ?.mediaTrackGroup
                ?.let { if (it.length > 0) it.getFormat(0) else null }
            val advanced = positionAfter - positionBefore
            val played = ready && failure == null && advanced > 0L
            log(
                "play file=%s ready=%b state=%d durationMs=%s positionMs=%d advanceMs=%d mime=%s codec=%s rate=%s channels=%s error=%s",
                path,
                ready,
                player.playbackState,
                if (duration > 0L) duration.toString() else "unknown",
                positionAfter,
                advanced,
                format?.sampleMimeType ?: "-",
                format?.codecs ?: "-",
                format?.sampleRate ?: -1,
                format?.channelCount ?: -1,
                failure ?: "none",
            )
            log(
                "spotiflac-debug step=play file=%s verdict=%s ready=%b advanceMs=%d mime=%s rate=%s channels=%s error=%s",
                path,
                if (played) "PASS" else "FAIL",
                ready,
                advanced,
                format?.sampleMimeType ?: "-",
                format?.sampleRate ?: -1,
                format?.channelCount ?: -1,
                failure ?: "none",
            )
        } finally {
            player.release()
        }
    }

    /**
     * Logged through [Log] on purpose: this receiver is the way the route is driven from a shell, and
     * its output has to be readable whatever the app's logging configuration happens to be.
     *
     * A verdict line is *also* written to [SpotiFLACDiag]'s rolling file. Some devices drop an app's
     * own logcat output while still passing the framework's through - measured on a OnePlus running
     * this probe: every `bytes` and `play` verdict ran and logged nowhere, leaving a shell with a
     * probe that works and reports nothing. The diag file is readable with `run-as` on any device, so
     * the machine-readable line survives wherever logcat does not.
     */
    private fun log(
        message: String,
        vararg args: Any?,
    ) {
        val rendered = if (args.isEmpty()) message else message.format(*args)
        Log.d(TAG, rendered)
        if (message.startsWith(VERDICT_PREFIX)) SpotiFLACDiag.log(rendered)
    }

    companion object {
        private const val TAG = "SpotiFLACDebug"

        /** The one-line contract a script gates on; mirrored into the diag file as well. */
        private const val VERDICT_PREFIX = "spotiflac-debug step="

        const val ACTION_DEBUG = "app.hush.music.action.SPOTIFLAC_DEBUG"
        const val EXTRA_OPERATION = "op"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_GRANT = "grant"

        const val OP_STATE = "state"
        const val OP_CHALLENGE = "challenge"
        const val OP_RECOVER = "recover"
        const val OP_BROWSER = "browser"
        const val OP_GRANT = "grant"
        const val OP_RESOLVE = "resolve"
        const val OP_BYTES = "bytes"
        const val OP_VERIFY = "verify"
        const val OP_PLAY = "play"
        const val OP_TOGGLE = "toggle"

        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_PATH = "path"
    }
}
