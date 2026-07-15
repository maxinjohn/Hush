/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 */

package app.hush.music.ui.menu

import android.content.ContentResolver
import android.content.Intent
import android.media.RingtoneManager
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.hush.music.R
import app.hush.music.ui.component.NewAction
import app.hush.music.ui.component.menuActionIconSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberSetAsRingtoneAction(localMediaId: String?): NewAction? {
    val ringtoneMediaId = localMediaId?.takeIf(String::isMediaStoreContentUri) ?: return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingRingtoneMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    val setRingtone: (String) -> Unit =
        remember(context, coroutineScope) {
            { mediaId ->
                coroutineScope.launch {
                    val didSetRingtone =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                RingtoneManager.setActualDefaultRingtoneUri(
                                    context,
                                    RingtoneManager.TYPE_RINGTONE,
                                    mediaId.toUri(),
                                )
                            }.isSuccess
                        }
                    Toast
                        .makeText(
                            context,
                            if (didSetRingtone) R.string.ringtone_set else R.string.ringtone_set_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    val ringtoneSettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val mediaId = pendingRingtoneMediaId ?: return@rememberLauncherForActivityResult
            pendingRingtoneMediaId = null
            if (Settings.System.canWrite(context)) {
                setRingtone(mediaId)
            } else {
                Toast.makeText(context, R.string.ringtone_permission_required, Toast.LENGTH_SHORT).show()
            }
        }
    val text = stringResource(R.string.set_as_ringtone)

    return remember(ringtoneMediaId, text, context) {
        NewAction(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.vibration),
                    contentDescription = null,
                    modifier = Modifier.size(menuActionIconSize()),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            text = text,
            onClick = {
                if (Settings.System.canWrite(context)) {
                    setRingtone(ringtoneMediaId)
                } else {
                    pendingRingtoneMediaId = ringtoneMediaId
                    ringtoneSettingsLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        ),
                    )
                }
            },
        )
    }
}

private fun String.isMediaStoreContentUri(): Boolean {
    val uri = toUri()
    return uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) && uri.authority == MediaStore.AUTHORITY
}
