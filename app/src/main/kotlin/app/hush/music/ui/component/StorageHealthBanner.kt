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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.hush.music.R
import app.hush.music.storage.StorageFolderHealth

/**
 * Says that the folder downloads live in cannot be used, and stays saying it.
 *
 * Unlike the other pills in this slot, this is not news about something that just happened: it is a
 * standing problem that stops the next download from working and puts the downloads already on the
 * device out of reach. So it does not time itself out - it goes away when the folder is usable
 * again - and it is the one banner here that is worth a tap, which takes the user to the storage
 * settings where the folder is chosen.
 */
@Composable
fun StorageHealthBanner(
    health: StorageFolderHealth,
    onOpenStorageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messageResId =
        when (health) {
            StorageFolderHealth.Healthy -> null
            is StorageFolderHealth.Missing -> R.string.downloads_folder_missing_banner
            is StorageFolderHealth.NotWritable -> R.string.downloads_folder_not_writable_banner
            is StorageFolderHealth.NeedsAllFilesAccess -> R.string.downloads_folder_needs_access_banner
        }
    // Held across the exit animation, because a banner on its way out still has to have words to
    // draw - reading the state directly would blank the text in the same frame the animation starts.
    var lastMessage by remember { mutableStateOf("") }
    messageResId?.let { resId -> lastMessage = stringResource(resId) }

    AnimatedVisibility(
        visible = messageResId != null,
        modifier = modifier,
        enter =
            slideInVertically(animationSpec = tween(durationMillis = 250)) { -it } +
                fadeIn(animationSpec = tween(durationMillis = 180)),
        exit =
            slideOutVertically(animationSpec = tween(durationMillis = 220)) { -it } +
                fadeOut(animationSpec = tween(durationMillis = 180)),
        label = "storageHealthBanner",
    ) {
        HushStatusBanner(
            message = lastMessage,
            icon = Icons.Default.Warning,
            containerColor = Color(0xFF7F1D1D),
            contentColor = Color.White,
            onClick = onOpenStorageSettings,
        )
    }
}
