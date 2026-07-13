package app.hush.music.waze

data class HushQueueItem(
    val queueItemId: Long,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
)

data class HushQueueSnapshot(
    val title: String,
    val revision: Long,
    val items: List<HushQueueItem>,
)

data class HushPlaybackSnapshot(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val playerState: Int,
    val playbackSpeed: Float,
    val activeQueueItemId: Long,
    val queue: HushQueueSnapshot,
    val sequenceNumber: Long,
    val timestampMs: Long,
)

interface MetadataUpdateListener {
    fun onPlaybackSnapshot(snapshot: HushPlaybackSnapshot)
}
