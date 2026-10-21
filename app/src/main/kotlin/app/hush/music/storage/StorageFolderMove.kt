/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hush.music.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * The copy rules for carrying a storage folder's contents to a new one.
 *
 * Kept out of the worker so they can be tested directly: the one thing they must never do
 * is leave a file wearing a song's real name before its bytes are all there. The downloads
 * folder is read by name to decide whether a track is already downloaded, so a half-copied
 * file under the real name would be adopted and served as the song.
 */
object StorageFolderMove {
    private const val TAG = "StorageFolderMove"

    /** Marks a copy that is still being written. Never a name a download can be matched on. */
    const val PART_SUFFIX = ".hush-part"

    private const val COPY_BUFFER_BYTES = 128 * 1024

    /**
     * The tag every folder-move job carries, so the app can find the move it has running.
     *
     * The jobs are enqueued as unique work named after the folder being drained, which the
     * settings screen does not know at the time it wants to ask - so the tag is how it asks
     * WorkManager for "whatever move is going on" instead.
     */
    const val WORK_TAG = "storage_folder_move"

    /**
     * A second tag naming the folder kind, because a job that has not started yet has reported no
     * progress to read it from: without this, a move waiting for its slot would be invisible on the
     * screen that is supposed to show it.
     */
    fun workTagFor(kind: StorageFolderKind): String = "${WORK_TAG}_${kind.name}"

    /** The folder kind a job's tags name, or null when they do not name one. */
    fun kindForTags(tags: Set<String>): StorageFolderKind? =
        StorageFolderKind.entries.firstOrNull { kind -> workTagFor(kind) in tags }

    /**
     * How many files are left to carry over, after [handled] of [total].
     *
     * Never negative: a file can appear in the folder while the move is running - the user is not
     * locked out of it - and a count that went below zero would be a worse answer than the honest
     * "none left".
     */
    fun filesRemaining(
        total: Int,
        handled: Int,
    ): Int = (total - handled).coerceAtLeast(0)

    /**
     * Writes [source] to [target], or returns false having written nothing.
     *
     * Streamed into `<name>.hush-part` and then renamed, so the real name only ever
     * describes a whole file. A copy whose length does not match what was read is deleted
     * rather than promoted: the source is still the authority, and a short file under the
     * target's name is worse than no file, because nothing can tell it apart from a good one.
     */
    fun copyFile(
        source: File,
        target: File,
    ): Boolean {
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        runCatching { part.delete() }
        val copied =
            runCatching {
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    part.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
                part.length()
            }.getOrElse { error ->
                Timber.tag(TAG).w(error, "could not copy %s", source.name)
                runCatching { part.delete() }
                return false
            }
        val expected = source.length()
        if (copied != expected) {
            Timber.tag(TAG).w(
                "copy of %s is %d bytes, not %d; discarding it",
                source.name,
                copied,
                expected,
            )
            runCatching { part.delete() }
            return false
        }
        if (!part.renameTo(target)) {
            Timber.tag(TAG).w("could not give %s its name", target.name)
            runCatching { part.delete() }
            return false
        }
        return true
    }

    /** The files under [root], by path relative to it, in a stable order. */
    fun relativeFiles(root: File): List<String> =
        runCatching {
            root.walkTopDown()
                .filter { file -> file.isFile }
                .map { file -> file.relativeTo(root).path }
                .sorted()
                .toList()
        }.getOrDefault(emptyList())

    /**
     * Whether [root]'s contents can be listed at all.
     *
     * "It holds no files" and "this app may not look inside it" are the same empty list to
     * [relativeFiles], and the difference matters: one is a finished move and the other is a
     * folder this app has no access to, where quietly reporting success loses the user's files
     * in the folder they moved away from.
     */
    fun isReadable(root: File): Boolean =
        runCatching { root.isDirectory && root.list() != null }.getOrDefault(false)

    /** How many bytes [root] holds, for a progress percentage. */
    fun byteCount(
        root: File,
        relatives: List<String>,
    ): Long = relatives.sumOf { relative -> File(root, relative).length().coerceAtLeast(0L) }

    /**
     * Removes the directories [root] holds that are now empty.
     *
     * [root] itself is kept: it is a folder the user picked and may hold files of their
     * own, and deleting something a person chose is not this move's business.
     */
    fun pruneEmptyDirectories(root: File) {
        val directories =
            runCatching {
                root.walkTopDown().filter { file -> file.isDirectory && file != root }.toList()
            }.getOrDefault(emptyList())
        // Deepest first, so a parent left empty by its children is pruned in the same pass.
        directories.sortedByDescending { directory -> directory.path.length }.forEach { directory ->
            runCatching { if (directory.listFiles()?.isEmpty() == true) directory.delete() }
        }
    }
}

