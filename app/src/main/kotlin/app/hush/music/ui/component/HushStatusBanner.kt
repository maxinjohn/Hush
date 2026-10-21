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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hush.music.spotiflac.SpotiFLACVerifiedBanner

/**
 * The one pill the app uses to say "something about the app's state just changed".
 *
 * Extracted so the network banner and the SpotiFLAC one cannot drift apart: they share a slot at the
 * top of the window and are read the same way - a glance, no tap - so a second look for the same job
 * would only make the two disagree about how the app talks.
 *
 * The animation belongs to the host, not here: what slides is the banner's appearance, and a pill
 * that always drew itself would leave nothing to animate.
 *
 * [onClick] is off by default because these are meant to be read at a glance. It exists for the one
 * banner that has somewhere to send the user - the storage warning, which leads to the screen where
 * the folder is chosen - and a pill that led nowhere would only teach people not to tap them.
 */
@Composable
fun HushStatusBanner(
    message: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * Reports a source becoming usable again, in the app, at the moment it happens.
 *
 * The same green and the same shape as the network banner's "Back online", and deliberately so: both
 * say that something which was broken a moment ago now works, and a user who has learnt to read one
 * should not have to learn a second vocabulary for the other.
 */
@Composable
fun SpotiFLACVerifiedBannerHost(modifier: Modifier = Modifier) {
    val banner by SpotiFLACVerifiedBanner.message.collectAsState()
    // Held across the exit animation, because a disappearing banner still has to have words to
    // draw: reading the flow directly blanks the text in the same frame the animation starts.
    var lastText by remember { mutableStateOf("") }
    banner?.let { lastText = it.text }
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier,
        enter =
            slideInVertically(animationSpec = tween(durationMillis = 250)) { -it } +
                fadeIn(animationSpec = tween(durationMillis = 180)),
        exit =
            slideOutVertically(animationSpec = tween(durationMillis = 220)) { -it } +
                fadeOut(animationSpec = tween(durationMillis = 180)),
        label = "spotiflacVerifiedBanner",
    ) {
        HushStatusBanner(
            message = lastText,
            icon = Icons.Default.CloudDone,
            containerColor = Color(0xFF1E8E3E),
            contentColor = Color.White,
        )
    }
}
