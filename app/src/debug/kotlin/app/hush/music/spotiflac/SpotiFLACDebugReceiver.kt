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
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import app.hush.music.BuildConfig
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.playback.ExoDownloadService
import app.hush.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * Every call names the component (`-n`), because the action alone does **not** reach this receiver on
 * Android 8 or newer: an implicit broadcast to a manifest-registered receiver is not delivered, and
 * `am broadcast` reports `Broadcast completed: result=0` as if it had been. Measured on the reporting
 * device: the implicit form produced no log line at all while the explicit one ran the operation - so
 * a harness written as below reads as "the operation did nothing", which is exactly the wrong
 * conclusion to draw about a probe whose job is to tell you what happened.
 *
 * ```
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver \
 *     -a app.hush.music.action.SPOTIFLAC_DEBUG --es op state
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op challenge --es source deezer
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op recover   --es source deezer
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op browser   --es source deezer
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op resolve   \
 *     --es source amazon --es title "Africa" --es artist "Toto"
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op bytes --es path /data/.../file.flac
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op verify  --es source amazon
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op toggle  --es source youtube
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op gateway-returned
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op download --es source <mediaId>
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op remove-download --es source <mediaId>
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op play    --es path /data/.../file.flac
 * adb shell am broadcast -n <package>/app.hush.music.spotiflac.SpotiFLACDebugReceiver -a app.hush.music.action.SPOTIFLAC_DEBUG --es op source-row \
 *     --es source deezer --es state failed --es message "403 on test for deezer"
 * ```
 *
 * `source-test` runs the Audio Sources Test button's own call and reports its verdict, which is how
 * a failing Test gets attributed to what it actually asked about.
 *
 * `source-row` forces what one source row renders, and exists because that screen exposes nothing
 * to the accessibility tree: a row's result cannot be tapped into existence or read back on a
 * device with nobody in front of it. It writes through the same `setSourceTestState` the row's own
 * Test button uses, so what it produces is the real state and not a mock-up.
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
                    OP_STATE -> logState(context.applicationContext)
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

                    OP_DOWNLOAD -> downloadTrack(context.applicationContext, source)

                    OP_REMOVE_DOWNLOAD -> removeDownload(context.applicationContext, source)

                    OP_SOURCE_TEST -> sourceTest(source)

                    OP_RENEW -> renewSessions(context.applicationContext)

                    OP_GATEWAY_RETURNED -> replayAfterGatewayReturns()

                    OP_SOURCE_ROW -> setSourceRow(
                        source = source,
                        state = intent.getStringExtra(EXTRA_STATE).orEmpty(),
                        message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
                    )

                    else -> log("unknown operation=%s", operation)
                }
            } catch (error: Throwable) {
                log("step=%s source=%s verdict=FAIL reason=%s", intent.getStringExtra(EXTRA_OPERATION) ?: OP_STATE, source, error.message)
            } finally {
                pending.finish()
            }
        }
    }

    /** Every enabled source's auth state, the runtime's pending challenge and this install's identity. */
    private suspend fun logState(context: Context) {
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
        // No relay-session field: Hush holds no gateway session any more, and a constant `false`
        // beside the thing being probed would read as a finding rather than as a state that no
        // longer exists (see SpotiFLACInstallIdentity).
        log(
            "state sources=%s installId=%s pendingRuntimeAuth=%s owner=%s",
            sources.joinToString(","),
            SpotiFLACInstallIdentity.installId(context)?.take(8)?.plus("…") ?: "none",
            runtimePending?.authUrl?.take(120) ?: "none",
            runtimePending?.extensionId ?: "none",
        )
        // Read through the same manager the Audio Sources screen renders from, so a probe can say
        // what a row *has* - including the test result and its reason - even though that screen
        // exposes nothing to the accessibility tree.
        val rows = runCatching {
            ExtensionRepositoryManager.getInstance().sources.value.associateBy { it.source.id }
        }.getOrDefault(emptyMap())
        // The same line the Audio Sources row draws for a source whose own service says it cannot
        // serve. Read here for the same reason the row's test result is: that screen exposes nothing
        // to the accessibility tree, so a shell probe is the only way to see what it says.
        val providerNotes = runCatching { bridge.providerHealthNotes() }.getOrDefault(emptyMap())
        for (source in sources) {
            val state = bridge.sourceAuthState(source)
            val pending = bridge.pendingAuthFor(source)
            val row = rows[source]
            val note = providerNotes[source]
            log(
                "state source=%s auth=%s verified=%b test=%s reason=%s provider=%s challengeOwner=%s challenge=%s",
                source,
                state,
                bridge.isSourceVerified(source),
                row?.testState ?: "absent",
                (row?.testError ?: "none").take(60),
                (note ?: "none").take(80),
                pending?.extensionId ?: "none",
                pending?.authUrl?.take(120) ?: "none",
            )
            log(
                "spotiflac-debug step=state source=%s verdict=ok auth=%s verified=%b test=%s provider=%s owner=%s",
                source,
                state,
                bridge.isSourceVerified(source),
                row?.testState ?: "absent",
                (note ?: "none").take(80),
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
     * Downloads a track through the app's real download path.
     *
     * The download/cache relationship is the part that cannot be checked by reading code: a
     * download has to land as one ordinary file in the downloads folder, and the same song must
     * not also be served out of the playback cache once it is removed. Both are exercised here so
     * a headless run can gate on them, because the alternative is tapping the player on a device.
     *
     * `source` is the media id, which is the same key the player and the download record use.
     */
    private fun downloadTrack(
        context: Context,
        mediaId: String,
    ) {
        if (mediaId.isBlank()) {
            return log("spotiflac-debug step=download source=- verdict=FAIL reason=no-media-id")
        }
        val request =
            DownloadRequest
                .Builder(mediaId, mediaId.toUri())
                .setCustomCacheKey(mediaId)
                .setData(mediaId.toByteArray())
                .build()
        DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
        log("spotiflac-debug step=download source=%s verdict=REQUESTED", mediaId)
    }

    /**
     * Removes a download the same way the player's offline badge does, and reports what is left.
     *
     * The interesting part is not the file: it is everything *besides* the file that described
     * it. A record or a cached copy left behind means the next download of that song completes
     * from bytes the user just deleted, so both stores are read back and reported.
     */
    private suspend fun removeDownload(
        context: Context,
        mediaId: String,
    ) {
        if (mediaId.isBlank()) {
            return log("spotiflac-debug step=remove-download source=- verdict=FAIL reason=no-media-id")
        }
        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaId, false)
        // The removal is handled by the download service, so give it a moment before reporting the
        // state it left behind rather than the state it was asked to leave.
        delay(REMOVAL_SETTLE_MS)
        val recordStillIndexed = downloadRecordPresent(context, mediaId)
        val cachedCopy = cachedPlaybackCopy(context, mediaId)
        log(
            "spotiflac-debug step=remove-download source=%s verdict=%s record=%b cachedCopy=%b",
            mediaId,
            if (recordStillIndexed || cachedCopy != null) "FAIL" else "ok",
            recordStillIndexed,
            cachedCopy != null,
        )
    }

    /**
     * Runs the same test the Audio Sources row's button runs, from a shell.
     *
     * The button's verdict is only reachable by tapping it, and a failing verdict is the thing worth
     * attributing, so this calls the identical entry point rather than reproducing it.
     */
    private suspend fun sourceTest(source: String) {
        if (source.isBlank()) {
            return log("spotiflac-debug step=source-test source=- verdict=FAIL reason=no-source")
        }
        val started = System.currentTimeMillis()
        // The same entry point the row's button calls: the source's own extension through the engine,
        // not the relay session the row used to be tested with (and which no longer exists on a
        // signed-session install, so every verdict was a false failure).
        val result = runCatching {
            val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
                ?: return@runCatching Result.failure<String>(
                    IllegalStateException("SpotiFLAC runtime is not available in this build"),
                )
            if (!bridge.isRuntimeAvailable) {
                return@runCatching Result.failure<String>(
                    IllegalStateException("SpotiFLAC runtime is not available in this build"),
                )
            }
            bridge.testSource(source)
        }
            .getOrElse { failure -> Result.failure(failure) }
        val elapsed = System.currentTimeMillis() - started
        val verdict = result.getOrNull()?.take(120)
        log(
            "spotiflac-debug step=source-test source=%s verdict=%s elapsed=%dms detail=%s",
            source,
            if (result.isSuccess) "ok" else "FAIL",
            elapsed,
            verdict ?: result.exceptionOrNull()?.message?.take(160) ?: "no detail",
        )
    }

    /**
     * Fires the route watch's "the gateway is serving us again" callback, as a route change would.
     *
     * The one half of the route watch that cannot be produced from a shell on a blocked device: the
     * watch only calls this after the gateway has *positively* answered a renewal, and a device whose
     * address the gateway is refusing cannot get that answer by any means. Everything upstream of the
     * call - noticing the change, asking once, re-arming on a refusal - is exercised for real by
     * flipping the proxy or the network; this proves what happens downstream, which is otherwise only
     * reachable by waiting out a ~21 hour block on another network.
     *
     * It invokes the same property the watch invokes, so what runs is the player's real replay path and
     * not a mock-up of it.
     */
    private suspend fun replayAfterGatewayReturns() {
        val listener = SpotiFLACRouteWatch.onGatewayReachableAgain
        if (listener == null) {
            return log(
                "spotiflac-debug step=gateway-returned verdict=SKIPPED reason=no-listener",
            )
        }
        listener()
        log("spotiflac-debug step=gateway-returned verdict=OK")
    }

    /**
     * Runs the Audio Sources "Renew" button's own call and reports what it changed on disk.
     *
     * The button is only worth offering if it renews rather than merely re-reading, and the two are
     * indistinguishable from the screen: a renewed session and a recomputed timer both leave a later
     * expiry showing. The gateway answers a genuine renewal with a *new* `session_id` alongside the
     * new `expires_at`, so the record's own generation is reported before and after - an unchanged id
     * with a moved expiry is a timer, a changed id is a renewal.
     */
    private suspend fun renewSessions(context: Context) {
        val before = sessionSnapshot(context)
        val attempt = withContext(Dispatchers.IO) {
            runCatching { SpotiFLACSessionRenewer.renewAll(context, force = true, reason = "debug") }
        }
        val results = attempt.getOrNull()
        if (results == null) {
            return log("spotiflac-debug step=renew verdict=FAIL reason=${attempt.exceptionOrNull()?.message}")
        }
        if (results.isEmpty()) {
            return log("spotiflac-debug step=renew verdict=FAIL reason=no-sessions")
        }
        val after = sessionSnapshot(context)
        results.forEach { result ->
            val was = before[result.extensionId]
            val now = after[result.extensionId]
            log(
                "spotiflac-debug step=renew source=%s renewed=%b detail=%s id=%s->%s expires=%s->%s",
                result.extensionId,
                result.renewed,
                result.detail,
                was?.first ?: "-",
                now?.first ?: "-",
                was?.second ?: "-",
                now?.second ?: "-",
            )
        }
    }

    /** Each extension's session id (short) and stored expiry, as they are right now. */
    private fun sessionSnapshot(context: Context): Map<String, Pair<String, String>> =
        runCatching {
            SpotiFLACSessionRenewer.sessions(context).associate { session ->
                val record = runCatching {
                    Json.parseToJsonElement(session.recordFile.readText()).jsonObject
                }.getOrNull()
                session.extensionId to (
                    (record?.get("session_id")?.jsonPrimitive?.contentOrNull?.take(8) ?: "none") to
                        (record?.get("expires_at")?.jsonPrimitive?.contentOrNull ?: "none")
                    )
            }
        }.getOrDefault(emptyMap())

    /**
     * Forces one source row's test state, so what a row renders can be read back from a shell.
     *
     * The Audio Sources screen exposes nothing to the accessibility tree, so on a device nobody is
     * holding a row cannot be tapped into a failing state - and a failed row is the case worth
     * checking, because its reason used to be drawn in the narrow slot beside the name and pushed
     * the switch and the reorder arrows out of the row. Writing through
     * [ExtensionRepositoryManager.setSourceTestState] means the row receives the same value its own
     * Test button produces, clipping and all, rather than a mock-up of it.
     */
    private suspend fun setSourceRow(
        source: String,
        state: String,
        message: String,
    ) {
        val manager = SpotiFLACNativeRuntimeBridgeHolder.repositoryManager
        if (manager == null) {
            return log("spotiflac-debug step=source-row source=%s verdict=FAIL reason=no-repository", source)
        }
        if (source.isBlank()) {
            return log("spotiflac-debug step=source-row source=- verdict=FAIL reason=no-source")
        }
        val testState =
            when (state.lowercase()) {
                "failed", "fail" -> SourceTestState.FAILED
                "testing" -> SourceTestState.TESTING
                "success", "ok" -> SourceTestState.SUCCESS
                else -> SourceTestState.IDLE
            }
        manager.setSourceTestState(source, testState, message.takeIf { it.isNotBlank() })
        log(
            "spotiflac-debug step=source-row source=%s verdict=ok state=%s messageLength=%d",
            source,
            testState,
            message.length,
        )
    }

    /**
     * Whether the downloads index still lists [mediaId].
     *
     * Read from the index file rather than through the store, because the claim being checked is
     * "the record is gone from disk": a store that still holds it in memory would answer for a
     * state that does not survive the next launch.
     */
    private fun downloadRecordPresent(
        context: Context,
        mediaId: String,
    ): Boolean =
        runCatching {
            val index = java.io.File(context.filesDir, DOWNLOADS_INDEX_FILE_NAME)
            index.isFile && index.readText().contains("\"$mediaId\"")
        }.getOrDefault(false)

    /** The cached playback copy of [mediaId], or null when there is none left. */
    private fun cachedPlaybackCopy(
        context: Context,
        mediaId: String,
    ): String? =
        runCatching {
            SpotiFLACPlaybackCache.getInstance()
                ?.entryForMediaId(mediaId)
                ?.filePath
                ?.takeIf { java.io.File(it).isFile }
        }.getOrNull()

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
        const val OP_DOWNLOAD = "download"
        const val OP_REMOVE_DOWNLOAD = "remove-download"
        const val OP_SOURCE_ROW = "source-row"
        const val OP_SOURCE_TEST = "source-test"
        const val OP_RENEW = "renew"
        const val OP_GATEWAY_RETURNED = "gateway-returned"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        /** The downloads index file, read directly to prove a removal reached the disk. */
        private const val DOWNLOADS_INDEX_FILE_NAME = "downloaded_files.json"

        /** How long a removal is given to reach the stores before it is reported on. */
        private const val REMOVAL_SETTLE_MS = 2_500L

        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_PATH = "path"
    }
}
