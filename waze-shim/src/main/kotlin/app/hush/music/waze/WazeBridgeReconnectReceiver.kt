package app.hush.music.waze

import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WazeBridgeReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.startForegroundService(
                Intent(context, WazeIntegrationService::class.java).apply {
                    action = WazeIntegrationService.ACTION_RECONNECT
                },
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to restart Waze integration service", error)
        }
    }

    companion object {
        private const val TAG = "WazeReconnectReceiver"
    }
}
