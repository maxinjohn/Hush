/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How fast Hush's tickers move, in dp per second. */
private val HushMarqueeVelocity: Dp = 40.dp

/**
 * Scrolls [this] content sideways for as long as it is wider than its row.
 *
 * `Modifier.basicMarquee()` on its own stops after three passes - that is its
 * `iterations` default - so a label that is too long for its row reads as "scrolled
 * once, then froze half way", which is as useless as no marquee at all. This pins the
 * parts that make it a real ticker:
 *
 *  - `iterations = Int.MAX_VALUE`, so it never stops in the middle of the label,
 *  - a short initial delay, so the first pass starts while the user is still looking,
 *  - a short pause between passes, so jumping back to the start does not read as a glitch.
 *
 * Nothing moves while the content fits: `basicMarquee` measures its content
 * unconstrained and only animates when it exceeds the available width, so short labels
 * stay perfectly still. That is the point - motion on a label that already fits is
 * distracting, not informative.
 */
fun Modifier.hushMarquee(
    enabled: Boolean = true,
    velocity: Dp = HushMarqueeVelocity,
    initialDelayMillis: Int = 700,
    repeatDelayMillis: Int = 1000,
): Modifier =
    if (enabled) {
        basicMarquee(
            iterations = Int.MAX_VALUE,
            animationMode = MarqueeAnimationMode.Immediately,
            repeatDelayMillis = repeatDelayMillis,
            initialDelayMillis = initialDelayMillis,
            velocity = velocity,
        )
    } else {
        this
    }
