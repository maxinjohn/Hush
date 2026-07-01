/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import app.hush.music.BuildConfig
import app.hush.music.constants.AutoBackupFrequency
import app.hush.music.db.InternalDatabase
import app.hush.music.extensions.div
import app.hush.music.extensions.zipOutputStream
import app.hush.music.viewmodels.BackupRestoreViewModel
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry

object AutoBackupHelper {
    private const val WEEKLY_WORK_NAME = "weekly_auto_backup"
    private const val PUBLIC_BACKUP_FOLDER = "Hush"

    private fun mirrorBackupToAppDir(
        context: Context,
        source: File,
        fileName: String,
    ) {
        val dir = File(context.getExternalFilesDir(null), "backups")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val target = File(dir, fileName)
        source.copyTo(target, overwrite = true)
    }

    fun getBackupDir(context: Context): File {
        val dir =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                File(context.getExternalFilesDir(null), "backups")
            } else {
                val downloadsDir =
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                File(downloadsDir, PUBLIC_BACKUP_FOLDER)
            }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun performBackup(
        context: Context,
        backupType: String,
    ): Boolean {
        val database = InternalDatabase.newInstance(context)
        try {
            val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            val timestamp = LocalDateTime.now().format(formatter)

            val fileName =
                if (backupType == "before_update") {
                    "auto_backup_before_update_${BuildConfig.VERSION_NAME}_$timestamp.backup"
                } else {
                    "auto_backup_${backupType}_$timestamp.backup"
                }

            val tempFile = File(context.cacheDir, fileName)

            FileOutputStream(tempFile).use { fos ->
                fos.buffered().zipOutputStream().use { outputStream ->
                    val settingsFile = context.filesDir / "datastore" / BackupRestoreViewModel.SETTINGS_FILENAME
                    if (settingsFile.exists()) {
                        settingsFile.inputStream().buffered().use { inputStream ->
                            outputStream.putNextEntry(ZipEntry(BackupRestoreViewModel.SETTINGS_FILENAME))
                            inputStream.copyTo(outputStream)
                        }
                    }

                    runBlocking(Dispatchers.IO) {
                        database.checkpoint()
                    }

                    val dbPath = context.getDatabasePath(InternalDatabase.DB_NAME)
                    if (dbPath.exists()) {
                        FileInputStream(dbPath).use { inputStream ->
                            outputStream.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/$PUBLIC_BACKUP_FOLDER")
                    }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    try {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            tempFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    throw java.io.IOException("Failed to create MediaStore entry in Downloads")
                }
                mirrorBackupToAppDir(context, tempFile, fileName)
            } else {
                val downloadsDir =
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                val publicDir = File(downloadsDir, PUBLIC_BACKUP_FOLDER)
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                val targetFile = File(publicDir, fileName)
                tempFile.copyTo(targetFile, overwrite = true)
            }

            if (tempFile.exists()) {
                tempFile.delete()
            }

            cleanUpOldBackups(context, backupType)
            Timber.tag("AutoBackup").d("Automatic backup completed successfully.")
            return true
        } catch (e: Exception) {
            reportException(e)
            Timber.tag("AutoBackup").e(e, "Automatic backup failed")
            return false
        } finally {
            database.close()
        }
    }

    private fun cleanUpOldBackups(
        context: Context,
        backupType: String,
    ) {
        val backups =
            getAutoBackups(context).filter { file ->
                file.name.startsWith("auto_backup_${backupType}_")
            }

        if (backups.size > 5) {
            for (i in 5 until backups.size) {
                val file = backups[i]
                Timber.tag("AutoBackup").d("Deleting old backup: %s", file.name)
                deleteBackup(context, file)
            }
        }
    }

    fun getAutoBackups(context: Context): List<File> {
        val backupsList = mutableListOf<File>()

        try {
            val appDir = File(context.getExternalFilesDir(null), "backups")
            if (appDir.exists()) {
                val files =
                    appDir.listFiles { file ->
                        file.isFile && file.name.startsWith("auto_backup_") && file.name.endsWith(".backup")
                    }
                if (files != null) {
                    backupsList.addAll(files)
                }
            }
        } catch (e: Exception) {
            Timber.tag("AutoBackup").e(e, "Error reading backups from app dir")
        }

        if (backupsList.isNotEmpty()) {
            return backupsList.sortedByDescending { it.lastModified() }
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val projection =
                    arrayOf(
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.DATA,
                    )
                val selection =
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?"
                val selectionArgs =
                    arrayOf(
                        "Download/$PUBLIC_BACKUP_FOLDER/%",
                        "Download/$PUBLIC_BACKUP_FOLDER",
                    )

                context.contentResolver
                    .query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        null,
                    )?.use { cursor ->
                        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATA)
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameColumn)
                            val path = cursor.getString(dataColumn)
                            if (name.startsWith("auto_backup_") && name.endsWith(".backup") && path != null) {
                                val file = File(path)
                                if (backupsList.none { it.name == file.name }) {
                                    backupsList.add(file)
                                }
                            }
                        }
                    }
            } else {
                val downloadsDir =
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    )
                val publicDir = File(downloadsDir, PUBLIC_BACKUP_FOLDER)
                if (publicDir.exists()) {
                    val files =
                        publicDir.listFiles { file ->
                            file.isFile && file.name.startsWith("auto_backup_") && file.name.endsWith(".backup")
                        }
                    if (files != null) {
                        for (file in files) {
                            if (backupsList.none { it.name == file.name }) {
                                backupsList.add(file)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("AutoBackup").e(e, "Error reading backups from public dir")
        }

        return backupsList.sortedByDescending { it.lastModified() }
    }

    fun deleteBackup(
        context: Context,
        file: File,
    ): Boolean =
        try {
            var deleted = false
            if (file.exists()) {
                deleted = file.delete()
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val selection =
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND (" +
                        "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? OR " +
                        "${MediaStore.Downloads.RELATIVE_PATH} = ?)"
                val selectionArgs =
                    arrayOf(
                        file.name,
                        "Download/$PUBLIC_BACKUP_FOLDER/%",
                        "Download/$PUBLIC_BACKUP_FOLDER",
                    )
                val deletedRows =
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        selection,
                        selectionArgs,
                    )
                deleted = deleted || deletedRows > 0
            } else if (file.exists()) {
                deleted = file.delete() || deleted
            }
            deleted
        } catch (e: Exception) {
            reportException(e)
            Timber.tag("AutoBackup").e(e, "Failed to delete backup file")
            false
        }

    private const val SCHEDULED_WORK_NAME = "scheduled_auto_backup"

    fun calculateInitialDelayMillis(
        hour: Int,
        minute: Int,
        dayOfWeek: Int? = null,
    ): Long {
        val now = LocalDateTime.now()
        var target =
            now
                .withHour(hour.coerceIn(0, 23))
                .withMinute(minute.coerceIn(0, 59))
                .withSecond(0)
                .withNano(0)
        if (dayOfWeek != null) {
            val normalizedDay = dayOfWeek.coerceIn(1, 7)
            while (target.dayOfWeek.value != normalizedDay || !target.isAfter(now)) {
                target = target.plusDays(1)
            }
        } else if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return java.time.Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }

    fun updateScheduledBackupWork(
        context: Context,
        enabled: Boolean,
        frequency: AutoBackupFrequency,
        hour: Int = 2,
        minute: Int = 0,
        dayOfWeek: Int = 1,
    ) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(SCHEDULED_WORK_NAME)
        workManager.cancelUniqueWork(WEEKLY_WORK_NAME)

        if (!enabled || frequency == AutoBackupFrequency.OFF) {
            Timber.tag("AutoBackup").d("Scheduled backup disabled")
            return
        }

        val constraints =
            Constraints
                .Builder()
                .setRequiresBatteryNotLow(true)
                .build()

        val initialDelayMillis =
            when (frequency) {
                AutoBackupFrequency.DAILY ->
                    calculateInitialDelayMillis(hour = hour, minute = minute)
                AutoBackupFrequency.WEEKLY ->
                    calculateInitialDelayMillis(hour = hour, minute = minute, dayOfWeek = dayOfWeek)
                AutoBackupFrequency.OFF -> return
            }

        val repeatInterval =
            when (frequency) {
                AutoBackupFrequency.DAILY -> 1L to TimeUnit.DAYS
                AutoBackupFrequency.WEEKLY -> 7L to TimeUnit.DAYS
                AutoBackupFrequency.OFF -> return
            }

        Timber.tag("AutoBackup").d(
            "Enqueuing %s backup worker (initial delay %d ms)",
            frequency.name,
            initialDelayMillis,
        )

        val autoBackupRequest =
            PeriodicWorkRequestBuilder<AutoBackupWorker>(repeatInterval.first, repeatInterval.second)
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(SCHEDULED_WORK_NAME)
                .build()

        workManager.enqueueUniquePeriodicWork(
            SCHEDULED_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            autoBackupRequest,
        )
    }

    @Deprecated("Use updateScheduledBackupWork", ReplaceWith("updateScheduledBackupWork(context, enabled, frequency)"))
    fun updateWeeklyBackupWork(
        context: Context,
        enabled: Boolean,
    ) {
        updateScheduledBackupWork(
            context = context,
            enabled = enabled,
            frequency = if (enabled) AutoBackupFrequency.WEEKLY else AutoBackupFrequency.OFF,
        )
    }
}
