/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import android.content.Context
import app.hush.music.spotiflac.SpotiFLACDiag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which engine's bytes are stored for a media id.
 *
 * A *record*, not a guess: Hush resolves the download's URI itself (see
 * [DownloadUtil]'s data-source resolver), so at the moment a download is created it knows
 * exactly which engine answered.
 */
enum class StoredBytesOrigin {
    SPOTIFLAC,
    YOUTUBE,

    /** Nothing recorded - an older download, or bytes written by some other path. */
    UNKNOWN,
}

@Serializable
private data class DownloadOriginEntry(
    val mediaId: String,
    val origin: String,
)

@Serializable
private data class DownloadOriginIndex(
    val version: Int = 1,
    val entries: List<DownloadOriginEntry> = emptyList(),
)

/**
 * Remembers which engine produced the bytes stored for a download.
 *
 * Hush cannot read this back out of Media3. `CacheDataSource` only records a *redirected*
 * URI, and only when the URI it finally opened differs from the one it was asked for
 * (see its `open()`: `setRedirectedUri(mutations, redirected ? actualUri : null)`). Hush
 * hands the download's data source an already-resolved URI, so the two are always equal
 * and no origin is ever written. Reading origin out of Media3 is therefore not merely
 * unreliable - for this path it can never work, and
 * [PlaybackEngineOrder.storedBytesDecision] would refuse every download whenever
 * YouTube is switched off, including the SpotiFLAC-sourced ones that are supposed to
 * stay playable offline.
 *
 * So Hush keeps its own record. It is written where the URI is chosen and read where the
 * bytes are served, which are the only two places that need to agree.
 *
 * The record is deliberately keyed by media id *and kept when a download is removed*:
 * see [forget] for why removal is the caller's decision rather than this store's.
 */
@Singleton
class DownloadOriginStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private companion object {
            const val INDEX_FILE_NAME = "download_origins.json"
            const val MAX_ENTRIES = 2000
        }

        private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; prettyPrint = false }
        private val indexFile = File(context.filesDir, INDEX_FILE_NAME)

        private val entries = HashMap<String, StoredBytesOrigin>()

        @Volatile
        private var loaded = false

        /** The recorded origin for [mediaId], or [StoredBytesOrigin.UNKNOWN]. */
        fun originOf(mediaId: String): StoredBytesOrigin {
            if (mediaId.isBlank()) return StoredBytesOrigin.UNKNOWN
            ensureLoaded()
            return synchronized(this) { entries[mediaId] } ?: StoredBytesOrigin.UNKNOWN
        }

        /** Whether the bytes stored for [mediaId] are known to have come from SpotiFLAC. */
        fun isSpotiFLACSourced(mediaId: String): Boolean =
            originOf(mediaId) == StoredBytesOrigin.SPOTIFLAC

        /**
         * Records the engine that produced [mediaId]'s bytes.
         *
         * Repeated calls with the same value are free: the download data source's resolver
         * runs on every open, so this must not write the file each time.
         */
        fun record(
            mediaId: String,
            origin: StoredBytesOrigin,
        ) {
            if (mediaId.isBlank() || origin == StoredBytesOrigin.UNKNOWN) return
            ensureLoaded()
            val changed =
                synchronized(this) {
                    if (entries[mediaId] == origin) {
                        false
                    } else {
                        entries[mediaId] = origin
                        dropOverflowLocked()
                        true
                    }
                }
            if (!changed) return
            persist()
            SpotiFLACDiag.log("download origin: mediaId=$mediaId origin=$origin")
        }

        /**
         * Drops the record for [mediaId].
         *
         * Called when a download is removed. Keeping a stale record would mislabel
         * whatever the next download of the same song stores, and a stale
         * `SPOTIFLAC` record is the dangerous direction: it would authorise bytes that a
         * YouTube fetch wrote to be served with YouTube switched off.
         */
        fun forget(mediaId: String) {
            if (mediaId.isBlank()) return
            ensureLoaded()
            val had = synchronized(this) { entries.remove(mediaId) != null }
            if (!had) return
            persist()
            SpotiFLACDiag.log("download origin cleared: mediaId=$mediaId")
        }

        fun size(): Int {
            ensureLoaded()
            return synchronized(this) { entries.size }
        }

        private fun dropOverflowLocked() {
            if (entries.size <= MAX_ENTRIES) return
            // No timestamps to sort by: insertion order is the only signal, and for an
            // overflow this large any bound is fine as long as it is bounded.
            val excess = entries.size - MAX_ENTRIES
            entries.keys.take(excess).forEach { entries.remove(it) }
        }

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                runCatching {
                    if (indexFile.isFile) {
                        val index = json.decodeFromString<DownloadOriginIndex>(indexFile.readText())
                        index.entries.forEach { entry ->
                            val origin =
                                runCatching { StoredBytesOrigin.valueOf(entry.origin) }.getOrNull()
                            if (entry.mediaId.isNotBlank() && origin != null) {
                                entries[entry.mediaId] = origin
                            }
                        }
                    }
                }.onFailure {
                    SpotiFLACDiag.log("download origin load failed: ${it.message}")
                    entries.clear()
                }
                loaded = true
            }
        }

        private fun persist() {
            runCatching {
                val snapshot =
                    synchronized(this) {
                        DownloadOriginIndex(
                            entries = entries.map { DownloadOriginEntry(it.key, it.value.name) },
                        )
                    }
                indexFile.parentFile?.mkdirs()
                val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
                temp.writeText(json.encodeToString(snapshot))
                if (!temp.renameTo(indexFile)) {
                    indexFile.writeText(temp.readText())
                    temp.delete()
                }
            }.onFailure { SpotiFLACDiag.log("download origin persist failed: ${it.message}") }
        }
    }
