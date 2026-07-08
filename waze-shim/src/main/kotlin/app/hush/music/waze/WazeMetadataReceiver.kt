package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "app.hush.music.WAZE_METADATA_UPDATE") return

        val title = intent.getStringExtra("title") ?: return
        val artist = intent.getStringExtra("artist") ?: ""
        val album = intent.getStringExtra("album") ?: ""
        val duration = intent.getLongExtra("duration", 0L)
        val position = intent.getLongExtra("position", 0L)
        val state = intent.getIntExtra("state", 0)
        val artworkUrl = intent.getStringExtra("artwork_url")

        Log.d(TAG, "Metadata update: $title - $artist (state=$state)")

        listener?.updateMetadata(title, artist, album, duration, position, state, artworkUrl)
            ?: Log.w(TAG, "Listener not attached, metadata dropped")
    }
}
