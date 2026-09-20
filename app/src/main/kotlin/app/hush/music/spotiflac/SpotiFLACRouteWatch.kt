/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.utils.NetworkConnectivityObserver
import app.hush.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notices that the route under a remembered gateway block has moved, and asks the gateway about it.
 *
 * A block is the gateway refusing an address, and an address changes far more often than the app
 * does: a VPN is switched on, Wi-Fi hands over to mobile data, a hotspot is joined, a proxy is
 * enabled in Internet settings. Measured on the reporting device, the *same* install that was refused
 * with `{"retry_after":75358}` was served normally the instant a VPN came up - so the block outlived
 * the thing it was about and kept suppressing every source for the ~21 hours it had left. The only way
 * out was for a user to find "Try again now", which needs them to know both that the app was waiting
 * and that it was waiting on something stale.
 *
 * This is the app noticing by itself, at the only moment the answer can have changed. It asks *once*
 * per change, never on a timer: asking a blocking gateway is what extends a block, so the trigger has
 * to be evidence that the situation is different rather than the passage of time.
 *
 * The direction *into* a refused network matters as much as the way out of one, and was the half that
 * was missing: with no block recorded there was nothing to disprove, so nothing was asked, nothing was
 * remembered, and the first play after the switch still walked the whole provider chain before it fell
 * back to YouTube. So a moved route is asked about in both states - under a remembered block it is
 * disproved (or re-armed from the gateway's own timing), and with no block remembered it is probed, and
 * a route the gateway refuses is recorded as one. Either answer is then known *before* the next play,
 * which is the whole point: what the play does should not depend on how long it is willing to wait. Two kinds of
 * evidence are watched, because the two halves of a route arrive by different routes themselves:
 *
 *  - the platform reports a network change, through the same observer the player already uses for its
 *    "waiting for a connection" state;
 *  - a proxy is switched on or off, which nothing in the platform reports at all - it is a preference,
 *    so the preference is what is watched.
 *
 * ## Two fingerprints, because a route has two halves
 *
 * What the watch compares to decide "the route moved" is [SpotiFLACRouteFingerprint.live] - the
 * durable route plus which network is carrying it - while what it compares a *stored block* against is
 * [SpotiFLACRouteFingerprint.current], the durable half alone. Those are not the same question and
 * conflating them broke the commonest case there is: two Wi-Fi networks are both
 * `exit=direct net=wifi`, so switching between them left the durable fingerprint identical, the watch
 * returned early, and nothing was asked - which is a device that moves onto the network its gateway
 * refuses and finds out only when the first play takes a minute to fall through to YouTube. The
 * durable half is what a block can be recorded against (a network handle is reassigned across
 * restarts, so recording one would make every launch a fresh ask); the live half is what a *move in
 * this run of the app* can be noticed by. See [SpotiFLACRouteFingerprint.live].
 *
 * ## Why this is process-wide
 *
 * It started inside [app.hush.music.playback.MusicService], which is the one place that can replay a
 * held track - and that turned out to be the wrong home for the *detection*: the service stops when
 * playback is idle, and the moment a user changes a proxy is precisely a moment they are looking at
 * settings rather than listening. Nothing was watching, so the change was noticed only if playback
 * happened to be running. The watch therefore lives with the process and reports to whoever is
 * listening: the player registers [onGatewayReachableAgain] while it exists, and an app with no
 * playback simply has the block cleared and its sessions minted, ready for the next play.
 */
internal object SpotiFLACRouteWatch {

    /**
     * What to do when the gateway starts answering again: replay whatever the block was holding.
     *
     * A callback held rather than a flow collected, for the same reason the verification hook is: a
     * missed emission would leave a park track with nothing to wake it.
     */
    @Volatile var onGatewayReachableAgain: (suspend () -> Unit)? = null

    /**
     * What to do when the route the device has moved onto is one the gateway refuses.
     *
     * The block is recorded by the time this is called, so the resolver will pass SpotiFLAC over on
     * its own. What the player can add is the track that is *already* parked: it was parked because
     * nothing could answer, and the answer is now that SpotiFLAC cannot be asked at all from here - so
     * it is retried, and lands on the other engine instead of waiting out a countdown.
     */
    @Volatile var onGatewayBlocked: ((SpotiFLACSessionRenewer.RelayBlock) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var started = false

    /** The route as of the last check, so an event that changed nothing costs nothing. */
    @Volatile private var lastRoute: String? = null

    /** The route as of the last check, with the network carrying it - what a move is noticed by. */
    @Volatile private var lastLiveRoute: String? = null

    /** Idempotent: the app may start it from more than one path. */
    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val appContext = context.applicationContext
        val current = currentRoute(appContext)
        lastRoute = current
        lastLiveRoute = liveRoute(appContext)
        // Checked at start as well as on change, because the change can happen while the app is not
        // running: a VPN switched on with Hush closed leaves a block that is already about a route the
        // device has left, and opening the app is exactly when the user expects the wait to be over.
        scope.launch {
            SpotiFLACDiag.log(
                "route watch: started on $current (live ${liveRoute(appContext)}, " +
                    "spotiflac=${spotiFLACEnabled(appContext)})",
            )
            onRouteChanged(appContext, previous = null, current = current, moved = false)
        }
        scope.launch {
            appContext.dataStore.data
                .map(SpotiFLACRouteFingerprint::ofPreferences)
                .distinctUntilChanged()
                .collect { onRouteMaybeChanged(appContext) }
        }
        scope.launch {
            // Owned here rather than handed in: the watch outlives the service, so it cannot borrow an
            // observer that dies with it.
            NetworkConnectivityObserver(appContext).networkStatus.collect {
                onRouteMaybeChanged(appContext)
            }
        }
    }

    private fun onRouteMaybeChanged(context: Context) {
        val live = liveRoute(context)
        if (live == lastLiveRoute) return
        lastLiveRoute = live
        val current = currentRoute(context)
        val previous = lastRoute
        lastRoute = current
        scope.launch {
            onRouteChanged(context, previous = previous, current = current, moved = true)
        }
    }

    /**
     * What to do about the route the device is on now, for both of the answers it can have.
     *
     * [previous] is null for the check at start, where the only thing that can show a move is the route
     * recorded *with* the block - which is the whole reason it is recorded there. [moved] says the live
     * route changed under this run of the app, which is evidence a stored block cannot carry: two
     * Wi-Fi networks have the same durable fingerprint, so a device that has switched between them
     * looks, to everything written down, like it never went anywhere.
     */
    private suspend fun onRouteChanged(
        context: Context,
        previous: String?,
        current: String,
        moved: Boolean,
    ) {
        // Nothing to ask while the engine is switched off, and nothing to clear: the answer cannot
        // change anything, and a request is a cost. What is still worth knowing - and what this branch
        // used to walk past - is that the *address* has moved under the block: a block is the gateway
        // refusing an address, so once the address is known to be another one the remembered wait stops
        // applying (see [SpotiFLACSessionRenewer.relayBlock]) and the engine is tried again on this
        // network. That is the path a user takes by switching SpotiFLAC off before moving networks and
        // on again afterwards, and it is why turning it on does not start from a stale block.
        if (!spotiFLACEnabled(context)) {
            val recordedAddress = SpotiFLACSessionRenewer.storedRelayBlockAddress(context)
            if (moved && SpotiFLACRouteFingerprint.addressChanged(recordedAddress, liveAddress(context))) {
                SpotiFLACDiag.log(
                    "route changed (${liveAddress(context)}): SpotiFLAC is off - nothing asked, but the " +
                        "remembered gateway block was earned on another address and no longer applies",
                )
            } else {
                SpotiFLACDiag.log("route changed ($current): SpotiFLAC is off - nothing to ask")
            }
            return
        }
        // A device that has just left Wi-Fi and has not found mobile data yet carries nothing, and a
        // request made from there can only fail - a failure the app would then have to tell apart from
        // the gateway refusing it. The network coming up is the next event, and it asks properly.
        if (SpotiFLACRouteFingerprint.carriesNoNetwork(current)) {
            SpotiFLACDiag.log("route changed ($current) with no network on it yet - nothing to ask")
            return
        }
        val block = SpotiFLACSessionRenewer.storedRelayBlock(context)
        if (block == null) {
            probeUnblockedRoute(context, previous, current, moved)
            return
        }
        val recorded = SpotiFLACSessionRenewer.storedRelayBlockRoute(context)
        // The address half as well as the route half, for the reason the two exist separately: the
        // durable route cannot tell two Wi-Fi networks apart, so a block earned on one of them used to
        // look like a block earned here - across a restart, and for as long as the wait had left.
        val recordedAddress = SpotiFLACSessionRenewer.storedRelayBlockAddress(context)
        val stillMoved =
            moved || (previous != null && previous != current) ||
                !SpotiFLACRouteFingerprint.stillDescribes(recorded, current) ||
                SpotiFLACRouteFingerprint.addressChanged(recordedAddress, liveAddress(context))
        if (!stillMoved) return
        val live = liveRoute(context)
        SpotiFLACDiag.log(
            "route moved (${previous ?: "?"} -> $current, now $live) under a gateway block (earned on " +
                "${recorded ?: "?"}, " +
                "${SpotiFLACSessionRenewer.formatBlockRemaining(block.remainingMs)} left) - " +
                "asking the gateway once",
        )
        // The forced renewal is what makes this more than a guess: it drops the per-source refusals a
        // block wrote (each of which would otherwise answer out of its own memo), asks once, and either
        // mints the sessions this new route can get or re-arms the countdown from the gateway's own
        // timing. Off the main thread because it is a real request to a real host.
        val outcome =
            withContext(Dispatchers.IO) { SpotiFLACSessionRenewer.reask(context, "route-changed") }
        when {
            outcome.block != null ->
                SpotiFLACDiag.log(
                    "route moved: the gateway still refuses this address - " +
                        "${SpotiFLACSessionRenewer.formatBlockRemaining(outcome.block.remainingMs)} left",
                )
            // Nothing renewed and nothing refused: the ask never got an answer, and a route often
            // changes because the network is moving - so a request that died on the way is not proof
            // that the gateway is fine now. A held track is left to the verification path, which
            // replays it through the same hook a source landing always used.
            !outcome.servedAgain ->
                SpotiFLACDiag.log(
                    "route moved: no answer from the gateway (asked=${outcome.asked}) - " +
                        "holding until something it can answer arrives",
                )
            else -> {
                SpotiFLACDiag.log(
                    "route moved: the gateway is serving this client again - replaying what it held",
                )
                onGatewayReachableAgain?.invoke()
            }
        }
    }

    /**
     * Asks the gateway one question about a route that has no block against it yet.
     *
     * This is the half that was missing, and it is the half that costs the most when it is: a device
     * that moves *onto* a refused network has no block recorded, so nothing was asked, nothing was
     * remembered, and the first play walked the entire provider chain - every provider refusing in
     * turn, and every one of those requests another contact during a block - before the track finally
     * reached YouTube. Measured on the reporting device that is the difference between a song that
     * starts and one that sits at 0:00 for the length of the sweep.
     *
     * One GET, no session state, and no clearing of anything: if the gateway serves this route nothing
     * changes and nothing is thrown away, and if it refuses it, the block is recorded from its own
     * answer - which is what makes the very next play skip SpotiFLAC outright.
     */
    private suspend fun probeUnblockedRoute(
        context: Context,
        previous: String?,
        current: String,
        moved: Boolean,
    ) {
        SpotiFLACDiag.log(
            "route changed (${previous ?: "?"} -> $current, now ${liveRoute(context)}) " +
                "with no block remembered - asking the gateway once",
        )
        val probe = SpotiFLACSessionRenewer.probeRoute(context, reason = "route-changed")
        when {
            probe.block != null -> {
                SpotiFLACDiag.log(
                    "route changed: the gateway refuses this address - " +
                        "${SpotiFLACSessionRenewer.formatBlockRemaining(probe.block.remainingMs)} left, " +
                        "so SpotiFLAC is passed over until the route moves again",
                )
                onGatewayBlocked?.invoke(probe.block)
            }

            // Not asked means a probe answered this within the minute, or a block appeared meanwhile -
            // and the block case was handled before this was called. Silent on purpose: it is not an
            // answer about the route, and saying otherwise is how a route looks checked when it is not.
            !probe.asked ->
                SpotiFLACDiag.log(
                    "route changed: not asked (a probe already answered this route within the minute)",
                )

            // Asked and nothing came back. This is the state that used to be read as "served", and it
            // is the state a route change arrives in most often: the network is still coming up, so the
            // request dies before the gateway ever sees it. Acting on it would clear every refusal the
            // previous route had earned and record nothing about this one - so the first play here walks
            // the whole provider chain, and the block the gateway would have announced goes unstated for
            // the rest of the session. Nothing is cleared and nothing is concluded; the event that ends
            // the settling (the network reporting validated) asks again.
            !probe.answering ->
                SpotiFLACDiag.log(
                    "route changed: no answer from the gateway on this route yet - nothing cleared",
                )

            else -> {
                // Served, so this is the route the app will play on - and everything it recorded about
                // the *previous* one is now about an address it has left. The per-source refusals are
                // dropped locally (no request at all), and whatever is due for renewal is renewed, so
                // the first play here asks the gateway properly instead of answering out of a memo
                // written somewhere else. That is the "verify against the network I am on now" a user
                // would otherwise do by hand, and it costs one request per session that is due.
                clearRouteBoundRefusals(context)
                val renewed =
                    runCatching {
                        SpotiFLACSessionRenewer.renewAll(context, reason = "route-changed")
                    }.getOrNull()
                SpotiFLACDiag.log(
                    "route changed: the gateway serves this route - per-source refusals cleared, " +
                        "renewed=${renewed?.count { it.renewed } ?: 0} of ${renewed?.size ?: 0}",
                )
                // And whatever the *previous* route was holding is re-resolved, because the reason it
                // was held has just stopped being true. Without this a track that failed on the blocked
                // network stays parked after the move - its position is only retried when a verification
                // lands or a block clears, and neither of those happens on a route that was never
                // blocked. That is the "I changed the network and it still would not play" report.
                if (moved) onGatewayReachableAgain?.invoke()
            }
        }
    }

    /**
     * Drops every per-source refusal, because each was written about the route the device just left.
     *
     * Local only: this is the app forgetting its own notes, not asking anything. A refusal is kept
     * against the session generation it was written for, and it is what stops a renewal from being
     * attempted - so leaving them in place means a source that was turned down on a blocked network is
     * never re-asked on the network the device is on now.
     */
    private fun clearRouteBoundRefusals(context: Context) {
        runCatching {
            SpotiFLACSessionRenewer.sessions(context).forEach { session ->
                SpotiFLACSessionRenewer.clearRenewalRejection(context, session.extensionId)
            }
        }
    }

    /**
     * Whether the engine this watch exists for is switched on.
     *
     * Read out of the store rather than out of the in-process cache `PreferenceStore` keeps, because of
     * where the check at start runs from: `Application.onCreate`, which is before that cache has seen
     * its first emission, and `PreferenceStore.get` answers `null` until it has. Read as "off", that
     * null is why the check at start - the one that exists for a block earned while the app was closed
     * - silently never ran, and the app sat on a wait the route had already left. This is a suspend
     * function on a background dispatcher, so waiting for the value costs the caller nothing.
     */
    private suspend fun spotiFLACEnabled(context: Context): Boolean =
        runCatching { context.dataStore.data.first()[SpotiFLACEnabledKey] }.getOrNull() == true

    /** Never throws: a fingerprint that cannot be read must not take the process down with it. */
    private fun currentRoute(context: Context): String =
        runCatching { SpotiFLACRouteFingerprint.current(context) }.getOrElse { "unknown" }

    /** The route with the network carrying it, for noticing a move the durable half cannot. */
    private fun liveRoute(context: Context): String =
        runCatching { SpotiFLACRouteFingerprint.live(context) }.getOrElse { "unknown" }

    /** The same, without the settling flag: the address half, which is what a stored answer is about. */
    private fun liveAddress(context: Context): String =
        runCatching { SpotiFLACRouteFingerprint.liveAddress(context) }.getOrElse { "unknown" }
}
