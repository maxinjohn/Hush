/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.utils.PlaybackDownloadProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val progressJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Result of one `waitForMultiProgressDelta` payload. */
internal data class SpotiFLACProgressDelta(
    /** Revision to pass to the next delta call. */
    val seq: Long,
    /** Progress for [itemId], when this delta mentions it. */
    val progress: PlaybackDownloadProgress?,
) {
    /**
     * Whether this delta says anything about the item being waited on.
     *
     * The stream is multi-item, so a delta carrying only *other* items still has a newer
     * [seq]. Treating "a delta arrived" as liveness is what held wedged providers open:
     * measured on device, amazon and soundcloud each sat for the full 25s attempt ceiling
     * on a track they never progressed on, because other tracks' samples kept resetting
     * the stall clock, and a sweep for an unservable track took 74s instead of seconds.
     *
     * A stage transition counts as item-scoped: the runtime reports one as a progress
     * entry with zero bytes (`checking_session` -> `resolving_metadata`).
     */
    val isItemScoped: Boolean get() = progress != null
}

/**
 * Parses the extension runtime's `MultiProgressDelta` payload.
 *
 * The runtime answers `{"seq":N,"reset":true|false,"items":{"<item_id>":{...}}}` and
 * returns an empty string when nothing changed. Only the requested item is read, so
 * unrelated transfers cannot move the player's progress bar.
 */
internal fun parseSpotiFLACProgressDelta(
    raw: String,
    itemId: String,
    mediaId: String,
    sourceId: String,
): SpotiFLACProgressDelta? {
    if (raw.isBlank()) return null
    val delta = runCatching { progressJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return null
    val seq = delta["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    val items = delta["items"] as? JsonObject ?: return SpotiFLACProgressDelta(seq, null)
    val item = items[itemId] as? JsonObject ?: return SpotiFLACProgressDelta(seq, null)
    val fraction = item["progress"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
    return SpotiFLACProgressDelta(
        seq = seq,
        progress =
            PlaybackDownloadProgress(
                mediaId = mediaId,
                sourceId = sourceId,
                percent = ((fraction * 100).toInt()).coerceIn(0, 100),
                bytesReceived = item["bytes_received"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                bytesTotal = item["bytes_total"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                speedMbps = item["speed_mbps"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                stage = item["stage"]?.jsonPrimitive?.content,
                status = item["status"]?.jsonPrimitive?.content,
            ),
    )
}

