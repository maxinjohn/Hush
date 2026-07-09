package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "MediaButtonReceiver: action=$action")

        if (intent.hasExtra(Intent.EXTRA_KEY_EVENT)) {
            val keyEvent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        Log.d(TAG, "Media play/pause pressed")
                        sendCommand(context, "play_pause")
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        Log.d(TAG, "Media play pressed")
                        sendCommand(context, "play")
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        Log.d(TAG, "Media pause pressed")
                        sendCommand(context, "pause")
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        Log.d(TAG, "Media next pressed")
                        sendCommand(context, "next")
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        Log.d(TAG, "Media previous pressed")
                        sendCommand(context, "previous")
                    }
                }
            }
        }

        when (action) {
            "app.hush.music.waze.ACTION_PAUSE" -> sendCommand(context, "pause")
            "app.hush.music.waze.ACTION_PLAY" -> sendCommand(context, "play")
            "app.hush.music.waze.ACTION_SKIP" -> sendCommand(context, "next")
            "app.hush.music.waze.ACTION_LIKE" -> sendCommand(context, "like")
            "app.hush.music.waze.ACTION_DOWNLOAD" -> sendCommand(context, "download")
        }
    }

    private fun sendCommand(context: Context, command: String) {
        try {
            val intent = Intent("app.hush.music.WAZE_COMMAND").apply {
                putExtra("command", command)
                setPackage("app.hush.music")
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: $command", e)
        }
    }

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }
}
