/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.menu

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.hush.music.LocalDatabase
import app.hush.music.LocalPlayerConnection
import app.hush.music.R
import app.hush.music.constants.SpeedDialSongIdsKey
import app.hush.music.db.entities.ArtistEntity
import app.hush.music.innertube.models.ArtistItem
import app.hush.music.playback.queues.YouTubeQueue
import app.hush.music.ui.component.MenuSurfaceSection
import app.hush.music.ui.component.menuActionIconSize
import app.hush.music.ui.component.NewAction
import app.hush.music.ui.component.NewActionGrid
import app.hush.music.ui.component.YouTubeListItem
import app.hush.music.utils.SpeedDialPin
import app.hush.music.utils.SpeedDialPinType
import app.hush.music.utils.parseSpeedDialPins
import app.hush.music.utils.rememberPreference
import app.hush.music.utils.serializeSpeedDialPins
import app.hush.music.utils.toggleSpeedDialPin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeArtistMenu(
    artist: ArtistItem,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val libraryArtist by database.artist(artist.id).collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val (speedDialSongIds, onSpeedDialSongIdsChange) = rememberPreference(SpeedDialSongIdsKey, "")
    val speedDialPins = remember(speedDialSongIds) { parseSpeedDialPins(speedDialSongIds) }
    val artistPin = remember(artist.id) { SpeedDialPin(type = SpeedDialPinType.ARTIST, id = artist.id) }
    val isInSpeedDial =
        remember(speedDialPins, artistPin) {
            speedDialPins.any { it.type == artistPin.type && it.id == artistPin.id }
        }

    YouTubeListItem(
        item = artist,
        trailingContent = {},
    )

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val dividerModifier = Modifier.padding(start = 56.dp)
    LazyColumn(
        userScrollEnabled = true,
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        item {
            MenuSurfaceSection() {
                NewActionGrid(
                    actions =
                        buildList {
                            artist.radioEndpoint?.let { watchEndpoint ->
                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.radio),
                                                contentDescription = null,
                                                modifier = Modifier.size(menuActionIconSize()),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.start_radio),
                                        onClick = {
                                            playerConnection.playQueue(YouTubeQueue(watchEndpoint))
                                            onDismiss()
                                        },
                                    ),
                                )
                            }

                            artist.shuffleEndpoint?.let { watchEndpoint ->
                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.shuffle),
                                                contentDescription = null,
                                                modifier = Modifier.size(menuActionIconSize()),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.shuffle),
                                        onClick = {
                                            playerConnection.playQueue(YouTubeQueue(watchEndpoint))
                                            onDismiss()
                                        },
                                    ),
                                )
                            }

                            add(
                                NewAction(
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.share),
                                            contentDescription = null,
                                            modifier = Modifier.size(menuActionIconSize()),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    text = stringResource(R.string.share),
                                    onClick = {
                                        val intent =
                                            Intent().apply {
                                                action = Intent.ACTION_SEND
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, artist.shareLink)
                                            }
                                        context.startActivity(Intent.createChooser(intent, null))
                                        onDismiss()
                                    },
                                ),
                            )
                        },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            MenuSurfaceSection() {
                Column {
                    ListItem(
                        headlineContent = {
                            Text(
                                text =
                                    if (libraryArtist?.artist?.bookmarkedAt !=
                                        null
                                    ) {
                                        stringResource(R.string.subscribed)
                                    } else {
                                        stringResource(R.string.subscribe)
                                    },
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter =
                                    painterResource(
                                        if (libraryArtist?.artist?.bookmarkedAt != null) {
                                            R.drawable.subscribed
                                        } else {
                                            R.drawable.subscribe
                                        },
                                    ),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                database.query {
                                    val libraryArtist = libraryArtist
                                    if (libraryArtist != null) {
                                        update(libraryArtist.artist.toggleLike())
                                    } else {
                                        insert(
                                            ArtistEntity(
                                                id = artist.id,
                                                name = artist.title,
                                                channelId = artist.channelId,
                                                thumbnailUrl = artist.thumbnail,
                                            ).toggleLike(),
                                        )
                                    }
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                    HorizontalDivider(
                        modifier = dividerModifier,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    ListItem(
                        headlineContent = {
                            Text(
                                text =
                                    stringResource(
                                        if (isInSpeedDial) {
                                            R.string.remove_from_speed_dial
                                        } else {
                                            R.string.pin_to_speed_dial
                                        },
                                    ),
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(if (isInSpeedDial) R.drawable.bookmark_filled else R.drawable.bookmark),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                coroutineScope.launch {
                                    if (!isInSpeedDial) {
                                        withContext(Dispatchers.IO) {
                                            database.transaction {
                                                insert(
                                                    ArtistEntity(
                                                        id = artist.id,
                                                        name = artist.title,
                                                        channelId = artist.channelId,
                                                        thumbnailUrl = artist.thumbnail,
                                                    ),
                                                )
                                            }
                                        }
                                    }

                                    val updatedPins = toggleSpeedDialPin(speedDialPins, artistPin)
                                    onSpeedDialSongIdsChange(serializeSpeedDialPins(updatedPins))
                                    onDismiss()
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
