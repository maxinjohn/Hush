package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WazeBridgeReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(
            Intent(context, WazeIntegrationService::class.java).apply {
                action = WazeIntegrationService.ACTION_RECONNECT
            },
        )
    }
}
