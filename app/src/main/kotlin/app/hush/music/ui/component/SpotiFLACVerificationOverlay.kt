/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import android.net.Uri
import android.webkit.JavascriptInterface
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

    var authUrl by remember(extensionId) { mutableStateOf<String?>(null) }
    var expectedState by remember(extensionId) { mutableStateOf<String?>(null) }
    var status by remember(extensionId) { mutableStateOf<String?>(null) }
    var busy by remember(extensionId) { mutableStateOf(false) }
    var grantCaptured by remember(extensionId) { mutableStateOf(false) }

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
        // A source with no signed-session contract has no challenge to run; asking
        // for one only burned a queue slot and reported a failure the user cannot
        // act on.
        if (bridge.sourceAuthState(extensionId) == SpotiFLACSourceAuthState.NOT_REQUIRED) {
            abandon("$extensionId signs in with its own service and needs no verification")
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
        authUrl = pending.authUrl
        status = null
    }

    // An automatic attempt must not hold the queue forever: a challenge page that
    // never finishes (offline, Cloudflare asking for an interaction) would block
    // every later source.
    LaunchedEffect(extensionId, automatic) {
        if (!automatic) return@LaunchedEffect
        delay(SpotiFLAutoVerifier.CHALLENGE_TIMEOUT_MS)
        if (!grantCaptured) abandon("timed out waiting for the challenge")
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
                SpotiFLACVerificationRequest.closeChallenge()
            } else {
                status = "Verification for $extensionId did not complete — try again"
            }
        }
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
                                webViewClient = object : WebViewClient() {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { SpotiFLACVerificationRequest.dismiss() }) {
                        Text("Later")
                    }
                    TextButton(onClick = { SpotiFLACVerificationRequest.openChallenge(id) }) {
                        Text("Verify")
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
    val match = Regex("(?:^|[?&#\\s])(?:grant|code)=([^&#\\s]+)").find(trimmed) ?: return null
    return runCatching { Uri.decode(match.groupValues[1]) }.getOrNull()?.takeIf { it.isNotBlank() }
}

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
