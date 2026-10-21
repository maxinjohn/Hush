/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hush.music.R
import app.hush.music.constants.SpotiFLACEnabledDefault
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.SpotiFLACLosslessNudgeAskCountKey
import app.hush.music.constants.SpotiFLACLosslessNudgeHandledKey
import app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder
import app.hush.music.utils.dataStore
import app.hush.music.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The launch-time offer to turn on lossless playback.
 *
 * SpotiFLAC is the one thing in the app that changes what a track *is* - a FLAC from Qobuz, Tidal,
 * Deezer or Amazon instead of YouTube's audio - and it is on by default ([SpotiFLACEnabledDefault]),
 * so this offer now speaks to the one user who can still need it: someone who switched the engine off
 * and may not remember which screen that was. It stays an offer rather than a notice: it names the
 * feature, it says who it needs (this device can only offer it in a build that carries the runtime) and
 * it takes the user straight to the switch.
 *
 * ## When it is allowed to speak
 *
 * Decided from the *stored* preferences, once, at launch - never from the preference's default.
 * That distinction is the whole correctness of the thing: every preference read through
 * `rememberPreference` starts at its default value for a frame or two, so a banner that asked
 * "is SpotiFLAC off?" of the composition would be answered "yes" on the very install that had
 * already answered the offer - once per launch, forever. So the stored values are read directly.
 *
 * ## How often it may ask
 *
 * At most [LosslessNudgeMaxAsks] times, ever, per install. Ignoring the offer is not an answer, but
 * it is not an invitation either: every appearance is counted, answered or not, and the count runs
 * out. Two launches, and the offer is over.
 *
 * Two things end it sooner. The close button is the "no", written down once and never undone. And a
 * user who already has SpotiFLAC on has nothing to be offered at all - that preference is read
 * before the offer is made and never consulted again afterwards, and no ask is spent on it, so
 * someone who switches lossless on and later off still has whatever asks were left.
 *
 * The count is written as the offer is *shown* rather than when it is answered, because what it
 * promises is how many times the user sees it; answering the offer - either way - is not what spends
 * an ask.
 *
 * Two more checks, because the offer must not be made where it cannot be kept:
 *
 *  - **the runtime must exist in this build.** `isRuntimeAvailable` reports whether the gobackend
 *    module is present. Without it there is no lossless playback to offer at all, and a build
 *    without it would send the user to a screen whose switch does nothing.
 *  - **the device has to be able to read the answer.** The stored read is guarded: a store that
 *    throws leaves the offer unmade rather than made by default, because being offered something
 *    already answered is the worse failure of the two.
 */
@Composable
fun SpotiFLACLosslessNudgeHost(
    onOpenAudioSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read once: the runtime is either in this build or it is not, and it does not change under us.
    val runtimeAvailable =
        remember { SpotiFLACNativeRuntimeBridgeHolder.instance?.isRuntimeAvailable == true }
    val (_, setHandled) = rememberPreference(SpotiFLACLosslessNudgeHandledKey, defaultValue = false)
    val (_, setAskCount) = rememberPreference(SpotiFLACLosslessNudgeAskCountKey, defaultValue = 0)
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val stored =
            runCatching { context.dataStore.data.first() }.getOrNull()
                ?: return@LaunchedEffect
        val asksMade = stored[SpotiFLACLosslessNudgeAskCountKey] ?: 0
        val due =
            losslessOfferDue(
                enabled = stored[SpotiFLACEnabledKey] ?: SpotiFLACEnabledDefault,
                handled = stored[SpotiFLACLosslessNudgeHandledKey] == true,
                asksMade = asksMade,
                runtimeAvailable = runtimeAvailable,
            )
        if (!due) return@LaunchedEffect
        // Held back a beat so it does not land in the same frame as the home screen: the first
        // frame of a launch is where a banner looks like part of the app.
        delay(RevealDelayMillis)
        // Spent here, as the offer goes up, not when it is answered: what the cap counts is how many
        // times the user was asked, and letting it time out is one of those times.
        setAskCount(asksMade + 1)
        visible = true
        delay(AutoHideMillis)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter =
            slideInVertically(animationSpec = tween(durationMillis = 250)) { -it } +
                fadeIn(animationSpec = tween(durationMillis = 180)),
        exit =
            slideOutVertically(animationSpec = tween(durationMillis = 220)) { -it } +
                fadeOut(animationSpec = tween(durationMillis = 180)),
        label = "spotiflacLosslessNudge",
    ) {
        LosslessNudgeBanner(
            // Opens the switch; the switching is the answer, and it hides the offer by being on. This
            // tap spends nothing extra either: the appearance it was tapped from already did.
            onTurnOn = {
                visible = false
                onOpenAudioSources()
            },
            onDismiss = {
                visible = false
                setHandled(true)
            },
        )
    }
}

/**
 * Whether the lossless offer should be made, given what is stored.
 *
 * Pure, so the rule is pinned by a test instead of by walking the whole sequence on a device - and
 * the interesting directions are the ones a careless edit reverses: asking someone who already said
 * no, asking a third time on an install that has had its two, or asking in a build that cannot
 * deliver what it is offering.
 */
internal fun losslessOfferDue(
    enabled: Boolean,
    handled: Boolean,
    asksMade: Int,
    runtimeAvailable: Boolean,
): Boolean = runtimeAvailable && !enabled && !handled && asksMade < LosslessNudgeMaxAsks

/**
 * How many times the offer may be put to a user in one install.
 *
 * Two, because the first appearance is how someone learns the feature exists at all and the second
 * is the one they get to answer once they have it in mind - and because "at every launch" is how a
 * banner turns into something to be dismissed by reflex.
 */
internal const val LosslessNudgeMaxAsks = 2

/** How long the offer waits after launch before it appears. */
internal const val RevealDelayMillis = 1_200L

/**
 * How long the offer stays up unattended.
 *
 * Far longer than the status banners' 2.5s, because this one asks a question and gives the user two
 * things to read, and it shares their slot. Ignoring it does not answer the offer - but the
 * appearance it spent is still spent, because [LosslessNudgeMaxAsks] counts how many times the user
 * was asked, not how many times they replied.
 */
internal const val AutoHideMillis = 14_000L

/**
 * The offer itself: title, one line of reasons, and the two ways to answer it.
 *
 * A card rather than the [HushStatusBanner] pill: that pill is for a glance the user does not have to
 * act on, and this one is nothing but a question with a button under it, so it carries a second line
 * of text and its own controls instead of pretending to be the same thing.
 */
@Composable
private fun LosslessNudgeBanner(
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(max = 460.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = stringResource(R.string.spotiflac_nudge_title),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = stringResource(R.string.spotiflac_nudge_body),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onTurnOn) {
                Text(
                    text = stringResource(R.string.spotiflac_nudge_action),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
