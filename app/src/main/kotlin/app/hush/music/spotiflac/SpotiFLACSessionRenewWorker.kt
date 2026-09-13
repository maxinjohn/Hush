package app.hush.music.spotiflac

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Renews SpotiFLAC extension sessions in the background so a session that is
 * still valid never silently lapses into a manual Cloudflare verification.
 *
 * The interval is well under [SpotiFLACSessionRenewer.RENEW_WINDOW_SECONDS] of a
 * typical session lifetime, so a single successful run is enough; runs that find
 * nothing due exit immediately without a network call.
 */
class SpotiFLACSessionRenewWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching {
            SpotiFLACSessionRenewer.renewAll(applicationContext, reason = "background")
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.success() },
        )

    companion object {
        private const val PERIODIC_WORK_NAME = "spotiflac_session_renew"
        private const val ONE_SHOT_WORK_NAME = "spotiflac_session_renew_now"

        /**
         * Keeps a periodic renewer registered. Uses KEEP so a session that is
         * already scheduled is never pushed out by a later app start.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SpotiFLACSessionRenewWorker>(
                3,
                TimeUnit.HOURS,
                30,
                TimeUnit.MINUTES,
            ).setConstraints(constraints).build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        /** One immediate attempt, e.g. at app start or after a verification. */
        fun renewNow(context: Context) {
            val constraints = Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SpotiFLACSessionRenewWorker>()
                .setConstraints(constraints)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_SHOT_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
}
