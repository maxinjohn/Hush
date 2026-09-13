package app.hush.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WazeCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WazeCommandReceiver"
    }

    private var service: MusicService? = null

    fun attachService(service: MusicService) {
        this.service = service
    }

    fun detachService() {
        this.service = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "app.hush.music.WAZE_COMMAND") return

        val command = intent.getStringExtra("command") ?: return
        val svc = service ?: run {
            Log.w(TAG, "Service not attached, command ignored: $command")
            return
        }

        Log.d(TAG, "Command: $command")

        try {
            // Route through MusicService's gating logic for cold-start handling, debounce, etc.
            svc.routeWazeCommand(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
        }
    }
}
