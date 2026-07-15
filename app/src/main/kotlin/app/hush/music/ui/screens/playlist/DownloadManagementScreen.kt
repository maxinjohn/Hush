/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import app.hush.music.LocalDatabase
import app.hush.music.LocalDownloadUtil
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.LocalPlayerConnection
import app.hush.music.R
import app.hush.music.db.entities.Song
import app.hush.music.extensions.toMediaItem
import app.hush.music.extensions.togglePlayPause
import app.hush.music.playback.ExoDownloadService
import app.hush.music.playback.queues.ListQueue
import app.hush.music.ui.component.EmptyPlaceholder
import app.hush.music.ui.component.IconButton as AppIconButton
import app.hush.music.ui.component.ListItem
import app.hush.music.ui.component.SongListItem
import app.hush.music.ui.utils.HeaderDownloadProgressIndicator
import app.hush.music.ui.utils.backToMain
import app.hush.music.ui.utils.sendPauseDownloads
import app.hush.music.ui.utils.sendRemoveDownloads
import app.hush.music.ui.utils.sendResumeDownloads
import app.hush.music.utils.joinByBullet
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagementScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val isPlaying = playerConnection?.isPlaying?.collectAsStateWithLifecycle()?.value ?: false
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value
    val downloads by LocalDownloadUtil.current.downloads.collectAsStateWithLifecycle()
    val songs by LocalDatabase.current.allSongs().collectAsStateWithLifecycle(emptyList())
    val songById = remember(songs) { songs.associateBy { it.id } }
    val visibleDownloads =
        remember(downloads) {
            downloads.values.filter { it.state != Download.STATE_REMOVING }
        }

    val activeDownloads =
        remember(visibleDownloads) {
            visibleDownloads
                .filter {
                    it.state == Download.STATE_QUEUED ||
                        it.state == Download.STATE_DOWNLOADING ||
                        it.state == Download.STATE_RESTARTING
                }.sortedByDescending { it.updateTimeMs }
        }
    val pausedDownloads =
        remember(visibleDownloads) {
            visibleDownloads
                .filter { it.state == Download.STATE_STOPPED }
                .sortedByDescending { it.updateTimeMs }
        }
    val failedDownloads =
        remember(visibleDownloads) {
            visibleDownloads
                .filter { it.state == Download.STATE_FAILED }
                .sortedByDescending { it.updateTimeMs }
        }
    val completedDownloads =
        remember(visibleDownloads) {
            visibleDownloads
                .filter { it.state == Download.STATE_COMPLETED }
                .sortedByDescending { it.updateTimeMs }
        }
    val completedSongs =
        remember(completedDownloads, songById) {
            completedDownloads.mapNotNull { songById[it.request.id] }
        }
    val playerPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val activeTitle = stringResource(R.string.downloads_active)
    val pausedTitle = stringResource(R.string.downloads_paused)
    val failedTitle = stringResource(R.string.downloads_failed)
    val completedTitle = stringResource(R.string.downloads_completed)

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloaded_songs)) },
                navigationIcon = {
                    AppIconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        if (visibleDownloads.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(playerPadding),
            ) {
                EmptyPlaceholder(
                    icon = R.drawable.download,
                    text = stringResource(R.string.downloads_empty),
                )
            }
        } else {
            LazyColumn(
                contentPadding =
                    PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        bottom = maxOf(contentPadding.calculateBottomPadding(), playerPadding.calculateBottomPadding()),
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                downloadSection(
                    title = activeTitle,
                    downloads = activeDownloads,
                ) { download ->
                    DownloadListItem(
                        download = download,
                        song = songById[download.request.id],
                        state = DownloadListState.Active,
                        onPrimaryAction = { sendPauseDownloads(context, listOf(download.request.id)) },
                        onRemove = { sendRemoveDownloads(context, listOf(download.request.id)) },
                    )
                }
                downloadSection(
                    title = pausedTitle,
                    downloads = pausedDownloads,
                ) { download ->
                    DownloadListItem(
                        download = download,
                        song = songById[download.request.id],
                        state = DownloadListState.Paused,
                        onPrimaryAction = { sendResumeDownloads(context, listOf(download.request.id)) },
                        onRemove = { sendRemoveDownloads(context, listOf(download.request.id)) },
                    )
                }
                downloadSection(
                    title = failedTitle,
                    downloads = failedDownloads,
                ) { download ->
                    DownloadListItem(
                        download = download,
                        song = songById[download.request.id],
                        state = DownloadListState.Failed,
                        onPrimaryAction = {
                            DownloadService.sendAddDownload(
                                context,
                                ExoDownloadService::class.java,
                                download.request,
                                false,
                            )
                        },
                        onRemove = { sendRemoveDownloads(context, listOf(download.request.id)) },
                    )
                }
                downloadSection(
                    title = completedTitle,
                    downloads = completedDownloads,
                ) { download ->
                    val song = songById[download.request.id]
                    if (song == null) {
                        DownloadListItem(
                            download = download,
                            song = null,
                            state = DownloadListState.Completed,
                            onPrimaryAction = null,
                            onRemove = { sendRemoveDownloads(context, listOf(download.request.id)) },
                        )
                    } else {
                        CompletedDownloadSong(
                            song = song,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            onRemove = { sendRemoveDownloads(context, listOf(download.request.id)) },
                            onPlay = {
                                playerConnection?.let { connection ->
                                    if (song.id == mediaMetadata?.id) {
                                        connection.player.togglePlayPause()
                                    } else {
                                        connection.playQueue(
                                            ListQueue(
                                                title = context.getString(R.string.downloaded_songs),
                                                items = completedSongs.map { it.toMediaItem() },
                                                startIndex = completedSongs.indexOf(song),
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.downloadSection(
    title: String,
    downloads: List<Download>,
    content: @Composable (Download) -> Unit,
) {
    if (downloads.isEmpty()) return

    item(key = "section_$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
        )
    }
    items(
        items = downloads,
        key = { it.request.id },
    ) {
        content(it)
    }
}

@Composable
private fun CompletedDownloadSong(
    song: Song,
    isActive: Boolean,
    isPlaying: Boolean,
    onRemove: () -> Unit,
    onPlay: () -> Unit,
) {
    SongListItem(
        song = song,
        isActive = isActive,
        isPlaying = isPlaying,
        showInLibraryIcon = true,
        modifier = Modifier.clickable(onClick = onPlay),
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.remove_download),
                )
            }
        },
    )
}

@Composable
private fun DownloadListItem(
    download: Download,
    song: Song?,
    state: DownloadListState,
    onPrimaryAction: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val progress = download.percentDownloaded.takeIf { it >= 0f }
    val stateText =
        stringResource(
            when (state) {
                DownloadListState.Active -> R.string.downloading
                DownloadListState.Paused -> R.string.downloads_paused
                DownloadListState.Failed -> R.string.downloads_failed
                DownloadListState.Completed -> R.string.downloads_completed
            },
        )
    val title = song?.song?.title ?: Util.fromUtf8Bytes(download.request.data).ifBlank { download.request.id }

    ListItem(
        title = title,
        subtitle =
            joinByBullet(
                stateText,
                progress?.let { stringResource(R.string.download_progress_percent, it.roundToInt()) },
            ),
        thumbnailContent = {
            if (state == DownloadListState.Active) {
                HeaderDownloadProgressIndicator(
                    progress = (progress ?: 0f) / 100f,
                )
            } else {
                Icon(
                    painter =
                        painterResource(
                            when (state) {
                                DownloadListState.Failed -> R.drawable.error
                                DownloadListState.Completed -> R.drawable.offline
                                else -> R.drawable.download
                            },
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        trailingContent = {
            if (onPrimaryAction != null) {
                IconButton(onClick = onPrimaryAction) {
                    Icon(
                        painter =
                            painterResource(
                                when (state) {
                                    DownloadListState.Active -> R.drawable.pause
                                    DownloadListState.Paused -> R.drawable.play
                                    DownloadListState.Failed -> R.drawable.replay
                                    DownloadListState.Completed -> R.drawable.offline
                                },
                            ),
                        contentDescription =
                            stringResource(
                                when (state) {
                                    DownloadListState.Active -> R.string.widget_pause
                                    DownloadListState.Paused -> R.string.play
                                    DownloadListState.Failed -> R.string.retry
                                    DownloadListState.Completed -> R.string.downloads_completed
                                },
                            ),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.remove_download),
                )
            }
        },
    )
}

private enum class DownloadListState {
    Active,
    Paused,
    Failed,
    Completed,
}
