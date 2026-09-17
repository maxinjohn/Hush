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
 * The cadence is deliberately much shorter than [SpotiFLACSessionRenewer.RENEW_WINDOW_SECONDS]:
 * a session is renewed only while it is still valid (the runtime refuses to refresh an expired
 * one), so the interval is the number of chances a run gets inside that window. At three hours
 * the period and the window were the *same* length - one deferred run - and WorkManager defers
 * freely under doze - and the session lapsed, which is a manual Cloudflare check for a user who
 * did nothing wrong. An hourly run means three attempts inside the window, and a run that finds
 * nothing due costs a preference read and no network call.
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
         * Keeps a periodic renewer registered at the cadence below the renewal window.
         *
         * The policy is UPDATE rather than KEEP: an install that already registered the old
         * three-hour request keeps that interval under KEEP, so the tighter cadence would reach
         * only fresh installs and every existing user would stay on the schedule that lapses.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SpotiFLACSessionRenewWorker>(
                SpotiFLACSessionRenewer.BACKGROUND_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
                SpotiFLACSessionRenewer.BACKGROUND_FLEX_MINUTES,
                TimeUnit.MINUTES,
            ).setConstraints(constraints).build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
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
