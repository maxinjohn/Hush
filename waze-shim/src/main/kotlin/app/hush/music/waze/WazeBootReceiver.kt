package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Keeps the Waze bridge ready after a device reboot. Waze only connects to an
 * audio app that is already running (or that can be started), so an
 * always-ready mediaPlayback foreground service avoids the "Can't connect"
 * error that otherwise appears when Waze sends ACTION_INIT to a cold shim.
 */
class WazeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed - starting Waze integration service")
        val serviceIntent = Intent(context, WazeIntegrationService::class.java).apply {
            action = WazeIntegrationService.ACTION_RECONNECT
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (error: Exception) {
            Log.w(TAG, "startForegroundService on boot failed, trying startService", error)
            try {
                context.startService(serviceIntent)
            } catch (error2: Exception) {
                Log.e(TAG, "Unable to start Waze integration service on boot", error2)
            }
        }
    }

    companion object {
        private const val TAG = "WazeBootReceiver"
    }
}