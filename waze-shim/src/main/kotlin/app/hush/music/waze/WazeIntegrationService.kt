package app.hush.music.waze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat

class WazeIntegrationService : MediaBrowserServiceCompat(), MetadataUpdateListener {

    companion object {
        private const val TAG = "WazeIntegration"
        private const val NOTIFICATION_CHANNEL_ID = "hush_waze_shim"
        private const val NOTIFICATION_ID = 1
        private const val ROOT_ID = "__ROOT__"
        private const val START_APP_PROTOCOL_SERVICE =
            "com.spotify.mobile.appprotocol.action.START_APP_PROTOCOL_SERVICE"
        const val ACTION_INIT = "com.waze.sdk.audio.ACTION_INIT"
        private const val WAZE_PKG = "com.waze"
        private const val WAZE_SDK_SERVICE = "com.waze.sdk.SdkService"
        private const val AIDL_DESCRIPTOR = "com.waze.sdk.ISdkService"
        private const val TRANSACTION_CONNECT = 1
    }

    @Volatile private var mediaSession: MediaSessionCompat? = null
    @Volatile private var metadataReceiver: WazeMetadataReceiver? = null
    private var lastCommandTime = 0L
    private var lastSeekTime = 0L
    @Volatile private var cleanedUp = false
    @Volatile private var isForeground = false
    private val lock = Any()
    private var wazeSdkConnection: ServiceConnection? = null
    private var wazeMessenger: Messenger? = null
    private var notificationTitle = "Hush Music"
    private var notificationSubtitle = "Ready to play"
    private var wazeToken: String? = null
    private var reconnectScheduled = false

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        Log.d(TAG, "handleMessage: what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2}" +
            " replyTo=${msg.replyTo} sendingUid=${msg.sendingUid}")

        if (msg.data != null && !msg.data.isEmpty) {
            Log.d(TAG, "  data keys=${msg.data.keySet()}")
            for (key in msg.data.keySet()) {
                Log.d(TAG, "    $key=${msg.data.get(key)}")
            }
        }
        if (msg.obj != null) {
            Log.d(TAG, "  obj=${msg.obj} (${msg.obj?.javaClass?.simpleName})")
        }

        when (msg.what) {
            0 -> {
                Log.d(TAG, "  -> Init from Waze")
                ensureForeground()
                if (msg.replyTo != null) {
                    try {
                        val reply = Message.obtain(null, 0, 0, 0).apply {
                            data = Bundle().apply {
                                putString("name", "Hush Music")
                                putString("version", "1.0")
                                val fwSession = this@WazeIntegrationService.mediaSession?.mediaSession
                                if (fwSession is android.media.session.MediaSession) {
                                    val fwToken = fwSession.sessionToken
                                    putParcelable("SPOTIFY_APP_REMOTE", fwToken)
                                    putParcelable("session_token", fwToken)
                                }
                            }
                        }
                        msg.replyTo?.send(reply)
                        Log.d(TAG, "  -> Sent ack with sessionToken + info")
                    } catch (e: Exception) {
                        Log.e(TAG, "  -> Failed to send response", e)
                    }
                }
            }
            1 -> Log.d(TAG, "  -> Message what=1 from Waze arg1=${msg.arg1} arg2=${msg.arg2}")
            2 -> Log.d(TAG, "  -> Message what=2 from Waze arg1=${msg.arg1} arg2=${msg.arg2}")
            3 -> Log.d(TAG, "  -> Message what=3 from Waze")
            else -> Log.d(TAG, "  -> Unknown message what=${msg.what}")
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }

        try {
            setupMediaSession()
            Log.d(TAG, "session token: $sessionToken")
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession setup failed", e)
        }

        try {
            metadataReceiver = WazeMetadataReceiver().also { receiver ->
                receiver.attach(this)
                val filter = IntentFilter("app.hush.music.WAZE_METADATA_UPDATE")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
            }
            Log.d(TAG, "Metadata receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register metadata receiver", e)
        }

