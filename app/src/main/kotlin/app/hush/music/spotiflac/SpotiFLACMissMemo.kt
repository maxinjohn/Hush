/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One remembered miss. */
@Serializable
data class SpotiFLACMissEntry(
    val mediaId: String,
    val recordedAtMs: Long,
    val context: String,
    val retentionMs: Long,
    val reason: String,
)

@Serializable
private data class SpotiFLACMissIndex(
    val version: Int = 1,
    val entries: List<SpotiFLACMissEntry> = emptyList(),
)

/**
 * The pure half of the miss memo: how long a recorded miss stays a valid answer, and
 * when one stops being valid.
 *
 * A provider sweep costs up to the fallback timeout (45s) and ends in the same place
 * every time for a track no provider has. Remembering that result is what stops every
 * replay - and every app start - from paying for it again. The risk in remembering is
 * the opposite one: something changes that would make the track resolvable, and the
 * memo keeps serving a stale "no". [contextFingerprint] is the answer to that - a miss
 * is only reused while the set of things that could serve the track is unchanged.
 */
object SpotiFLACMissPolicy {
    /**
     * How long a definitive "no enabled provider has this track" is trusted.
     *
     * Hours rather than minutes because the answer comes from the providers' own
     * catalogues, which do not change minute to minute; and not indefinitely because a
     * catalogue can gain a track, and re-checking once a day is cheap.
     */
    const val NO_MATCH_RETENTION_MS = 6L * 60 * 60 * 1000L

    /**
     * How long a sweep that never finished is trusted - it timed out, so it proves
     * nothing about the providers. Deliberately much shorter: one slow network must not
     * take a track away from SpotiFLAC for hours.
     */
    const val UNFINISHED_SWEEP_RETENTION_MS = 30L * 60 * 1000L

    /** Upper bound on the memo, so it cannot grow with listening history. */
    const val MAX_ENTRIES = 500

    const val REASON_NO_MATCH = "no-match"
    const val REASON_SWEEP_UNFINISHED = "sweep-unfinished"

    fun retentionFor(reason: String): Long =
        if (reason == REASON_NO_MATCH) NO_MATCH_RETENTION_MS else UNFINISHED_SWEEP_RETENTION_MS

    /**
     * Which answer a finished sweep earns.
     *
     * Only [SpotiFLACSweepOutcome.NO_MATCH] is a statement about the providers, and only
     * it is worth remembering for hours. Everything else - including [RESOLVED], which
     * clears the memo rather than recording one - is treated as a sweep that proved
     * nothing.
     */
    fun reasonFor(outcome: SpotiFLACSweepOutcome): String =
        when (outcome) {
            SpotiFLACSweepOutcome.NO_MATCH -> REASON_NO_MATCH
            else -> REASON_SWEEP_UNFINISHED
        }

    /**
     * Whether a recorded miss still answers a sweep attempted under [attemptContext].
     *
     * Both halves matter: the context has to match exactly (any change to the enabled
     * providers, the quality bucket, the session or the runtime invalidates it), and the
     * retention chosen when it was recorded has to have elapsed.
     */
    fun isStillValid(
        recordedAtMs: Long,
        recordedContext: String,
        attemptContext: String,
        nowMs: Long,
        recordedRetentionMs: Long,
    ): Boolean {
        if (recordedContext != attemptContext) return false
        // A clock that moved backwards must not turn a miss into a permanent answer.
        if (recordedAtMs > nowMs) return false
        return nowMs - recordedAtMs <= recordedRetentionMs.coerceAtLeast(1L)
    }

    /**
     * Everything that decides whether a sweep could succeed, as a comparable string.
     *
     * The enabled source ids come first because they are the thing that usually changes:
     * adding or re-enabling a provider is exactly the situation where a remembered miss
     * should be thrown away. Their order is irrelevant because a sweep tries all of them
     * either way, so the list is sorted rather than compared as saved.
     */
    fun contextFingerprint(
        enabledSourceIds: List<String>,
        qualityBucket: String,
        sessionActive: Boolean,
        runtimeAvailable: Boolean,
        usableSourceIds: List<String> = enabledSourceIds,
    ): String {
        val sources =
            enabledSourceIds
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .joinToString(",")
        // Which of those sources can actually download right now. Without this, a miss
        // recorded while every gateway source was waiting for a Cloudflare grant looked
        // identical to one recorded with them verified - so the memo kept answering
        // "already failed" and skipped the sweep for the full retention window, even
        // though the very reason it failed had just been fixed. Verifying a source is
        // exactly the kind of change that must throw remembered misses away.
        val usable =
            usableSourceIds
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .joinToString(",")
        return buildString {
            append("sources=").append(sources)
            append("|usable=").append(usable)
            append("|quality=").append(qualityBucket.trim().lowercase(Locale.US))
            append("|session=").append(if (sessionActive) 1 else 0)
            append("|runtime=").append(if (runtimeAvailable) 1 else 0)
        }
    }

    /**
     * Which entries to drop to stay within [maxEntries].
     *
     * Entries that can no longer be consulted go first - they are dead weight - and only
     * then the oldest ones, so a burst of misses cannot evict an entry that is still
     * doing its job.
     */
    fun entriesToDrop(
        entries: List<SpotiFLACMissEntry>,
        attemptContext: String,
        nowMs: Long,
        maxEntries: Int = MAX_ENTRIES,
    ): List<String> {
        val overflow = entries.size - maxEntries.coerceAtLeast(0)
        if (overflow <= 0) return emptyList()
        val spent =
            entries
                .filter {
                    !isStillValid(
                        recordedAtMs = it.recordedAtMs,
                        recordedContext = it.context,
                        attemptContext = attemptContext,
                        nowMs = nowMs,
                        recordedRetentionMs = it.retentionMs,
                    )
                }.map { it.mediaId }
        if (spent.size >= overflow) return spent
        val oldest =
            entries
                .filterNot { it.mediaId in spent }
                .sortedBy { it.recordedAtMs }
                .map { it.mediaId }
        return spent + oldest.take(overflow - spent.size)
    }
}