/** Enqueues the move of a folder's contents to whichever folder is current now. */
object StorageFolderMoveScheduler {
    private const val TAG = "StorageFolderMove"

    fun enqueue(
        context: Context,
        kind: StorageFolderKind,
        source: File,
    ) {
        val request =
            OneTimeWorkRequestBuilder<StorageFolderMoveWorker>()
                .setInputData(
                    workDataOf(
                        StorageFolderMoveWorker.KEY_KIND to kind.name,
                        StorageFolderMoveWorker.KEY_SOURCE to source.absolutePath,
                    ),
                ).addTag(StorageFolderMove.WORK_TAG)
                .addTag(StorageFolderMove.workTagFor(kind))
                .build()
        // One job per source folder, so changing the location twice drains both old folders
        // into the one that is current. KEEP rather than REPLACE: a job already running is
        // making progress on real files, and restarting it would only start over.
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(kind, source),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }.onFailure { error ->
            // Not scheduled is not a failure the user has to answer for: the files stay in
            // the folder they were in, which is where they still play from.
            Timber.tag(TAG).w(error, "could not schedule the move out of %s", source)
        }
    }

    private fun workName(
        kind: StorageFolderKind,
        source: File,
    ): String = "storage_folder_move_${kind.name}_${source.absolutePath.hashCode()}"
}

/**
 * Carries the files a storage folder already held into the folder that has just replaced it.
 *
 * It runs as work rather than as part of saving the setting because saving the setting has
 * to be immediate: a library is tens of gigabytes, and a pick that only takes effect once
 * every byte has been copied is a pick that looks like nothing happened - and is undone by
 * the app being closed, which is exactly when the user, seeing no progress, closes it.
 *
 * Nothing is lost if this never runs or is killed part way. A file is deleted from the old
 * folder only after its copy is whole in the new one, and a download resolves by name in the
 * current folder first and then by the path it was written to - so a song that has not been
 * carried over yet still plays from where it is.
 */
class StorageFolderMoveWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    companion object {
        private const val TAG = "StorageFolderMove"

        const val KEY_KIND = "kind"
        const val KEY_SOURCE = "source"

        // Progress, published as WorkManager progress data rather than only drawn into the
        // notification: the storage screen reads it back to show the same move in the app, with
        // the number of files still to carry over. The keys are a contract with
        // [ObserveStorageFolderMoveUseCase], which is the only reader.
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_PROGRESS_FILES_REMAINING = "progress_files_remaining"
        const val KEY_PROGRESS_FILES_TOTAL = "progress_files_total"

        private const val CHANNEL_ID = "storage_folder_move"
        private const val NOTIFICATION_ID = 8421

        /** Reported at most this often, so a large move does not post thousands of updates. */
        private const val PROGRESS_STEP_PERCENT = 1

