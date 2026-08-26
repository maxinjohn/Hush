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
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WazeIntegrationService : MediaBrowserServiceCompat(), MetadataUpdateListener {

    companion object {
        private const val TAG = "WazeIntegration"
        private const val NOTIFICATION_CHANNEL_ID = "hush_waze_bridge"
        private const val NOTIFICATION_ID = 1
        private const val ROOT_ID = "__ROOT__"
        private const val START_APP_PROTOCOL_SERVICE =
            "com.spotify.mobile.appprotocol.action.START_APP_PROTOCOL_SERVICE"
        const val ACTION_INIT = "com.waze.sdk.audio.ACTION_INIT"
        const val ACTION_RECONNECT = "app.hush.music.waze.ACTION_RECONNECT"
        private const val WAZE_PKG = "com.waze"
        private const val WAZE_SDK_SERVICE = "com.waze.sdk.SdkService"
        private const val AIDL_DESCRIPTOR = "com.waze.sdk.ISdkService"
        private const val TRANSACTION_CONNECT = 1
        private const val PLAYER_STATE_BUFFERING = 2
        private const val PLAYER_STATE_READY = 3
        private const val PLAYER_STATE_ENDED = 4
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_WHAT = 99
    }

    @Volatile private var mediaSession: MediaSessionCompat? = null
    @Volatile private var metadataReceiver: WazeMetadataReceiver? = null
    private val lastCommandTimeByType = mutableMapOf<String, Long>()
    private var lastSeekTime = 0L
    @Volatile private var cleanedUp = false
    @Volatile private var isForeground = false
    private val lock = Any()
    private var wazeSdkConnection: ServiceConnection? = null
    private var wazeMessenger: Messenger? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val wazeSdkConnectionImpl = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Connected to Waze SdkService!")
            synchronized(lock) { reconnectAttempt = 0 }
            val token = this@WazeIntegrationService.wazeToken
            if (token != null) {
                callConnect(service, token)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Disconnected from Waze SdkService")
            wazeSdkConnection = null
            wazeMessenger = null
            stopHeartbeat()
            scheduleWazeReconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            Log.w(TAG, "Waze SdkService binding died")
            wazeSdkConnection = null
            wazeMessenger = null
            stopHeartbeat()
            scheduleWazeReconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "Waze SdkService returned a null binding")
            wazeSdkConnection = null
            wazeMessenger = null
            stopHeartbeat()
            scheduleWazeReconnect()
        }
    }
    private var notificationTitle = "Hush Music"
    private var notificationSubtitle = "Ready to play"
    private var wazeToken: String? = null
    private var reconnectScheduled = false
    private var reconnectAttempt = 0
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var latestSnapshot: HushPlaybackSnapshot? = null
    private var latestSnapshotSequence = -1L
    private var latestSnapshotTimestampMs = Long.MIN_VALUE
    private var pendingPlaybackState: Int? = null
    private var pendingPlaybackStateAtMs = 0L
    private var pendingSeekPositionMs: Long? = null
    private var pendingSeekAtMs = 0L
    private var latestQueueRevision = -1L
    private var lastNotifyChildrenChangedAt = 0L

    /**
     * Content fingerprint of the last queue applied to the MediaSession.
     * The app's revision counter resets when its MusicService restarts, so
     * revision comparison alone silently drops every queue update afterwards.
     * Comparing content as well keeps the Waze queue correct in all cases.
     */
    private var latestQueueFingerprint: String? = null

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
            WazeCommandMapper.WHAT_PLAY_PAUSE,
            WazeCommandMapper.WHAT_NEXT,
            WazeCommandMapper.WHAT_PREVIOUS -> {
                Log.i(TAG, "  -> Message what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2}")
                val cmd = WazeCommandMapper.mapMessageToCommand(msg.what, msg.arg1)
                if (cmd != null) {
                    Log.i(TAG, "  -> Translating what=${msg.what} to command: $cmd")
                    sendCommandToHush(cmd)
                }
            }
            else -> Log.w(TAG, "  -> Unknown message what=${msg.what}")
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
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
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
                sendSyncCommand()
                return messenger.binder
            }
            SERVICE_INTERFACE -> {
                Log.d(TAG, "  -> Returning MediaBrowserService binder")
                ensureForeground()
                startHushMusicService()
                sendSyncCommand()
                return super.onBind(intent)
            }
            ACTION_INIT -> {
                Log.d(TAG, "  -> ACTION_INIT received - returning MediaBrowserService binder")
                ensureForeground()
                startHushMusicService()
                sendSyncCommand()
                return super.onBind(intent)
            }
            else -> {
                // Return MediaBrowserService binder for any unknown action.
                // Waze may connect to Deezer/YT Music with non-standard intent actions;
                // returning null causes "can't connect" errors in Waze's UI.
                Log.d(TAG, "  -> Unknown action '$action' — returning MediaBrowserService binder as fallback")
                ensureForeground()
                startHushMusicService()
                sendSyncCommand()
                return super.onBind(intent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: intent=$intent action=${intent?.action}")
        when (intent?.action) {
            ACTION_INIT -> {
                Log.d(TAG, "  -> ACTION_INIT via onStartCommand")
                synchronized(lock) {
                    reconnectAttempt = 0
                    reconnectScheduled = false
                }
                ensureForeground()
                startHushMusicService()
                sendSyncCommand()
                val token = intent.getStringExtra("token")
                if (token != null) {
                    bindToWazeSdkService(token)
                }
            }
            ACTION_RECONNECT -> {
                Log.d(TAG, "  -> reconnect requested by Hush")
                synchronized(lock) {
                    reconnectAttempt = 0
                    reconnectScheduled = false
                }
                ensureForeground()
                startHushMusicService()
                sendSyncCommand()
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
        Log.i(TAG, "onLoadChildren: parentId=$parentId")
        val items = mutableListOf<MediaItem>()

        when (parentId) {
            ROOT_ID -> {
                // Waze audio panel shows playlist groups at the root.
                // Return one browsable "Hush Queue" entry that contains
                // the actual tracks when drilled into.
                val snapshot = latestSnapshot
                val trackCount = snapshot?.queue?.items?.size ?: 0
                val currentlyPlaying = snapshot?.title?.ifEmpty { null }
                items.add(
                    MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId("hush_queue")
                            .setTitle("Hush Queue")
                            .setSubtitle(
                                if (currentlyPlaying != null) {
                                    "Now playing: $currentlyPlaying • $trackCount tracks"
                                } else {
                                    "$trackCount tracks"
                                }
                            )
                            .apply {
                                if (!snapshot?.artworkUrl.isNullOrEmpty()) {
                                    setIconUri(Uri.parse(snapshot!!.artworkUrl))
                                }
                            }
                            .build(),
                        MediaItem.FLAG_BROWSABLE,
                    )
                )
                result.sendResult(items)
            }
            "hush_queue" -> {
                // Return all queue items as playable tracks.
                val snapshot = latestSnapshot
                val queueItems = snapshot?.queue?.items
                if (!queueItems.isNullOrEmpty()) {
                    Log.d(TAG, "  -> Returning ${queueItems.size} queue items")
                    for (item in queueItems) {
                        items.add(
                            MediaItem(
                                MediaDescriptionCompat.Builder()
                                    .setMediaId(item.trackId)
                                    .setTitle(item.title)
                                    .setSubtitle(item.artist)
                                    .setDescription(item.album)
                                    .apply {
                                        if (!item.artworkUrl.isNullOrEmpty()) {
                                            setIconUri(Uri.parse(item.artworkUrl))
                                        }
                                    }
                                    .build(),
                                MediaItem.FLAG_PLAYABLE,
                            )
                        )
                    }
                }
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
        sendWazeCommandToHush("search", android.os.Bundle().apply {
            putString("query", query)
        })
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
                isForeground = true
                Log.d(TAG, "startForeground succeeded (on demand)")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed", e)
                try {
                    startForeground(NOTIFICATION_ID, buildFallbackNotification())
                    isForeground = true
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

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d(TAG, "onPlay")
            if (sendCommandToHush("play")) {
                publishOptimisticPlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
        }

        override fun onPause() {
            Log.d(TAG, "onPause")
            if (sendCommandToHush("pause")) {
                publishOptimisticPlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
        }

        override fun onStop() {
            Log.d(TAG, "onStop")
            if (sendCommandToHush("pause")) {
                publishOptimisticPlaybackState(PlaybackStateCompat.STATE_PAUSED)
            }
        }

        override fun onSkipToNext() {
            Log.d(TAG, "onSkipToNext")
            sendCommandToHush("next")
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious")
            sendCommandToHush("previous")
        }

        override fun onSkipToQueueItem(id: Long) {
            Log.d(TAG, "onSkipToQueueItem: $id")
            sendQueueItemCommandToHush(id)
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "onSeekTo: $pos")
            if (sendSeekCommandToHush(pos)) {
                publishOptimisticSeek(pos)
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            Log.d(TAG, "onCustomAction: $action")
            when (action) {
                "THUMBS_UP" -> sendCommandToHush("like")
            }
        }

        override fun onSetRating(rating: RatingCompat?) {
            // Waze sends a rated heart for both like and unlike. Hush owns the
            // current state, so treat the interaction as a toggle.
            if (rating?.isRated == true) {
                Log.d(TAG, "onSetRating: toggling like (value=${rating.rating})")
                sendCommandToHush("like")
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "onPlayFromMediaId: $mediaId")
            when (mediaId) {
                null -> {}
                "play_hush", "hush_queue" -> {
                    // Generic play — start or resume playback
                    if (sendCommandToHush("play")) {
                        publishOptimisticPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    }
                }
                else -> {
                    // Find the queue item index for this trackId
                    val snapshot = latestSnapshot
                    val queueItem = snapshot?.queue?.items?.firstOrNull { it.trackId == mediaId }
                    if (queueItem != null) {
                        Log.i(TAG, "  -> Playing queue item ${queueItem.queueItemId}: ${queueItem.title}")
                        sendWazeCommandToHush("skip_to_queue_item", android.os.Bundle().apply {
                            putLong("queue_item_id", queueItem.queueItemId)
                        })
                        // Send play after skip so the song auto-starts
                        serviceScope.launch {
                            delay(200)
                            sendCommandToHush("play")
                        }
                        publishOptimisticPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    } else {
                        Log.w(TAG, "  -> Queue item not found for mediaId=$mediaId, sending generic play")
                        if (sendCommandToHush("play")) {
                            publishOptimisticPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                        }
                    }
                }
            }
        }
    }

    private fun setupMediaSession() {
        val session = MediaSessionCompat(this, "HushWazeBridge")
        session.setCallback(mediaSessionCallback)
        session.setRatingType(RatingCompat.RATING_HEART)
        session.isActive = true
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
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f, SystemClock.elapsedRealtime())
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
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

    override fun onPlaybackSnapshot(snapshot: HushPlaybackSnapshot) {
        if (cleanedUp || isStaleSnapshot(snapshot)) return

        val resolvedState = resolvePlaybackState(snapshot)
        val ignorePending = shouldIgnorePendingSnapshot(snapshot, resolvedState)

        val session = mediaSession ?: return
        latestSnapshot = snapshot
        latestSnapshotSequence = snapshot.sequenceNumber
        latestSnapshotTimestampMs = snapshot.timestampMs

        try {
            val queueFingerprint = buildQueueFingerprint(snapshot.queue)
            val queueChanged =
                snapshot.queue.revision > latestQueueRevision ||
                    queueFingerprint != latestQueueFingerprint
            if (queueChanged) {
                Log.i(
                    TAG,
                    "setQueue: revision=${snapshot.queue.revision} items=${snapshot.queue.items.size} " +
                        "title=${snapshot.queue.title} changed=${queueFingerprint != latestQueueFingerprint}",
                )
                session.setQueue(
                    snapshot.queue.items.map { item ->
                        MediaSessionCompat.QueueItem(
                            MediaDescriptionCompat.Builder()
                                .setMediaId(item.trackId)
                                .setTitle(item.title)
                                .setSubtitle(item.artist)
                                .setDescription(item.album)
                                .apply {
                                    if (!item.artworkUrl.isNullOrEmpty()) {
                                        setIconUri(Uri.parse(item.artworkUrl))
                                    }
                                }
                                .build(),
                            item.queueItemId,
                        )
                    },
                )
                session.setQueueTitle(snapshot.queue.title)
                latestQueueRevision = maxOf(latestQueueRevision, snapshot.queue.revision)
                latestQueueFingerprint = queueFingerprint
                // Tell Waze to re-query onLoadChildren so it shows the updated queue.
                // Without this, Waze caches the old result and only shows stale data.
                // Debounce: at most once per 500ms to avoid spamming Waze.
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotifyChildrenChangedAt > 500L) {
                    lastNotifyChildrenChangedAt = now
                    try {
                        notifyChildrenChanged("hush_queue")
                        notifyChildrenChanged(ROOT_ID)
                        Log.d(TAG, "notifyChildrenChanged: queue updated (${snapshot.queue.items.size} items)")
                    } catch (e: Exception) {
                        Log.w(TAG, "notifyChildrenChanged failed", e)
                    }
                }
                // If queue has only 1 item, request a sync from Hush — the full queue
                // may not have been loaded yet (e.g., playing from search result).
                if (snapshot.queue.items.size <= 1) {
                    Log.d(TAG, "Queue has only 1 item, requesting sync to populate full queue")
                    sendSyncCommand()
                }
            }
            val displaySubtitle = if (snapshot.album.isNotEmpty()) {
                "${snapshot.artist} \u2014 ${snapshot.album}"
            } else {
                snapshot.artist
            }
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, snapshot.trackId)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, snapshot.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, snapshot.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, snapshot.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs.coerceAtLeast(0L))
                    .putRating(MediaMetadataCompat.METADATA_KEY_USER_RATING, RatingCompat.newHeartRating(snapshot.liked))
                    .apply {
                        if (!snapshot.artworkUrl.isNullOrEmpty()) {
                            putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, snapshot.artworkUrl)
                            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, snapshot.artworkUrl)
                        }
                    }
                    .build(),
            )

            // Apply state always; suppress position only if seek pending
            val position = if (ignorePending && pendingSeekPositionMs != null) {
                latestSnapshot?.positionMs ?: snapshot.positionMs
            } else {
                snapshot.positionMs
            }

            session.setPlaybackState(
                buildPlaybackState(
                    state = resolvedState,
                    position = position,
                    speed = playbackSpeedFor(resolvedState, snapshot.playbackSpeed),
                    bufferedPosition = snapshot.bufferedPositionMs,
                    activeQueueItemId = snapshot.activeQueueItemId,
                    updateTimeMs = snapshot.timestampMs,
                ),
            )
            notificationTitle = snapshot.title
            notificationSubtitle = displaySubtitle
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply playback snapshot", e)
        }
    }

    private fun isStaleSnapshot(snapshot: HushPlaybackSnapshot): Boolean {
        if (snapshot.sequenceNumber < 0L && latestSnapshotSequence >= 0L) return true
        return snapshot.timestampMs < latestSnapshotTimestampMs ||
            (
                snapshot.timestampMs == latestSnapshotTimestampMs &&
                    snapshot.sequenceNumber <= latestSnapshotSequence
                )
    }

    /** Stable content identity of a queue: size + track ids, independent of revision numbers. */
    private fun buildQueueFingerprint(queue: HushQueueSnapshot): String =
        buildString {
            append(queue.items.size)
            append(':')
            for (item in queue.items) {
                append(item.trackId)
                append(',')
            }
        }.hashCode().toString(16)

    private fun shouldIgnorePendingSnapshot(
        snapshot: HushPlaybackSnapshot,
        resolvedState: Int,
    ): Boolean {
        // Handle pending playback state echo suppression
        pendingPlaybackState?.let { expectedState ->
            val elapsedMs = snapshot.timestampMs - pendingPlaybackStateAtMs
            val isExpectedState =
                resolvedState == expectedState ||
                    (
                        expectedState == PlaybackStateCompat.STATE_PLAYING &&
                            resolvedState == PlaybackStateCompat.STATE_BUFFERING
                        )
            pendingPlaybackState = null
            if (isExpectedState && elapsedMs < 2000L) return true
        }
        // Handle pending seek: only suppress position update if still far from target,
        // but always apply state/metadata changes
        pendingSeekPositionMs?.let { expectedPosition ->
            val elapsedMs = snapshot.timestampMs - pendingSeekAtMs
            if (kotlin.math.abs(snapshot.positionMs - expectedPosition) <= 1000L || elapsedMs >= 2000L) {
                pendingSeekPositionMs = null
            } else {
                // Position still far from seek target - suppress POSITION only
                // by returning a special marker that the caller should handle
                return true
            }
        }
        return false
    }

    private fun resolvePlaybackState(snapshot: HushPlaybackSnapshot): Int = when {
        snapshot.playerState == PLAYER_STATE_BUFFERING && snapshot.playWhenReady -> PlaybackStateCompat.STATE_BUFFERING
        snapshot.isPlaying -> PlaybackStateCompat.STATE_PLAYING
        snapshot.playerState == PLAYER_STATE_READY && !snapshot.playWhenReady -> PlaybackStateCompat.STATE_PAUSED
        snapshot.playerState == PLAYER_STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
        else -> PlaybackStateCompat.STATE_PAUSED
    }

    private fun playbackSpeedFor(state: Int, sourceSpeed: Float): Float =
        if (state == PlaybackStateCompat.STATE_PLAYING) sourceSpeed.takeIf { it > 0f } ?: 1f else 0f

    private fun publishOptimisticPlaybackState(state: Int) {
        val snapshot = latestSnapshot
        val now = SystemClock.elapsedRealtime()
        pendingPlaybackState = state
        pendingPlaybackStateAtMs = now
        mediaSession?.setPlaybackState(
            buildPlaybackState(
                state = state,
                position = snapshot?.positionMs ?: 0L,
                speed = playbackSpeedFor(state, snapshot?.playbackSpeed ?: 1f),
                bufferedPosition = snapshot?.bufferedPositionMs ?: 0L,
                activeQueueItemId = snapshot?.activeQueueItemId ?: -1L,
                updateTimeMs = now,
            ),
        )
    }

    private fun publishOptimisticSeek(position: Long) {
        val snapshot = latestSnapshot
        val now = SystemClock.elapsedRealtime()
        val resolvedState = snapshot?.let(::resolvePlaybackState) ?: PlaybackStateCompat.STATE_PAUSED
        val targetPosition = position.coerceAtLeast(0L)
        pendingSeekPositionMs = targetPosition
        pendingSeekAtMs = now
        mediaSession?.setPlaybackState(
            buildPlaybackState(
                state = resolvedState,
                position = targetPosition,
                speed = playbackSpeedFor(resolvedState, snapshot?.playbackSpeed ?: 1f),
                bufferedPosition = snapshot?.bufferedPositionMs ?: targetPosition,
                activeQueueItemId = snapshot?.activeQueueItemId ?: -1L,
                updateTimeMs = now,
            ),
        )
    }

    private fun buildPlaybackState(
        state: Int,
        position: Long,
        speed: Float,
        bufferedPosition: Long,
        activeQueueItemId: Long,
        updateTimeMs: Long,
    ): PlaybackStateCompat {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SET_RATING,
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    "THUMBS_UP",
                    if (latestSnapshot?.liked == true) "Unlike" else "Like",
                    R.drawable.ic_heart,
                ).apply {
                    setExtras(Bundle().apply {
                        putInt(
                            "androidx.media3.session.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT",
                            3, // CommandButton.ICON_HEART
                        )
                    })
                }.build(),
            )
            .setBufferedPosition(bufferedPosition.coerceAtLeast(0L))
            .setState(
                state,
                position.coerceAtLeast(0L),
                playbackSpeedFor(state, speed),
                updateTimeMs,
            )

        if (activeQueueItemId >= 0L) {
            playbackState.setActiveQueueItemId(activeQueueItemId)
        }
        return playbackState.build()
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
        val prevIntent = PendingIntent.getBroadcast(
            this, 4,
            Intent(this, MediaButtonReceiver::class.java).apply {
                action = "app.hush.music.waze.ACTION_PREVIOUS"
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
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_media_play, "Play", playIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", skipIntent)

        try {
            val session = mediaSession
            if (session != null) {
                builder.setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(1, 2, 3),
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

    private fun sendCommandToHush(command: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val lastTime = lastCommandTimeByType[command] ?: 0L
        if (now - lastTime < 300) return false
        lastCommandTimeByType[command] = now
        // Periodically prune stale entries (every 50 commands)
        if (lastCommandTimeByType.size > 50) {
            lastCommandTimeByType.entries.removeAll { now - it.value > 5_000L }
        }

        try {
            val intent = Intent("app.hush.music.WAZE_COMMAND").apply {
                putExtra("command", command)
                component = ComponentName("app.hush.music", "app.hush.music.playback.MusicService")
            }
            try {
                startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed for '$command', falling back to broadcast", e)
                // Broadcast fallback: use action-only intent (no component) so it
                // reaches the dynamically registered WazeCommandReceiver in MusicService.
                // An explicit component targeting a Service is invisible to receivers.
                try {
                    val broadcastIntent = Intent("app.hush.music.WAZE_COMMAND").apply {
                        putExtra("command", command)
                        setPackage("app.hush.music")
                    }
                    sendBroadcast(broadcastIntent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Broadcast fallback also failed for '$command'", e2)
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command to Hush", e)
            return false
        }
    }

    private fun sendSyncCommand() {
        // Send a sync request to Hush so it publishes current playback state
        try {
            val intent = Intent("app.hush.music.WAZE_COMMAND").apply {
                putExtra("command", "sync")
                component = ComponentName("app.hush.music", "app.hush.music.playback.MusicService")
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed for sync, falling back to broadcast", e)
            try {
                val broadcastIntent = Intent("app.hush.music.WAZE_COMMAND").apply {
                    putExtra("command", "sync")
                    setPackage("app.hush.music")
                }
                sendBroadcast(broadcastIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Broadcast fallback also failed for sync", e2)
            }
        }
    }

    private fun startHushMusicService() {
        try {
            val intent = Intent().apply {
                component = ComponentName("app.hush.music", "app.hush.music.playback.MusicService")
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed for MusicService start, trying broadcast", e)
            try {
                sendBroadcast(Intent("app.hush.music.WAZE_COMMAND").apply {
                    putExtra("command", "sync")
                    setPackage("app.hush.music")
                })
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start Hush MusicService via broadcast", e2)
            }
        }
    }

    private fun sendWazeCommandToHush(command: String, extras: android.os.Bundle? = null): Boolean {
        return try {
            val intent = Intent("app.hush.music.WAZE_COMMAND").apply {
                putExtra("command", command)
                extras?.let { putExtras(it) }
                component = ComponentName("app.hush.music", "app.hush.music.playback.MusicService")
            }
            try {
                startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed for '$command'", e)
                try {
                    val broadcastIntent = Intent("app.hush.music.WAZE_COMMAND").apply {
                        putExtra("command", command)
                        extras?.let { putExtras(it) }
                        setPackage("app.hush.music")
                    }
                    sendBroadcast(broadcastIntent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Broadcast fallback also failed for '$command'", e2)
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send '$command' to Hush", e)
            false
        }
    }

    private fun sendSeekCommandToHush(position: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekTime < 100) return false
        lastSeekTime = now
        return sendWazeCommandToHush("seek", android.os.Bundle().apply {
            putLong("position", position)
        })
    }

    private fun sendQueueItemCommandToHush(queueItemId: Long): Boolean {
        return sendWazeCommandToHush("skip_to_queue_item", android.os.Bundle().apply {
            putLong("queue_item_id", queueItemId)
        })
    }

    private fun bindToWazeSdkService(token: String) {
        if (cleanedUp) return
        wazeToken = token
        Log.d(TAG, "Binding to Waze SdkService with token=present")
        val serviceIntent = Intent().apply {
            component = ComponentName(WAZE_PKG, WAZE_SDK_SERVICE)
        }

        synchronized(lock) {
            wazeSdkConnection?.let {
                try {
                    unbindService(it)
                } catch (_: Exception) {}
            }
            val bound = bindService(serviceIntent, wazeSdkConnectionImpl, Context.BIND_AUTO_CREATE)
            if (bound) {
                wazeSdkConnection = wazeSdkConnectionImpl
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
                if (!success) {
                    Log.w(TAG, "connect() transact returned false")
                    scheduleWazeReconnect()
                    return
                }
                reply.readException()
                if (reply.dataAvail() < Int.SIZE_BYTES) {
                    Log.w(TAG, "connect() reply too short (${reply.dataAvail()} bytes)")
                    scheduleWazeReconnect()
                    return
                }
                val wazeMessengerSent = reply.readInt() != 0
                Log.d(TAG, "connect() transact success=$success wazeMessengerSent=$wazeMessengerSent")

                if (wazeMessengerSent) {
                    if (reply.dataAvail() > 0) {
                        wazeMessenger = Messenger.CREATOR.createFromParcel(reply)
                        Log.d(TAG, "Got Waze Messenger, connection established!")
                        startHeartbeat()
                    } else {
                        Log.w(TAG, "Waze indicated messenger but reply has no parcel data")
                        scheduleWazeReconnect()
                    }
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
        val attempt = synchronized(lock) { ++reconnectAttempt }
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Exceeded max reconnect attempts ($MAX_RECONNECT_ATTEMPTS), giving up")
            synchronized(lock) { reconnectScheduled = false }
            return
        }
        val delayMs = minOf(
            RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(5)),
            RECONNECT_MAX_DELAY_MS,
        )
        Log.d(TAG, "Scheduling Waze reconnect attempt $attempt in ${delayMs}ms")
        Handler(Looper.getMainLooper()).postDelayed({
            val token = synchronized(lock) {
                reconnectScheduled = false
                wazeToken?.takeIf { !cleanedUp }
            }
            if (token != null) {
                Log.d(TAG, "Retrying Waze SdkService connection (attempt $attempt)")
                bindToWazeSdkService(token)
            }
        }, delayMs)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                val messenger = wazeMessenger ?: continue
                try {
                    val msg = Message.obtain(null, HEARTBEAT_WHAT, 0, 0)
                    messenger.send(msg)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Waze heartbeat failed, scheduling reconnect", e)
                    wazeMessenger = null
                    scheduleWazeReconnect()
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        reconnectScheduled = false
        stopHeartbeat()
        serviceScope.coroutineContext.cancelChildren()
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
