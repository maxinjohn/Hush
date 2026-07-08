package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WazeInitReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WazeInitReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        val token = intent.getStringExtra("token")
        Log.d(TAG, "  token=${if (token != null) "present" else "missing"}")

        val serviceIntent = Intent(context, WazeIntegrationService::class.java).apply {
            action = intent.action
            token?.let { putExtra("token", it) }
        }
        context.startForegroundService(serviceIntent)
    }
}
