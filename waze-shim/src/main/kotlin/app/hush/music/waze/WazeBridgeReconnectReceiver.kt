package app.hush.music.waze

import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WazeBridgeReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, WazeIntegrationService::class.java).apply {
            action = WazeIntegrationService.ACTION_RECONNECT
        }
        // Same Android 15 background-FGS restriction as WazeInitReceiver: a
        // plain startService() is allowed from the broadcast context and the
        // service promotes itself to foreground in onStartCommand.
        try {
            context.startService(serviceIntent)
        } catch (error: Exception) {
            Log.w(TAG, "startService failed, falling back to startForegroundService", error)
            try {
                context.startForegroundService(serviceIntent)
            } catch (error2: Exception) {
                Log.e(TAG, "Unable to restart Waze integration service", error2)
            }
        }
    }

    companion object {
        private const val TAG = "WazeReconnectReceiver"
    }
}