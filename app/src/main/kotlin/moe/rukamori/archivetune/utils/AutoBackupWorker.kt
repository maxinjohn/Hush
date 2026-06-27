/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import moe.rukamori.archivetune.constants.AutoBackupEnabledKey
import moe.rukamori.archivetune.constants.EnableWeeklyAutoBackupKey

class AutoBackupWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val autoBackupEnabled = context.dataStore[AutoBackupEnabledKey] ?: true
        val weeklyBackupEnabled = context.dataStore[EnableWeeklyAutoBackupKey] ?: false

        if (!autoBackupEnabled || !weeklyBackupEnabled) {
            return Result.success()
        }

        val success = AutoBackupHelper.performBackup(context, "weekly")
        return if (success) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