/**
 * Remembers which tracks SpotiFLAC has already failed to resolve, across restarts.
 *
 * An in-memory memo only helped within one session: after a relaunch - which is exactly
 * when a replay is most likely - the provider sweep ran again from scratch and the user
 * waited out the fallback timeout to reach the same answer. Persisting it is what makes
 * the second play of such a track as fast as the first one was slow.
 *
 * It is a memo of an *attempt*, not a claim about the catalogue, so it is invalidated
 * whenever the attempt would be made differently. See [SpotiFLACMissPolicy].
 */
@Singleton
class SpotiFLACMissMemo
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private companion object {
            const val INDEX_FILE_NAME = "miss_memo.json"
            const val INDEX_DIR = "spotiflac"
        }

        private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; prettyPrint = false }
        private val indexFile = File(context.filesDir, "$INDEX_DIR/$INDEX_FILE_NAME")

        private val entries = HashMap<String, SpotiFLACMissEntry>()

        @Volatile
        private var loaded = false

        /**
         * The remembered miss for [mediaId] under [context], or null when there is none
         * that can still be trusted.
         */
        fun entry(
            mediaId: String,
            context: String,
            nowMs: Long = System.currentTimeMillis(),
        ): SpotiFLACMissEntry? {
            if (mediaId.isBlank()) return null
            ensureLoaded()
            val entry = synchronized(this) { entries[mediaId] } ?: return null
            val valid =
                SpotiFLACMissPolicy.isStillValid(
                    recordedAtMs = entry.recordedAtMs,
                    recordedContext = entry.context,
                    attemptContext = context,
                    nowMs = nowMs,
                    recordedRetentionMs = entry.retentionMs,
                )
            if (valid) return entry
            // Expired or invalidated: drop it so it is never consulted again, and so the
            // file does not accumulate answers that are no longer true.
            remove(mediaId)
            return null
        }

        /** Whether a sweep for [mediaId] can be skipped. */
        fun isMissed(
            mediaId: String,
            context: String,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean = entry(mediaId, context, nowMs) != null

        /**
         * Records that a sweep found nothing. [reason] decides how long the answer is
         * trusted; see [SpotiFLACMissPolicy].
         */
        fun record(
            mediaId: String,
            context: String,
            reason: String,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            if (mediaId.isBlank()) return
            ensureLoaded()
            val retention = SpotiFLACMissPolicy.retentionFor(reason)
            synchronized(this) {
                entries[mediaId] =
                    SpotiFLACMissEntry(
                        mediaId = mediaId,
                        recordedAtMs = nowMs,
                        context = context,
                        retentionMs = retention,
                        reason = reason,
                    )
                dropOverflowLocked(context, nowMs)
            }
            persist()
            SpotiFLACDiag.log("miss memo record mediaId=$mediaId reason=$reason retentionMs=$retention")
        }

        /** Forgets one track, used as soon as a sweep actually succeeds. */
        fun remove(mediaId: String) {
            if (mediaId.isBlank()) return
            ensureLoaded()
            val removed = synchronized(this) { entries.remove(mediaId) } ?: return
            persist()
            SpotiFLACDiag.log("miss memo cleared mediaId=${removed.mediaId}")
        }

        /**
         * Forgets everything. Called when the reason a sweep failed may have gone away -
         * a re-authentication, a new provider install - rather than on a timer.
         */
        fun clearAll() {
            ensureLoaded()
            val hadAny = synchronized(this) {
                if (entries.isEmpty()) return
                entries.clear()
                true
            }
            if (!hadAny) return
            persist()
            SpotiFLACDiag.log("miss memo cleared (all)")
        }

        fun size(): Int {
            ensureLoaded()
            return synchronized(this) { entries.size }
        }

        private fun dropOverflowLocked(
            context: String,
            nowMs: Long,
        ) {
            if (entries.size <= SpotiFLACMissPolicy.MAX_ENTRIES) return
            val drop =
                SpotiFLACMissPolicy.entriesToDrop(
                    entries = entries.values.toList(),
                    attemptContext = context,
                    nowMs = nowMs,
                )
            drop.forEach { entries.remove(it) }
        }

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                runCatching {
                    if (indexFile.isFile) {
                        val index = json.decodeFromString<SpotiFLACMissIndex>(indexFile.readText())
                        index.entries.forEach { entry ->
                            if (entry.mediaId.isNotBlank()) entries[entry.mediaId] = entry
                        }
                    }
                }.onFailure {
                    SpotiFLACDiag.log("miss memo load failed: ${it.message}")
                    entries.clear()
                }
                loaded = true
            }
        }

        private fun persist() {
            runCatching {
                val snapshot = synchronized(this) { SpotiFLACMissIndex(entries = entries.values.toList()) }
                indexFile.parentFile?.mkdirs()
                val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
                temp.writeText(json.encodeToString(snapshot))
                if (!temp.renameTo(indexFile)) {
                    indexFile.writeText(temp.readText())
                    temp.delete()
                }
            }.onFailure { SpotiFLACDiag.log("miss memo persist failed: ${it.message}") }
        }
    }
