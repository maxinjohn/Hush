/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hush.music.spotiflac.SpotiFLACBrowserVerification
import app.hush.music.spotiflac.SpotiFLACChallengeEngine
import app.hush.music.spotiflac.SpotiFLACChallengeRoute
import app.hush.music.spotiflac.SpotiFLACDiag
import app.hush.music.spotiflac.SpotiFLAutoVerifier
import app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder
import app.hush.music.spotiflac.SpotiFLACSourceAuthState
import app.hush.music.spotiflac.SpotiFLACVerificationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts a SpotiFLAC extension verification challenge from any screen.
 *
 * Extensions own their own Cloudflare flow: playback reports which extension was
 * blocked, this fetches that extension's challenge URL, runs it in a WebView, and
 * hands the resulting grant to the extension runtime.
 *
 * It observes two different signals, because they have two different owners. Playback
 * raising [SpotiFLACVerificationRequest.pending] shows a dismissible card and nothing else -
 * needing a verification is not a reason to swap the screen out from under the user, which
 * is what used to happen on every track that could not resolve. The full challenge opens
 * only after the user chooses to run it ([SpotiFLACVerificationRequest.challenge]).
 */
@Composable
fun SpotiFLACVerificationOverlay() {
    val context = LocalContext.current
    val pendingExtension by SpotiFLACVerificationRequest.pending.collectAsState()
    val requested by SpotiFLACVerificationRequest.challenge.collectAsState()
    // Automatic run: a source the player or the prewarm could not use. It is driven
    // exactly like a requested one, only without a user in front of it.
    val autoSource by SpotiFLAutoVerifier.active.collectAsState()
    val scope = rememberCoroutineScope()
    val extensionId =
        requested ?: autoSource ?: run {
            SpotiFLACVerificationNotice(extensionId = pendingExtension)
            return
        }
    // Automatic runs render no Cloudflare page: the challenge is still a real,
    // full-size WebView (Turnstile needs a real viewport to solve itself, which is
    // why it used to finish without a tap), it is just invisible.
    val automatic = requested == null && autoSource != null
    val lifecycleOwner = LocalLifecycleOwner.current

    // Which engine would render the challenge, resolved once per composition. A device whose
    // embedded WebView is older than Cloudflare's supported range never gets a token out of
    // it (a car head unit on a frozen Chrome 83 is the case this exists for), so the route
    // has to change rather than the attempt being retried forever.
    val engine = remember { SpotiFLACChallengeEngine.current() }
    val engineCapable = remember(engine) { SpotiFLACChallengeEngine.canSolveCloudflare(engine) }

    var authUrl by remember(extensionId) { mutableStateOf<String?>(null) }
    var expectedState by remember(extensionId) { mutableStateOf<String?>(null) }
    var status by remember(extensionId) { mutableStateOf<String?>(null) }
    var busy by remember(extensionId) { mutableStateOf(false) }
    var grantCaptured by remember(extensionId) { mutableStateOf(false) }
    // The same challenge URL, kept for the browser route. It is what the WebView loads when
    // the engine can solve the check, and it is all we can offer when it cannot.
    var browserChallengeUrl by remember(extensionId) { mutableStateOf<String?>(null) }
    var challengeView by remember(extensionId) { mutableStateOf<WebView?>(null) }
    var pastedCallback by remember(extensionId) { mutableStateOf("") }
    // Bumped whenever the challenge is handed to the browser, so the grant watch restarts on a
    // retry instead of being spent by the first attempt.
    var browserRouteAttempt by remember(extensionId) { mutableStateOf(0) }
    // A grant recovered from a challenge page that was already solved. Held here because the
    // prepare effect runs before `complete` is declared.
    var recoveredGrant by remember(extensionId) { mutableStateOf<String?>(null) }

    fun abandon(reason: String) {
        SpotiFLACDiag.log("verification abandoned for $extensionId: $reason")
        if (automatic) {
            SpotiFLAutoVerifier.finish(extensionId, verified = false)
        } else {
            status = reason
        }
    }

    LaunchedEffect(extensionId) {
        status = "Preparing verification…"
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            abandon("SpotiFLAC runtime is unavailable in this build")
            return@LaunchedEffect
        }
        if (bridge.isSourceVerified(extensionId)) {
            status = "$extensionId is already verified"
            if (automatic) {
                SpotiFLAutoVerifier.finish(extensionId, verified = true)
            } else {
                SpotiFLACVerificationRequest.closeChallenge()
            }
            return@LaunchedEffect
        }
        // A source with no signed-session contract has no challenge to run; asking for one only
        // burned a queue slot and reported a failure the user cannot act on. Skipped rather than
        // failed, so it is neither put in the retry cooldown nor offered as a manual check.
        if (bridge.sourceAuthState(extensionId) == SpotiFLACSourceAuthState.NOT_REQUIRED) {
            SpotiFLACDiag.log(
                "$extensionId signs in with its own service and needs no verification",
            )
            if (automatic) {
                SpotiFLAutoVerifier.finishNotRequired(extensionId)
            } else {
                status = "$extensionId needs no SpotiFLAC verification"
            }
            return@LaunchedEffect
        }
        // ensureChallenge raises a challenge when the runtime had none yet, so an
        // automatic run does not depend on a previous failed download.
        val pending = withContext(Dispatchers.IO) { bridge.ensureChallenge(extensionId) }
        if (pending == null) {
            abandon("No verification challenge for $extensionId")
            return@LaunchedEffect
        }
        expectedState = expectedVerificationState(pending.authUrl)
        browserChallengeUrl = pending.authUrl
        // A challenge that was already passed - in a browser, or by the user before Hush's
        // process was killed - still publishes its unspent grant. Reading it here means such a
        // source verifies on its own with no WebView and no browser, which is the only way an
        // automatic run can complete on a device whose engine cannot run Cloudflare at all.
        SpotiFLACChallengeRoute.grantFromChallengePage(
            SpotiFLACChallengeRoute.readChallengePage(pending.authUrl),
        )?.let { grant ->
            SpotiFLACDiag.log("challenge for $extensionId was already solved; recovering its grant")
            recoveredGrant = grant
            return@LaunchedEffect
        }
        if (engineCapable) {
            authUrl = pending.authUrl
            status = null
            return@LaunchedEffect
        }
        // The one diagnostic that a stalled challenge never produced: name the engine, because
        // "timed out waiting for the challenge" only describes the symptom.
        SpotiFLACDiag.log(
            "challenge engine unsupported (${SpotiFLACChallengeEngine.describe(engine)}) " +
                "extension=$extensionId automatic=$automatic",
        )
        authUrl = null
        if (automatic) {
            // Do not spend the challenge budget on an engine that cannot mint a token: the user
            // gets the manual notice - which carries the browser route - instead of a
            // five-minute march through every source that can only end the same way.
            abandon("${SpotiFLACChallengeEngine.describe(engine)} cannot run Cloudflare's check")
        } else {
            status = SpotiFLACChallengeRoute.unsupportedNotice(engine)
        }
    }

    // An automatic attempt must not hold the queue forever: a challenge page that
    // never finishes (offline, Cloudflare asking for an interaction) would block
    // every later source.
    LaunchedEffect(extensionId, automatic, engineCapable) {
        if (!automatic || !engineCapable) return@LaunchedEffect
        delay(SpotiFLAutoVerifier.CHALLENGE_TIMEOUT_MS)
        if (!grantCaptured) abandon("timed out waiting for the challenge")
    }

    // A challenge that renders off-screen is invisible in every sense: a stalled one and a
    // solving one look identical from outside. Poll the page's own status line - and whether
    // Cloudflare's widget is even present - and log it on change, so a failure on a device we
    // cannot hold names itself instead of needing a reproduction.
    LaunchedEffect(authUrl, extensionId) {
        if (authUrl == null) return@LaunchedEffect
        var last = ""
        repeat(25) {
            delay(3_000)
            if (grantCaptured) return@LaunchedEffect
            val view = challengeView ?: return@LaunchedEffect
            view.evaluateJavascript(SpotiFLACChallengeRoute.STATUS_PROBE) { result ->
                val text = result?.removeSurrounding("\"")?.trim().orEmpty()
                if (text.isNotEmpty() && text != "null" && text != last) {
                    last = text
                    SpotiFLACDiag.log("challenge status[$extensionId] $text")
                }
            }
        }
    }

    fun complete(raw: String) {
        if (grantCaptured) return
        val grant = verificationGrantFrom(raw) ?: return
        val state = verificationStateFrom(raw)
        val expected = expectedState
        if (expected != null && state != null && state != expected) {
            status = "Verification callback did not match the active challenge"
            return
        }
        grantCaptured = true
        busy = true
        status = "Finishing verification…"
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        scope.launch {
            // One solved challenge can refresh every enabled source, so apply the
            // grant to all of them instead of making the user repeat the check per
            // source (and they then expire together rather than one at a time).
            val targets = withContext(Dispatchers.IO) {
                bridge?.grantTargetSourceIds(extensionId) ?: listOf(extensionId)
            }
            withContext(Dispatchers.IO) { bridge?.deliverGrant(grant, targets) }
            val ok = bridge?.isSourceVerified(extensionId) ?: false
            SpotiFLACDiag.log(
                "overlay verification for $extensionId: authenticated=$ok automatic=$automatic " +
                    "targets=${targets.joinToString(",")}",
            )
            busy = false
            authUrl = null
            if (automatic) {
                SpotiFLAutoVerifier.finish(extensionId, verified = ok)
            } else if (ok) {
                status = "$extensionId verified — resuming playback"
                // Report through the surfaces' shared entry point, not just this one: a source may
                // also have been parked by playback, holding a track until it became usable.
                SpotiFLAutoVerifier.notifyVerified(extensionId)
                SpotiFLACVerificationRequest.closeChallenge()
            } else {
                status = "Verification for $extensionId did not complete — try again"
            }
        }
    }

    // Completion for a grant recovered from an already-solved challenge page (see the prepare
    // effect above). It goes through the same path a WebView or browser grant takes, so the
    // runtime sees one kind of delivery.
    LaunchedEffect(recoveredGrant) {
        recoveredGrant?.let { complete("grant=$it") }
    }

    // The browser route is tap-only. A browser cannot launch Hush from the page's redirect (it
    // is script-initiated, and browsers refuse those without a user gesture), so the grant is
    // read back from the challenge page itself, which re-publishes an unspent grant once the
    // check has been solved. The page's copied callback is still watched for the browsers that
    // offer one, and only while Hush is resumed: a background clipboard read is what puts a
    // paste notification on the user's screen.
    LaunchedEffect(extensionId, browserRouteAttempt, browserChallengeUrl, grantCaptured) {
        if (browserRouteAttempt == 0 || grantCaptured) return@LaunchedEffect
        val grant = SpotiFLACChallengeRoute.awaitBrowserGrant(
            owner = lifecycleOwner,
            context = context,
            challengeUrl = browserChallengeUrl,
        ) ?: return@LaunchedEffect
        if (grantCaptured) return@LaunchedEffect
        SpotiFLACDiag.log("browser verification delivered a grant for $extensionId")
        complete(grant)
    }

    Dialog(
        onDismissRequest = {
            if (automatic) SpotiFLAutoVerifier.cancel() else SpotiFLACVerificationRequest.closeChallenge()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // An automatic run renders the same full-size challenge but must not sit between
        // the user and the app: a window that is invisible yet still touchable makes every
        // tap land on a page nobody can see, which reads as "the app stopped responding to
        // my taps". FLAG_NOT_TOUCHABLE lets every touch through while the page keeps its
        // full viewport, so Cloudflare still solves itself.
        val dialogView = LocalView.current
        LaunchedEffect(automatic) {
            if (!automatic) return@LaunchedEffect
            runCatching {
                val provider = dialogView.parent as? DialogWindowProvider
                provider?.window?.setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (automatic) Color.Transparent else MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (!automatic) {
                    Text(
                        text = "SpotiFLAC verification — $extensionId",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Complete the Cloudflare check below. The grant is applied to this source only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val message = status
                if (message != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val url = authUrl
                if (url != null) {
                    AndroidView(
                        factory = {
                            WebView(context).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // The source challenge hands the user to an external
                                // OAuth sign-in, so the WebView must keep that session
                                // (including third-party cookies, which Android
                                // blocks for WebViews by default since API 31) and
                                // reuse it for every later re-verification.
                                val challengeCookies = android.webkit.CookieManager.getInstance()
                                challengeCookies.setAcceptCookie(true)
                                challengeCookies.setAcceptThirdPartyCookies(this, true)
                                // Published so the status poller can ask a stalled challenge what
                                // it is showing, which is the only view into an invisible run.
                                challengeView = this
                                // The challenge page delivers the grant through
                                // window.SpotiflacGrant.postMessage(...) because Chromium
                                // drops script-initiated custom-scheme navigation.
                                addJavascriptInterface(
                                    object : Any() {
                                        @JavascriptInterface
                                        fun postMessage(payload: String) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                complete(payload)
                                            }
                                        }
                                    },
                                    "SpotiflacGrant",
                                )
                                // Cloudflare names an engine it will not challenge through the
                                // page console, and a failed subresource is the difference
                                // between "no widget" and "a widget that never solved". Both go
                                // to the diagnostic log, because an unattended run has no screen.
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                        val text = message?.message() ?: return false
                                        SpotiFLACDiag.log(
                                            "challenge console[$extensionId] " +
                                                "${message.lineNumber()}: ${text.take(200)}",
                                        )
                                        return false
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        SpotiFLACDiag.log(
                                            "challenge load error[$extensionId] ${request?.url} " +
                                                "${error?.errorCode} ${error?.description}",
                                        )
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: android.webkit.WebResourceResponse?,
                                    ) {
                                        SpotiFLACDiag.log(
                                            "challenge http ${errorResponse?.statusCode}" +
                                                "[$extensionId] ${request?.url}",
                                        )
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val target = request?.url?.toString() ?: return false
                                        if (target.contains("grant=") || target.contains("code=")) {
                                            complete(target)
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                        super.onPageFinished(view, finishedUrl)
                                        SpotiFLACDiag.log(
                                            "challenge page[$extensionId] url=$finishedUrl " +
                                                "title=${view?.title}",
                                        )
                                        if (grantCaptured) return
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                try {
                                                    var params = new URLSearchParams(window.location.search);
                                                    var grant = params.get('grant') || params.get('code');
                                                    if (grant) return 'GRANT_FOUND:' + grant;
                                                } catch (e) {}
                                                return 'NO_GRANT';
                                            })();
                                            """.trimIndent(),
                                        ) { result ->
                                            if (result.contains("GRANT_FOUND:") && !grantCaptured) {
                                                val grant = result.substringAfter("GRANT_FOUND:")
                                                    .removeSurrounding("\"")
                                                if (grant.isNotBlank()) complete(grant)
                                            }
                                        }
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .alpha(if (automatic) 0f else 1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (!automatic) {
                    // The escape hatch that does not depend on this device's WebView at all:
                    // the same challenge, solved by the browser, handing its callback back as
                    // a code. On a limited engine it is the only route, and on a capable one it
                    // is what the user reaches for when the page stops cooperating.
                    val browserUrl = browserChallengeUrl
                    if (browserUrl != null && !grantCaptured) {
                        Text(
                            text = "Solve it in your browser and Hush finishes on its own - " +
                                "there is nothing to copy back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    browserRouteAttempt++
                                    SpotiFLACChallengeRoute.openInBrowser(context, browserUrl)
                                },
                            ) {
                                Text("Open in browser")
                            }
                            TextButton(
                                onClick = {
                                    SpotiFLACChallengeRoute.clipboardText(context)
                                        ?.let { pastedCallback = it }
                                },
                            ) {
                                Text("Paste code")
                            }
                        }
                        OutlinedTextField(
                            value = pastedCallback,
                            onValueChange = { pastedCallback = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Verification code or link") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    val callback = pastedCallback.trim()
                                    if (callback.isNotEmpty()) complete(callback)
                                },
                                enabled = pastedCallback.isNotBlank() && !busy,
                            ) {
                                Text("Apply")
                            }
                        }
                    }
                    TextButton(onClick = { SpotiFLACVerificationRequest.closeChallenge() }) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * The passive half of the flow: a dismissible card that says a source needs the user.
 *
 * It never navigates, never opens a dialog, and never blocks the player - the user picks the
 * moment. Background renewal is attempted before this is ever raised, so reaching here
 * means the automatic path already failed.
 */
@Composable
private fun SpotiFLACVerificationNotice(extensionId: String?) {
    val id = extensionId ?: return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val running by SpotiFLACBrowserVerification.running.collectAsState()
    val manualStatus by SpotiFLACBrowserVerification.status.collectAsState()
    // A device whose WebView cannot run Cloudflare's check has no in-app route to offer, so the
    // browser is not an escape hatch there - it is the way verification happens at all.
    val browserOnly = remember { SpotiFLACBrowserVerification.isOnlyRoute() }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "SpotiFLAC needs verification",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text =
                        "$id could not renew its session in the background. Playback will use " +
                            "the fallback source until you verify.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val message = manualStatus
                if (running || message != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = message ?: "Waiting for the browser…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            SpotiFLACBrowserVerification.clearStatus()
                            SpotiFLACVerificationRequest.dismiss()
                        },
                    ) {
                        Text("Later")
                    }
                    // The in-app route stays the default wherever it can work; the browser is
                    // offered beside it as the manual fallback, and on its own where the WebView
                    // cannot solve the check at all (a car head unit).
                    if (!browserOnly) {
                        TextButton(
                            enabled = !running,
                            onClick = { SpotiFLACVerificationRequest.openChallenge(id) },
                        ) {
                            Text("Verify")
                        }
                        TextButton(
                            enabled = !running,
                            onClick = { scope.launch { SpotiFLACBrowserVerification.run(context, owner, id) } },
                        ) {
                            Text("Verify in browser")
                        }
                    } else {
                        TextButton(
                            enabled = !running,
                            onClick = { scope.launch { SpotiFLACBrowserVerification.run(context, owner, id) } },
                        ) {
                            Text("Verify in browser")
                        }
                    }
                }
            }
        }
    }
}

/** Pulls the one-time grant (or `code`) out of a verification callback. */
private fun verificationGrantFrom(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    runCatching {
        val uri = Uri.parse(trimmed)
        uri.getQueryParameter("grant")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.getQueryParameter("cb")?.let { nested -> verificationGrantFrom(nested) }?.let { return it }
    }
    val match = Regex("(?:^|[?&#\\s])(?:grant|code)=([^&#\\s]+)").find(trimmed)
    if (match != null) {
        return runCatching { Uri.decode(match.groupValues[1]) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    // A bare token - what the page's JavaScript channel posts, and what a user pasting by hand
    // may copy - carries no parameters to read. Accepted only when it cannot be anything else,
    // so a pasted sentence or URL is never exchanged as a grant.
    return trimmed.takeIf { BARE_GRANT.matches(it) }
}

/** Length and shape of a bare verification token: unreserved URL characters, nothing else. */
private val BARE_GRANT = Regex("^[A-Za-z0-9._~-]{20,2048}$")

/** Pulls the callback `state` that binds a grant to the challenge that started it. */
private fun verificationStateFrom(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    runCatching {
        val uri = Uri.parse(trimmed)
        uri.getQueryParameter("state")?.takeIf { it.isNotBlank() }?.let { return it }
        for (key in listOf("cb", "callback", "callback_url", "redirect_uri")) {
            val nested = uri.getQueryParameter(key)?.takeIf { it.isNotBlank() } ?: continue
            verificationStateFrom(nested)?.let { return it }
        }
    }
    val match = Regex("(?:^|[?&#\\s])state=([^&#\\s]+)").find(trimmed) ?: return null
    return runCatching { Uri.decode(match.groupValues[1]) }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** Derives the expected callback state from the runtime's verification URL. */
private fun expectedVerificationState(authUrl: String, depth: Int = 0): String? {
    if (depth > 3) return null
    return runCatching {
        val uri = Uri.parse(authUrl.trim())
        uri.getQueryParameter("state")?.takeIf { it.isNotBlank() }?.let { return it }
        for (key in listOf("cb", "callback", "callback_url", "redirect_uri")) {
            val nested = uri.getQueryParameter(key)?.takeIf { it.isNotBlank() } ?: continue
            expectedVerificationState(nested, depth + 1)?.let { return it }
        }
        null
    }.getOrNull()
}
