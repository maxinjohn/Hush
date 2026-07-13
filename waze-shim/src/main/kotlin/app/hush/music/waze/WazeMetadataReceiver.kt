package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

class WazeMetadataReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WazeMetadataReceiver"
    }

    private var listener: MetadataUpdateListener? = null

    fun attach(listener: MetadataUpdateListener) {
        this.listener = listener
    }

    fun detach() {
        this.listener = null
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "app.hush.music.WAZE_METADATA_UPDATE") return

        val title = intent.getStringExtra("title") ?: return
        val artist = intent.getStringExtra("artist") ?: ""
        val album = intent.getStringExtra("album") ?: ""
        val queueItems = intent.getParcelableArrayListExtra<Bundle>("queue_items")
            ?.map { item ->
                HushQueueItem(
                    queueItemId = item.getLong("queue_item_id", -1L),
                    trackId = item.getString("track_id").orEmpty(),
                    title = item.getString("title").orEmpty(),
                    artist = item.getString("artist").orEmpty(),
                    album = item.getString("album").orEmpty(),
                    artworkUrl = item.getString("artwork_url"),
                )
            }
            ?.filter { item -> item.queueItemId >= 0L }
            ?: emptyList()
        val snapshot = HushPlaybackSnapshot(
            trackId = intent.getStringExtra("track_id").orEmpty(),
            title = title,
            artist = artist,
            album = album,
            artworkUrl = intent.getStringExtra("artwork_url"),
            durationMs = intent.getLongExtra("duration", 0L),
            positionMs = intent.getLongExtra("position", 0L),
            bufferedPositionMs = intent.getLongExtra("buffered_position", 0L),
            isPlaying = intent.getBooleanExtra("is_playing", false),
            playWhenReady = intent.getBooleanExtra("play_when_ready", false),
            playerState = intent.getIntExtra("player_state", 0),
            playbackSpeed = intent.getFloatExtra("playback_speed", 1f),
            activeQueueItemId = intent.getLongExtra("queue_item_id", -1L),
            queue = HushQueueSnapshot(
                title = intent.getStringExtra("queue_title") ?: "Hush Queue",
                revision = intent.getLongExtra("queue_revision", 0L),
                items = queueItems,
            ),
            sequenceNumber = if (intent.hasExtra("sequence_number")) {
                intent.getLongExtra("sequence_number", -1L)
            } else {
                -1L
            },
            timestampMs = if (intent.hasExtra("timestamp_elapsed_realtime")) {
                intent.getLongExtra("timestamp_elapsed_realtime", 0L)
            } else {
                SystemClock.elapsedRealtime()
            },
        )

        Log.d(TAG, "Playback snapshot: $title - $artist (sequence=${snapshot.sequenceNumber})")

        listener?.onPlaybackSnapshot(snapshot)
            ?: Log.w(TAG, "Listener not attached, metadata dropped")
    }
}
