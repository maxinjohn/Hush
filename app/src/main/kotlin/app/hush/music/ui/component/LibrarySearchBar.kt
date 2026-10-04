/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.hush.music.R

/**
 * The library screen's search bar (Metrolist's LibrarySearchHeader, host-level edition).
 *
 * Metrolist renders this header inside every library sub-screen, swapping between a sort
 * header and a text field. Hush's library is one pager host, so the bar lives above the
 * pager instead: a single always-visible field whose query is shared by every sub-screen
 * and survives tab switches. Clears via the trailing close button.
 */
@Composable
fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_library),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            onQueryChange("")
                            keyboardController?.hide()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            modifier = Modifier.weight(1f),
        )
    }
}
