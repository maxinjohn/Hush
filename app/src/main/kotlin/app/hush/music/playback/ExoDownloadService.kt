/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.media3.common.C
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import app.hush.music.R
import javax.inject.Inject

@AndroidEntryPoint
class ExoDownloadService :
    DownloadService(
        NOTIFICATION_ID,
        1000L,
        CHANNEL_ID,
        R.string.downloading,
        0,
    ) {
    @Inject
    lateinit var downloadUtil: DownloadUtil

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Every download request is routed to DownloadUtil instead of Media3's
        // DownloadManager, because the manager can only store bytes as 5 MiB cache
        // fragments named after a numeric uid (`15.10485760....v3.exo`). A download is
        // supposed to be one file the user owns, named for the track and carrying the
        // extension of the container the source actually served, so the manager is not
        // allowed to see these requests at all. Removal and pause/resume are intercepted
        // for the same reason: the file has to be deleted, and a paused download has to
        // stop transferring rather than merely be marked paused.
        when (intent?.action) {
            DownloadService.ACTION_ADD_DOWNLOAD -> {
                val request =
                    intent.getParcelableExtra(
                        DownloadService.KEY_DOWNLOAD_REQUEST,
                        DownloadRequest::class.java,
                    )
                if (request != null) {
                    downloadUtil.requestFileDownload(request)
                    // Consumed here. Media3 still gets a neutral intent so the service's own
                    // lifecycle bookkeeping runs - it just never receives the request.
                    return super.onStartCommand(
                        Intent(this, ExoDownloadService::class.java),
                        flags,
                        startId,
                    )
                }
            }

            DownloadService.ACTION_REMOVE_DOWNLOAD -> {
                intent.getStringExtra(DownloadService.KEY_CONTENT_ID)?.let(downloadUtil::removeDownload)
            }

            DownloadService.ACTION_REMOVE_ALL_DOWNLOADS -> downloadUtil.removeAllDownloads()

            DownloadService.ACTION_SET_STOP_REASON -> {
                val contentId = intent.getStringExtra(DownloadService.KEY_CONTENT_ID)
                val stopReason =
                    intent.getIntExtra(DownloadService.KEY_STOP_REASON, Download.STOP_REASON_NONE)
                if (contentId != null) {
                    if (stopReason == Download.STOP_REASON_NONE) {
                        downloadUtil.resumeDownload(contentId)
                    } else {
                        downloadUtil.pauseDownload(contentId)
                    }
                }
            }

            REMOVE_ALL_PENDING_DOWNLOADS -> {
                // The notification's cancel action stops in-flight file downloads too, not
                // just the ones Media3 is running.
                downloadUtil.removeAllDownloads()
                downloadManager.currentDownloads.forEach { download ->
                    downloadManager.removeDownload(download.request.id)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getDownloadManager() = downloadUtil.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val activeDownloads = downloads.filter { it.state != Download.STATE_REMOVING }
        val totalPercentage =
            activeDownloads
                .sumOf { download ->
                    if (download.getPercentDownloaded() != C.PERCENTAGE_UNSET.toFloat()) {
                        download.getPercentDownloaded().toDouble()
                    } else {
                        0.0
                    }
                }.toInt()
        val hasKnownProgress = activeDownloads.any { it.getPercentDownloaded() != C.PERCENTAGE_UNSET.toFloat() }
        val contentText =
            if (downloads.size == 1) {
                Util.fromUtf8Bytes(downloads[0].request.data)
            } else {
                resources.getQuantityString(R.plurals.n_song, downloads.size, downloads.size)
            }
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(getString(R.string.downloading))
            .setContentText(contentText)
            .setProgress(
                100 * activeDownloads.size,
                totalPercentage,
                !hasKnownProgress && activeDownloads.isNotEmpty(),
            ).setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action
                    .Builder(
                        Icon.createWithResource(this, R.drawable.close),
                        getString(android.R.string.cancel),
                        PendingIntent.getService(
                            this,
                            0,
                            Intent(this, ExoDownloadService::class.java).setAction(REMOVE_ALL_PENDING_DOWNLOADS),
                            PendingIntent.FLAG_IMMUTABLE,
                        ),
                    ).build(),
            ).build()
    }

    /**
     * This helper will outlive the lifespan of a single instance of [ExoDownloadService]
     */
    class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int,
    ) : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (download.state == Download.STATE_FAILED) {
                val notification =
                    notificationHelper.buildDownloadFailedNotification(
                        context,
                        R.drawable.error,
                        null,
                        Util.fromUtf8Bytes(download.request.data),
                    )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"
    }
}
