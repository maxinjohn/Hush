/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hush.music.MainActivity
import app.hush.music.R
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When to ask a source that refused again, and how long to keep asking.
 *
 * A refusal is a statement about a moment, not about a session. Measured against
 * `tido.tido.tido.dpdns.org`, every data route answered `403 {"error":"Unavailable"}` for hours while
 * the host itself was up (its `manifest.json` answered 200 and its own `/status` reported all five
 * hifi-api instances healthy) - so the source was not gone, its upstream was. A client that answers
 * that by dropping the source for ten minutes and then silently re-asking it on a track the user is
 * waiting for has the worst of both: the recovery is invisible, and the cost lands on a song.
 *
 * This is the missing half. Once a source is left out, it is asked again on a schedule of its own,
 * with nothing else happening - no track, no sweep, no user - and the moment it answers it is put
 * back and the user is told. The first probe is early because a refusal is often a moment (a
 * restarted service, a proxy blip); the spacing then doubles so an outage that lasts all evening
 * costs a handful of one-row searches an hour rather than a poll every few seconds.
 *
 * Deliberately pure: no clock, no coroutine, no runtime. The wait is returned rather than slept, so
 * the schedule is pinned by tests instead of by a device that happened to be attached.
 */
object SpotiFLACAddonRecoveryPolicy {

    /**
     * How long after a refusal the first probe goes out.
     *
     * Short on purpose: the case this is built for looked permanent and was not, and the difference
     * between learning that in half a minute and in ten is whether the next track plays.
     */
    const val FIRST_PROBE_MS = 30_000L

    /**
     * The longest gap between probes.
     *
     * Five minutes, because the refusal record itself lasts ten: a source asked only at the end of
     * its cooldown would be back in the walk before the app ever knew it was answering, and the
     * first thing the user would see is a track that fails and then works.
     */
    const val MAX_PROBE_MS = 5 * 60_000L

    /** The wait before probe number [attempt], counting from zero. 30s, 60s, 120s, 240s, 300s… */
    fun nextProbeMs(attempt: Int): Long {
        if (attempt <= 0) return FIRST_PROBE_MS
        // Doubling from the first probe, capped. Capped in long arithmetic, because an attempt
        // counter that somehow ran away must not overflow into a negative wait.
        var wait = FIRST_PROBE_MS
        repeat(min(attempt, 8)) {
            wait = (wait * 2).coerceAtMost(MAX_PROBE_MS)
        }
        return wait.coerceIn(FIRST_PROBE_MS, MAX_PROBE_MS)
    }
}

/**
 * Watches one refused source until it answers, or until it is no longer refused.
 *
 * The loop is here rather than in the bridge so the whole rule - probe, wait, give up when the
 * record disappears - can be tested without a runtime, a network or a device. The caller owns what
 * "still refused" means, what a probe costs, and what happens when it lands; this owns only the
 * sequence, and the promise that a probe is never sent to a source that is no longer refused.
 *
 * Returning false is the ordinary end of a watch: the source came back into the walk by itself (its
 * cooldown lapsed and a sweep asked it), was switched off, or was cleared by its own Test. None of
 * those is a failure, and none of them deserves a notification - the user either just fixed it or is
 * about to hear it work.
 */
object SpotiFLACAddonRecovery {

    /**
     * Runs until [probe] answers.
     *
     * @param stillRefused asked before every probe; a source that is no longer refused is dropped
     *   instead of being asked, so a watch can never resurrect a source the user cleared.
     * @param probe one cheap question to the source; true when it answered.
     * @param onRecovered called once, only on a real answer.
     * @param sleep the wait, injected so tests need no virtual clock.
     * @return true when the source answered.
     */
    suspend fun watch(
        stillRefused: () -> Boolean,
        probe: suspend () -> Boolean,
        onRecovered: () -> Unit,
        onProbe: (attempt: Int, answered: Boolean) -> Unit = { _, _ -> },
        sleep: suspend (Long) -> Unit = { delay(it) },
        maxAttempts: Int = Int.MAX_VALUE,
    ): Boolean {
        var attempt = 0
        while (attempt < maxAttempts) {
            sleep(SpotiFLACAddonRecoveryPolicy.nextProbeMs(attempt))
            attempt++
            if (!stillRefused()) return false
            val answered =
                try {
                    probe()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            onProbe(attempt, answered)
            if (answered) {
                onRecovered()
                return true
            }
        }
        return false
    }

    private val _ticker = MutableStateFlow(0)

    /**
     * Incremented every time a source comes back.
     *
     * The Audio Sources rows read their note from the bridge, which only changes when something asks
     * it to; without this the row would say "Skipped for 3min" while playback has been using the
     * source for a while. Same device as [SpotiFLAutoVerifier.verifiedTicker], for the same reason:
     * a state change that happens off-screen still has to reach the screen.
     */
    val ticker: StateFlow<Int> = _ticker.asStateFlow()

    internal fun noteRecovered() {
        _ticker.value += 1
    }
}

/**
 * "That source is answering again", said where the user is.
 *
 * Two surfaces because the wait is minutes long and the user may be anywhere: a banner for someone
 * looking at Hush, and a notification for someone in another app whose next track was going to be
 * the one that skipped. Both name the source, because "1 source is back" does not answer the only
 * question the user has - *which* one.
 *
 * Silent about the failure it follows: the row in Audio Sources already carried the refusal's own
 * words, and repeating them here would turn good news into a second report of the outage.
 */
object SpotiFLACAddonRecoveryNotifier {

    private const val CHANNEL_ID = "spotiflac_sources"

    /** One notification per source, kept apart from the verification offers' range. */
    private const val NOTIFICATION_ID_BASE = 8_300

    private fun notificationId(sourceId: String): Int =
        NOTIFICATION_ID_BASE + sourceId.hashCode()

    /** The words, kept here so the banner and the notification cannot drift. */
    fun text(displayName: String): String = "$displayName is answering again"

    fun notify(
        context: Context,
        sourceId: String,
        displayName: String,
    ) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        runCatching {
            val appContext = context.applicationContext
            ensureChannel(appContext)
            val open = PendingIntent.getActivity(
                appContext,
                notificationId(id),
                Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val name = displayName.trim().ifEmpty { id }
            val notification =
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(text(name))
                    .setContentText("It answered again, so it is back in your source order.")
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "$name refused earlier and was skipped so it would not cost a " +
                                "request per song. Hush asked it again in the background and it " +
                                "answered, so it is back in your source order.",
                        ),
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            // Checked here, in the same breath as the call, rather than through a helper: a denied
            // notification is not an error - the banner carries the same news in-app, so the offer
            // is never lost, it just lives somewhere else - and the check has to be visible to the
            // reader who is looking at the call that needs the permission.
            val permitted =
                NotificationManagerCompat.from(appContext).areNotificationsEnabled() &&
                    (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(
                                appContext,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        )
            if (!permitted) {
                SpotiFLACDiag.log(
                    "source $id is answering again, banner only: notifications are not permitted",
                )
                return@runCatching
            }
            NotificationManagerCompat.from(appContext).notify(notificationId(id), notification)
            SpotiFLACDiag.log("source $id is answering again (notified)")
        }.onFailure { SpotiFLACDiag.log("recovery notification failed for $id: ${it.message}") }
    }

    /** Removes the notice once the source refuses again, so the two cannot both stand. */
    fun clear(
        context: Context,
        sourceId: String,
    ) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(id))
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Source recovery",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Says when a source that refused starts answering again."
            },
        )
    }
}