        Log.d(TAG, "onCreate complete - NOT starting foreground (will start when Waze connects)")
    }

    override fun onBind(intent: Intent?): IBinder? {
        val action = intent?.action
        Log.d(TAG, "onBind: action=$action")

        when (action) {
            START_APP_PROTOCOL_SERVICE -> {
                Log.d(TAG, "  -> Returning Messenger binder for App Protocol")
                ensureForeground()
                startHushMusicService()
                return messenger.binder
            }
            SERVICE_INTERFACE -> {
                Log.d(TAG, "  -> Returning MediaBrowserService binder")
                ensureForeground()
                startHushMusicService()
                return super.onBind(intent)
            }
            ACTION_INIT -> {
                Log.d(TAG, "  -> ACTION_INIT received - returning MediaBrowserService binder")
                ensureForeground()
                startHushMusicService()
                return super.onBind(intent)
            }
            else -> {
                Log.d(TAG, "  -> Unknown action, returning null")
                return null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: intent=$intent action=${intent?.action}")
        if (intent?.action == ACTION_INIT) {
            Log.d(TAG, "  -> ACTION_INIT via onStartCommand")
            ensureForeground()
            startHushMusicService()
            val token = intent.getStringExtra("token")
            if (token != null) {
                bindToWazeSdkService(token)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        Log.d(TAG, "onGetRoot: pkg=$clientPackageName uid=$clientUid hints=$rootHints")
        val extras = Bundle().apply {
            putBoolean(MediaBrowserServiceCompat.BrowserRoot.EXTRA_SUGGESTED, true)
            putString("com.spotify.music.extra.SUGGESTED_TYPE", "navigation")
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaItem>>,
    ) {
        Log.d(TAG, "onLoadChildren: parentId=$parentId")
        val items = mutableListOf<MediaItem>()

        when (parentId) {
            ROOT_ID -> {
                items.add(
                    MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId("play_hush")
                            .setTitle("Hush Music")
                            .setSubtitle("Tap to play")
                            .build(),
                        MediaItem.FLAG_PLAYABLE,
                    )
                )
                result.sendResult(items)
            }
            else -> {
                result.sendResult(items)
            }
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaItem>>,
    ) {
        Log.d(TAG, "onSearch: $query")
        try {
            val intent = Intent("app.hush.music.WAZE_COMMAND").apply {
                putExtra("command", "search")
                putExtra("query", query)
                setPackage("app.hush.music")
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send search command", e)
        }
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureForeground() {
        synchronized(lock) {
            if (isForeground) return
            isForeground = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                Log.d(TAG, "startForeground succeeded (on demand)")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed", e)
                try {
                    startForeground(NOTIFICATION_ID, buildFallbackNotification())
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback startForeground also failed", e2)
                }
            }
        }
    }

    private fun updateNotification() {
        if (!isForeground) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun setupMediaSession() {
        val session = MediaSessionCompat(this, "HushWazeShim").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "onPlay")
                    sendCommandToHush("play")
                }

                override fun onPause() {
                    Log.d(TAG, "onPause")
                    sendCommandToHush("pause")
                }

                override fun onStop() {
                    Log.d(TAG, "onStop")
                    sendCommandToHush("pause")
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "onSkipToNext")
                    sendCommandToHush("next")
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "onSkipToPrevious")
                    sendCommandToHush("previous")
                }

                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "onSeekTo: $pos")
                    sendSeekCommandToHush(pos)
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    Log.d(TAG, "onCustomAction: $action")
                    when (action) {
                        "THUMBS_UP" -> sendCommandToHush("like")
                    }
                }

                override fun onSetRating(rating: RatingCompat?) {
                    if (rating?.rating == 1f) {
                        Log.d(TAG, "onSetRating: thumbs up")
                        sendCommandToHush("like")
                    }
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.d(TAG, "onPlayFromMediaId: $mediaId")
                    sendCommandToHush("play")
                }
            })
            setRatingType(RatingCompat.RATING_HEART)
            isActive = true
        }
        mediaSession = session
        sessionToken = session.sessionToken

        try {
            val mbrIntent = Intent(this, MediaButtonReceiver::class.java).apply {
                action = Intent.ACTION_MEDIA_BUTTON
            }
            val mbrPending = PendingIntent.getBroadcast(
                this, 0, mbrIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            session.setMediaButtonReceiver(mbrPending)
            Log.d(TAG, "MediaButtonReceiver set")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set MediaButtonReceiver", e)
        }

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SET_RATING
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        "THUMBS_UP",
                        "Like",
                        R.drawable.ic_heart,
                    ).apply {
                        setExtras(Bundle().apply {
                            putInt(
                                "androidx.media3.session.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT",
                                3, // CommandButton.ICON_HEART
                            )
                        })
                    }.build()
                )
                .build(),
        )

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Hush Music")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Ready to play")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0L)
                .putRating(MediaMetadataCompat.METADATA_KEY_USER_RATING, RatingCompat.newHeartRating(false))
                .build()
        )

        Log.d(TAG, "MediaSession created (state=PAUSED)")
    }

    override fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        duration: Long,
        position: Long,
        state: Int,
        artworkUrl: String?,
    ) {
        if (cleanedUp) return
        val session = mediaSession ?: return
        val displaySubtitle = if (album.isNotEmpty()) "$artist \u2014 $album" else artist

        try {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putRating(MediaMetadataCompat.METADATA_KEY_USER_RATING, RatingCompat.newHeartRating(false))
                .apply {
                    if (!artworkUrl.isNullOrEmpty()) {
                        putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
                        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUrl)
                    }
                }
                .build()

            val playbackState = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SET_RATING
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        "THUMBS_UP",
                        "Like",
                        R.drawable.ic_heart,
                    ).apply {
                        setExtras(Bundle().apply {
                            putInt(
                                "androidx.media3.session.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT",
                                3, // CommandButton.ICON_HEART
                            )
                        })
                    }.build()
                )
                .setState(state, position, 1f, SystemClock.elapsedRealtime())
                .build()

            session.setMetadata(metadata)
            session.setPlaybackState(playbackState)
            notificationTitle = title
            notificationSubtitle = displaySubtitle
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update metadata", e)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().apply {
                component = ComponentName("app.hush.music", "app.hush.music.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val pauseIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, MediaButtonReceiver::class.java).apply {
                action = "app.hush.music.waze.ACTION_PAUSE"
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val playIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(this, MediaButtonReceiver::class.java).apply {
                action = "app.hush.music.waze.ACTION_PLAY"
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val skipIntent = PendingIntent.getBroadcast(
            this, 3,
            Intent(this, MediaButtonReceiver::class.java).apply {
                action = "app.hush.music.waze.ACTION_SKIP"
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationSubtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_media_play, "Play", playIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", skipIntent)

        try {
            val session = mediaSession
            if (session != null) {
                builder.setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set MediaStyle", e)
        }

        return builder.build()
    }

    private fun buildFallbackNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Hush Music")
            .setContentText("Waze Integration")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Waze Integration",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun sendCommandToHush(command: String) {
        val now = System.currentTimeMillis()
        if (now - lastCommandTime < 300) return
        lastCommandTime = now

        try {
            val intent = Intent("app.hush.music.WAZE_COMMAND").apply {
                putExtra("command", command)
                component = ComponentName("app.hush.music", "app.hush.music.playback.MusicService")
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command to Hush", e)
        }
    }

    private fun startHushMusicService() {
        try {
            val intent = Intent().apply {
                component = ComponentName("app.hush.music", "app.hush.music.playback.MusicService")
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Hush MusicService", e)
        }
    }

    private fun sendSeekCommandToHush(position: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSeekTime < 100) return
        lastSeekTime = now

        try {
            val intent = Intent("app.hush.music.WAZE_COMMAND").apply {
                putExtra("command", "seek")
                putExtra("position", position)
                component = ComponentName("app.hush.music", "app.hush.music.playback.MusicService")
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send seek command to Hush", e)
        }
    }

    private fun bindToWazeSdkService(token: String) {
        if (cleanedUp) return
        wazeToken = token
        Log.d(TAG, "Binding to Waze SdkService with token=present")
        val serviceIntent = Intent().apply {
            component = ComponentName(WAZE_PKG, WAZE_SDK_SERVICE)
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Log.d(TAG, "Connected to Waze SdkService!")
                callConnect(service, token)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.d(TAG, "Disconnected from Waze SdkService")
                wazeSdkConnection = null
                wazeMessenger = null
                scheduleWazeReconnect()
            }

            override fun onBindingDied(name: ComponentName) {
                Log.w(TAG, "Waze SdkService binding died")
                wazeSdkConnection = null
                wazeMessenger = null
                scheduleWazeReconnect()
            }

            override fun onNullBinding(name: ComponentName) {
                Log.w(TAG, "Waze SdkService returned a null binding")
                wazeSdkConnection = null
                wazeMessenger = null
                scheduleWazeReconnect()
            }
        }

        synchronized(lock) {
            wazeSdkConnection?.let {
                try {
                    unbindService(it)
                } catch (_: Exception) {}
            }
            val bound = bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE)
            if (bound) {
                wazeSdkConnection = conn
            } else {
                scheduleWazeReconnect()
            }
            Log.d(TAG, "bindService result=$bound")
        }
    }

    private fun callConnect(binder: IBinder, token: String) {
        try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(AIDL_DESCRIPTOR)
                data.writeString(token)

                val appBundle = Bundle().apply {
                    putString("name", "Hush Music")
                    putString("version", "1.0")
                }
                data.writeInt(1)
                appBundle.writeToParcel(data, 0)

                val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
                    Log.d(TAG, "Waze callback: what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2}")
                    true
                })
                data.writeInt(1)
                replyMessenger.writeToParcel(data, 0)

                val success = binder.transact(TRANSACTION_CONNECT, data, reply, 0)
                reply.readException()
                val wazeMessengerSent = reply.readInt() != 0
                Log.d(TAG, "connect() transact success=$success wazeMessengerSent=$wazeMessengerSent")

                if (wazeMessengerSent) {
                    wazeMessenger = Messenger.CREATOR.createFromParcel(reply)
                    Log.d(TAG, "Got Waze Messenger, connection established!")
                } else {
                    scheduleWazeReconnect()
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to call connect()", e)
            scheduleWazeReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error calling connect()", e)
            scheduleWazeReconnect()
        }
    }

    private fun scheduleWazeReconnect() {
        synchronized(lock) {
            if (cleanedUp || wazeToken == null || reconnectScheduled) return
            reconnectScheduled = true
        }
        Handler(Looper.getMainLooper()).postDelayed({
            val token = synchronized(lock) {
                reconnectScheduled = false
                wazeToken?.takeIf { !cleanedUp }
            }
            if (token != null) {
                Log.d(TAG, "Retrying Waze SdkService connection")
                bindToWazeSdkService(token)
            }
        }, 1000)
    }

    private fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        reconnectScheduled = false
        wazeMessenger = null
        wazeToken = null
        wazeSdkConnection?.let {
            try {
                unbindService(it)
            } catch (_: Exception) {}
        }
        wazeSdkConnection = null
        metadataReceiver?.let {
            it.detach()
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        metadataReceiver = null
        try {
            mediaSession?.release()
        } catch (_: Exception) {}
        mediaSession = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        try {
            stopSelf()
        } catch (_: Exception) {}
    }
}
