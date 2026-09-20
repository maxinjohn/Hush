/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import app.hush.music.LocalDownloadUtil
import app.hush.music.utils.DeviceProfile
import app.hush.music.utils.MotionCadence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Remembers a flow for the life of [keys] so a recomposition cannot re-subscribe it.
 *
 * `collectAsState` keys its collector on the flow *instance*, so a flow created inline
 * in a composable is torn down and re-collected on every recomposition. For a Room
 * flow that is not just a coroutine: re-subscribing re-registers with the
 * invalidation tracker and re-runs the query, so a player surface was re-querying the
 * database on each frame it recomposed.
 */
@Composable
fun <T> rememberFlow(
    vararg keys: Any?,
    create: () -> Flow<T>,
): Flow<T> = remember(*keys) { create() }

/** The device's motion cadence, read once per composition instead of per frame. */
@Composable
fun rememberMotionCadence(): MotionCadence {
    val context = LocalContext.current
    return remember(context) { DeviceProfile.cadence(context) }
}

/**
 * One row's download entry, without re-composing that row when a different download advances.
 *
 * `DownloadUtil.downloads` is a single map that is republished on every progress update, so a
 * row collecting it directly recomposed on every tick of every download on the device - a
 * screenful of results meant dozens of full row recompositions a second while anything was
 * downloading. Selecting this row's entry first, then dropping repeats, leaves the row still
 * until its own download actually changes.
 *
 * The flow is also remembered rather than built inline: `collectAsState` keys its collector
 * on the flow instance, and a flow built inside a composable is a new instance on every
 * recomposition, so an inline call re-subscribed - and, for a Room query, re-ran it - on
 * every frame.
 */
@Composable
fun rememberDownload(songId: String): Download? {
    val downloadUtil = LocalDownloadUtil.current
    val downloadFlow =
        remember(songId, downloadUtil) {
            downloadUtil.downloads.map { it[songId] }.distinctUntilChanged()
        }
    // Only the first frame needs a value before the flow starts; taking it inside `remember`
    // keeps it out of composition, since the collection below is what observes changes.
    val initialDownload =
        remember(songId, downloadUtil) {
            downloadUtil.downloads.value[songId]
        }
    return downloadFlow
        .collectAsStateWithLifecycle(initialValue = initialDownload)
        .value
}
