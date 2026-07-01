/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.hush.music.constants.AutoBackupEnabledKey
import app.hush.music.constants.AutoBackupFrequency
import app.hush.music.constants.AutoBackupFrequencyKey
import app.hush.music.constants.EnableWeeklyAutoBackupKey

class AutoBackupWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val autoBackupEnabled = context.dataStore[AutoBackupEnabledKey] ?: true
        val frequency =
            context.dataStore[AutoBackupFrequencyKey]?.let { raw ->
                runCatching { AutoBackupFrequency.valueOf(raw) }.getOrNull()
            } ?: if (context.dataStore[EnableWeeklyAutoBackupKey] == true) {
                AutoBackupFrequency.WEEKLY
            } else {
                AutoBackupFrequency.OFF
            }

        if (!autoBackupEnabled || frequency == AutoBackupFrequency.OFF) {
            return Result.success()
        }

        val backupType =
            when (frequency) {
                AutoBackupFrequency.DAILY -> "daily"
                AutoBackupFrequency.WEEKLY -> "weekly"
                AutoBackupFrequency.OFF -> return Result.success()
            }

        val success = AutoBackupHelper.performBackup(context, backupType)
        return if (success) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