        /** Attempts before the leftovers are left alone rather than retried forever. */
        private const val MAX_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        val kind =
            inputData.getString(KEY_KIND)
                ?.let { name -> runCatching { StorageFolderKind.valueOf(name) }.getOrNull() }
                ?: return give("the job does not say which folder kind it belongs to")
        val source =
            inputData.getString(KEY_SOURCE)?.takeIf(String::isNotBlank)?.let(::File)
                ?: return give("the job does not say which folder to move out of")
        if (!source.isDirectory) return give("there is no folder at $source any more")
        val target =
            withContext(Dispatchers.IO) {
                // The configured folder, not the resolved one: a folder this app cannot enter yet
                // still resolves to the fallback, and moving the library into the fallback because
                // the real folder is temporarily closed would be the opposite of what was asked.
                StorageLocationRepository.moveTargetDirectory(applicationContext, kind)
            }
        // Nothing to do when the source *is* the current folder: that is the common case of
        // the user picking the folder that was already in use.
        if (runCatching { source.canonicalPath == target.canonicalPath }.getOrDefault(true)) {
            Timber.tag(TAG).i("nothing to move: %s is already the current %s folder", source, kind)
            return Result.success()
        }
        Timber.tag(TAG).i("moving the %s folder: %s -> %s", kind, source, target)
        return withContext(Dispatchers.IO) { move(kind, source, target) }.let { failed ->
            when {
                !failed -> Result.success()
                runAttemptCount + 1 < MAX_ATTEMPTS -> {
                    Timber.tag(TAG).i("retrying the move out of %s (attempt %d)", source, runAttemptCount + 1)
                    Result.retry()
                }
                else -> {
                    // Said out loud, not only logged: files that could not be carried over are
                    // still where they were, and that is something the user can act on - usually
                    // by letting Hush into the folder.
                    notifyIncomplete(part = source.name)
                    give("gave up after $MAX_ATTEMPTS attempts; what is left stays in $source")
                }
            }
        }
    }

    private fun notifyIncomplete(part: String) {
        ensureChannel()
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(applicationContext.getString(R.string.storage_move_incomplete_title))
                .setContentText(applicationContext.getString(R.string.storage_move_incomplete_text, part))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID + 1, notification)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS was refused. A notification the system will not show is not a
            // reason to fail a finished job; the outcome is in the log either way.
            Timber.tag(TAG).w(error, "could not post the incomplete-move notification")
        }
    }

    /**
     * Ends the job without a retry, having said why.
     *
     * Every one of these used to be a bare `Result.success()`, which is why an empty move looked
     * like a finished one: the log said SUCCESS, the files had not gone anywhere, and nothing
     * anywhere recorded the difference.
     */
    private fun give(reason: String): Result {
        Timber.tag(TAG).i("not moving anything: %s", reason)
        return Result.success()
    }

    /** @return true when at least one file could not be carried over. */
    private suspend fun move(
        kind: StorageFolderKind,
        source: File,
        target: File,
    ): Boolean {
        val relatives = StorageFolderMove.relativeFiles(source)
        if (relatives.isEmpty()) {
            // An unreadable folder lists as an empty one. Reported as a failure so the job is
            // retried - a shared-storage folder this app may not enter yet starts working the
            // moment the user grants All files access, and the retry is what notices.
            if (!StorageFolderMove.isReadable(source)) {
                Timber.tag(TAG).w("cannot read %s, so nothing can be carried out of it yet", source)
                return true
            }
            StorageFolderMove.pruneEmptyDirectories(source)
            return false
        }
        val totalBytes = StorageFolderMove.byteCount(source, relatives)
        val part = source.name
        var movedBytes = 0L
        var lastPercent = -1
        var failures = 0

        publishProgress(
            kind = kind,
            percent = 0,
            filesRemaining = relatives.size,
            filesTotal = relatives.size,
        )
        try {
            setForeground(foregroundInfo(percent = 0))
        } catch (error: Throwable) {
            // A notification the system refuses is not a reason to stop moving files.
            Timber.tag(TAG).w(error, "could not post the move notification")
        }

        relatives.forEachIndexed { index, relative ->
            currentCoroutineContext().ensureActive()
            // Re-asked for every file, because the answer can change while this runs: the
            // user is not locked out of picking a third folder. Continuing into a target the
            // setting no longer names would leave the library in one place and the app
            // pointing at another, and would delete the old copies on the way.
            if (!isStillTheCurrentFolder(kind, target)) {
                Timber.tag(TAG).i("stopping the move out of %s: the folder changed again", part)
                return failures > 0
            }
            val sourceFile = File(source, relative)
            val targetFile = File(target, relative)
            val length = sourceFile.length().coerceAtLeast(0L)
            // A target file of the same length is this file, from a run that was
            // interrupted between the copy and the delete: it is not copied again, only
            // the old copy is dropped. That is the rule the folder is read by everywhere
            // else too - a name inside a folder, at the byte count it was written at.
            val carried =
                if (targetFile.isFile && targetFile.length() == length) {
                    true
                } else {
                    StorageFolderMove.copyFile(sourceFile, targetFile)
                }
            if (carried) {
                runCatching { sourceFile.delete() }
            } else {
                failures++
            }
            movedBytes += length
            val percent =
                if (totalBytes <= 0L) 100 else ((movedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt()
            if (percent - lastPercent >= PROGRESS_STEP_PERCENT) {
                lastPercent = percent
                publishProgress(
                    kind = kind,
                    percent = percent,
                    // One file further along, so the count the screen shows and the percentage
                    // come from the same pass over the same list.
                    filesRemaining = StorageFolderMove.filesRemaining(relatives.size, index + 1),
                    filesTotal = relatives.size,
                )
                runCatching { setForeground(foregroundInfo(percent = percent)) }
            }
        }

        if (failures == 0) {
            StorageFolderMove.pruneEmptyDirectories(source)
        }
        Timber.tag(TAG).i(
            "moved %s: %d of %d files, %d failed",
            part,
            relatives.size - failures,
            relatives.size,
            failures,
        )
        return failures > 0
    }

    private fun isStillTheCurrentFolder(
        kind: StorageFolderKind,
        target: File,
    ): Boolean =
        runCatching {
            StorageLocationRepository.moveTargetDirectory(applicationContext, kind).canonicalPath == target.canonicalPath
        }.getOrDefault(false)

    /**
     * Says how far the move has got, to the log and to whoever is showing it.
     *
     * WorkManager persists this, so the storage screen can read it from the job itself: the
     * notification is not the only place the move exists. Failure to publish is logged and
     * ignored - a move that cannot report its progress is still a move that has to finish.
     */
    private suspend fun publishProgress(
        kind: StorageFolderKind,
        percent: Int,
        filesRemaining: Int,
        filesTotal: Int,
    ) {
        Timber.tag(TAG).d(
            "moving the %s folder: %d%%, %d of %d files left",
            kind.name,
            percent,
            filesRemaining,
            filesTotal,
        )
        runCatching {
            setProgress(
                workDataOf(
                    KEY_KIND to kind.name,
                    KEY_PROGRESS_PERCENT to percent,
                    KEY_PROGRESS_FILES_REMAINING to filesRemaining,
                    KEY_PROGRESS_FILES_TOTAL to filesTotal,
                ),
            )
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "could not publish the move's progress")
        }
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        ensureChannel()
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(applicationContext.getString(R.string.storage_move_notification_title))
                .setContentText(applicationContext.getString(R.string.storage_move_notification_text, percent))
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.storage_move_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.storage_move_channel_desc)
                setShowBadge(false)
            },
        )
    }
}

