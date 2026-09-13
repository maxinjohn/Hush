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
        // Android 15 blocks startForegroundService() while the app is in the
        // background (the shim is in RCVR state when Waze sends ACTION_INIT),
        // which made Waze show "Can't connect". A plain startService() is
        // permitted during the broadcast allowlist window; the service promotes
        // itself to a mediaPlayback foreground service immediately in
        // onStartCommand via ensureForeground().
        try {
            context.startService(serviceIntent)
        } catch (error: Exception) {
            Log.e(TAG, "startService failed, falling back to startForegroundService", error)
            try {
                context.startForegroundService(serviceIntent)
            } catch (error2: Exception) {
                Log.e(TAG, "Unable to start Waze integration service", error2)
            }
        }
    }
}