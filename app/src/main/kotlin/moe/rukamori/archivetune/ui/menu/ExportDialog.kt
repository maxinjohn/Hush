/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.DefaultDialog

@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    initialFormat: String = "csv",
    onShare: (format: String) -> Unit,
    onSave: (format: String) -> Unit,
) {
    var selected by remember { mutableStateOf(initialFormat) }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.export_playlist)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(onClick = { onSave(selected) }) {
                Text(text = stringResource(R.string.export_option_save))
            }
            TextButton(onClick = { onShare(selected) }) {
                Text(text = stringResource(R.string.export_option_share))
            }
        },
        horizontalAlignment = Alignment.Start,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = "csv" }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                RadioButton(selected = selected == "csv", onClick = null)
                Text(
                    text = stringResource(R.string.export_as_csv),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = "m3u" }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                RadioButton(selected = selected == "m3u", onClick = null)
                Text(
                    text = stringResource(R.string.export_as_m3u),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