/**
 * A folder move WorkManager is holding, as the storage screen shows it.
 *
 * [filesTotal] is zero until the job has counted the folder, which is what the screen uses to say
 * "starting" rather than "0 of 0 files left".
 */
data class StorageFolderMoveProgress(
    /** The job's own id, so the move on screen is the move that gets cancelled. */
    val workId: UUID,
    val kind: StorageFolderKind,
    val percent: Int,
    val filesRemaining: Int,
    val filesTotal: Int,
)

/**
 * The folder move the app has running, if any.
 *
 * Read from WorkManager rather than kept in memory, because the move outlives the screen and the
 * process that scheduled it: the app is restarted seconds after the folder is changed, and a move
 * that is only remembered in the process that asked for it would be invisible in the one that
 * arrives. It is the same source the notification comes from, so the two cannot disagree.
 */
class ObserveStorageFolderMoveUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        operator fun invoke(): Flow<StorageFolderMoveProgress?> =
            WorkManager
                .getInstance(context)
                .getWorkInfosByTagFlow(StorageFolderMove.WORK_TAG)
                .map { infos -> infos.runningMove() }
                .distinctUntilChanged()
    }

/**
 * Stops the move that is on screen.
 *
 * Nothing is lost by stopping: a file is only deleted from the old folder once its copy is whole,
 * so cancelling leaves the rest where they are - and they still play from there, which is the same
 * rule the move itself relies on when it is interrupted. The job is cancelled by id rather than by
 * tag so that the move the user pressed Cancel on is the one that stops, even if a second move is
 * queued behind it.
 */
class CancelStorageFolderMoveUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        operator fun invoke(workId: UUID) {
            runCatching { WorkManager.getInstance(context).cancelWorkById(workId) }
                .onFailure { error -> Timber.tag("StorageFolderMove").w(error, "could not cancel the move") }
        }
    }

/**
 * The move worth showing out of everything carrying the tag.
 *
 * A running job wins over a queued one, because progress is the part that changes; when nothing is
 * running, the queued job is still shown, since it is the move the user has just asked for and
 * waiting to say so would look like nothing happened.
 */
private fun List<WorkInfo>.runningMove(): StorageFolderMoveProgress? {
    filter { info -> info.state == WorkInfo.State.RUNNING }
        .firstNotNullOfOrNull { info -> info.toProgress() }
        ?.let { running -> return running }
    return filter { info -> info.state == WorkInfo.State.ENQUEUED }
        .firstNotNullOfOrNull { info -> info.toProgress() }
}

/**
 * The move [this] describes, or null when it is not a move to report.
 *
 * The kind comes from the progress when the job has published any, and from the tag when it has
 * not: a job that has been enqueued but not started has no progress data yet, and that is exactly
 * the moment the user is most likely to be looking at the screen.
 */
private fun WorkInfo.toProgress(): StorageFolderMoveProgress? {
    val kind =
        progress
            .getString(StorageFolderMoveWorker.KEY_KIND)
            ?.let { name -> runCatching { StorageFolderKind.valueOf(name) }.getOrNull() }
            ?: StorageFolderMove.kindForTags(tags)
            ?: return null
    return StorageFolderMoveProgress(
        workId = id,
        kind = kind,
        percent = progress.getInt(StorageFolderMoveWorker.KEY_PROGRESS_PERCENT, 0).coerceIn(0, 100),
        filesRemaining =
            progress.getInt(StorageFolderMoveWorker.KEY_PROGRESS_FILES_REMAINING, 0).coerceAtLeast(0),
        filesTotal = progress.getInt(StorageFolderMoveWorker.KEY_PROGRESS_FILES_TOTAL, 0).coerceAtLeast(0),
    )
}
