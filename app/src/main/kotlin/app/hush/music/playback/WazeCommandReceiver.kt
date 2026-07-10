package app.hush.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import app.hush.music.extensions.currentMetadata

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
            when (command) {
                "play" -> svc.player.play()
                "pause" -> svc.player.pause()
                "stop" -> svc.player.pause()
                "play_pause" -> if (svc.player.isPlaying) svc.player.pause() else svc.player.play()
                "next" -> {
                    if (svc.player.mediaItemCount > 0) {
                        svc.player.seekToNext()
                        svc.player.prepare()
                        if (!svc.player.playWhenReady) {
                            svc.publishWazePausedTrackChange()
                        }
                    } else {
                        svc.player.play()
                    }
                }
                "previous" -> {
                    if (svc.player.mediaItemCount > 0) {
                        svc.player.seekToPrevious()
                        svc.player.prepare()
                        if (!svc.player.playWhenReady) {
                            svc.publishWazePausedTrackChange()
                        }
                    } else {
                        svc.player.play()
                    }
                }
                "seek" -> {
                    val pos = intent.getLongExtra("position", 0L)
                    svc.player.seekTo(pos)
                }
                "search" -> {
                    val query = intent.getStringExtra("query") ?: return
                    Log.d(TAG, "Search query: $query")
                }
                "like" -> svc.toggleLike()
                "download" -> svc.toggleDownload()
                "shuffle" -> svc.toggleShuffleMode()
                "repeat" -> svc.toggleRepeatMode()
                else -> Log.w(TAG, "Unknown command: $command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
        }
    }

    private fun MusicService.toggleShuffleMode() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    private fun MusicService.toggleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ONE
            androidx.media3.common.Player.REPEAT_MODE_ONE -> androidx.media3.common.Player.REPEAT_MODE_ALL
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
    }
}
