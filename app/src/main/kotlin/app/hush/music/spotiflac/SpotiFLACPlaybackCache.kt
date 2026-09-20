/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import app.hush.music.constants.MaxSongCacheSizeKey
import app.hush.music.constants.SpotiFLACCacheStreamsKey
import app.hush.music.storage.StorageFolderKind
import app.hush.music.storage.StorageLocationRepository
import app.hush.music.utils.PreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** One cached SpotiFLAC playback file. */
@Serializable
data class SpotiFLACCacheEntry(
    val trackKey: String,
    val filePath: String,
    val extension: String? = null,
    val codec: String? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val sourceId: String? = null,
    val sizeBytes: Long = 0L,
    val lastUsedAtMs: Long = 0L,
    /** Pinned entries are user downloads and are never evicted. */
    val pinned: Boolean = false,
    /**
     * Quality group this file was fetched for (`lossless` / `hires`).
     *
     * Only needed where a file is reached by media id rather than by identity key:
     * that lookup has no quality component to match on, so the stored bucket is what
     * stops a hi-res request from being answered with a lossless copy. Null means an
     * entry written before this field existed.
     */
    val qualityBucket: String? = null,
)

@Serializable
private data class SpotiFLACCacheIndex(
    val version: Int = 1,
    val entries: List<SpotiFLACCacheEntry> = emptyList(),
    val mediaIds: Map<String, String> = emptyMap(),
)

data class SpotiFLACCacheStats(
    val bytes: Long = 0L,
    val fileCount: Int = 0,
    val limitBytes: Long = 0L,
    val enabled: Boolean = true,
) {
    val unlimited: Boolean get() = limitBytes <= 0L
    val usedFraction: Float
        get() = if (limitBytes <= 0L) 0f else (bytes.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * Persistent cache for tracks resolved through SpotiFLAC.
 *
 * The upstream runtime reuses an existing `output_path` instead of downloading it
 * again (see `shouldReuseExistingOutput` / `already_exists`), so playback files
 * are written to a deterministic per-track path. This index sits in front of the
 * runtime entirely: a cache hit never touches the network, which is what makes
 * replaying a track instant and keeps seeks from re-downloading.
 *
 * Keys are derived from the track identity plus a quality bucket, so a lossless
 * file is never served for a hi-res request (and vice versa).
 */
@Singleton
class SpotiFLACPlaybackCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "SpotiFLACCache"
        private const val INDEX_FILE_NAME = "playback_cache_index.json"

        /**
         * Subfolder of the Storage screen's song-cache location that holds these files.
         *
         * These are cached songs, so they belong with the other cached songs rather than in
         * the app's private data directory. Pointing Storage's cache folder at an SD card or a
         * Downloads folder has to move *all* of it, and the size cap and "clear cache" have to
         * see it. A subfolder rather than the folder root because Media3's streaming cache owns
         * the root (it refuses to open on a directory another cache already holds).
         */
        const val CACHE_SUBDIRECTORY_NAME = "spotiflac-playback"

        /**
         * Where these files were written before the folder was wired to Storage.
         *
         * Moved on first use, so a cached song survives the upgrade instead of being
         * resolved (and downloaded) again.
         */
        private const val LEGACY_PLAYBACK_DIR = "spotiflac/playback"

        /** Default cap for streamed playback files. */
        /** Matches the Storage screen's default "Max song cache size". */
        const val DEFAULT_LIMIT_MB = 1024

        /**
         * Maps the Storage screen's "Max song cache size" (MB) to a byte cap:
         * `-1` unlimited and `0` off both mean "evict nothing", so both yield 0.
         */
        fun limitBytesForMegabytes(mb: Int): Long = if (mb <= 0) 0L else mb.toLong() * 1024L * 1024L

        /** 0 means "no automatic eviction". */

        /** How often a cache hit rewrites `lastUsedAt` (avoids write amplification). */
        private const val TOUCH_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: SpotiFLACPlaybackCache? = null

        fun getInstance(): SpotiFLACPlaybackCache? = instance

        /**
         * Quality group for cache keying. A `LOSSLESS` file must not satisfy a
         * `HI_RES_LOSSLESS` request, so requests are split into two buckets.
         */
        fun qualityBucket(quality: String?): String {
            val normalized = quality.orEmpty().uppercase()
            return if (normalized.contains("HI_RES") || normalized.contains("HIRES")) "hires" else "lossless"
        }

        /**
         * Whether a file fetched for [stored] may answer a [requested] quality request.
         *
         * Only relevant where a file is reached by media id: that lookup has no quality
         * component in its key, so the bucket has to be checked explicitly or a hi-res
         * request would be answered with the lossless copy already on disk. `null` means
         * an entry written before the bucket was recorded, which is trusted only for the
         * default (lossless) request - serving that is what the user asked for anyway,
         * while a hi-res request is left to resolve properly.
         */
        fun bucketSatisfies(stored: String?, requested: String): Boolean =
            stored == requested || (stored == null && requested == qualityBucket(null))

        /**
         * Stable cache key: `sha1(identity|qualityBucket)`, truncated to 24 hex chars.
         *
         * Identity prefers the strongest available provider id so the same song
         * reaches one file regardless of which screen resolved it, and falls back to
         * the metadata tuple (what SpotiFLAC itself matches on). Pure, so it can be
         * unit tested and reused for diagnostics.
         */
        fun cacheKeyFor(
            title: String,
            artist: String,
            album: String?,
            durationMs: Long,
            isrc: String?,
            spotifyTrackId: String?,
            quality: String?,
        ): String {
            val identity =
                spotifyTrackId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: isrc?.trim()?.takeIf { it.isNotEmpty() }
                    ?: listOf(
                        title.trim().lowercase(),
                        artist.trim().lowercase(),
                        album.orEmpty().trim().lowercase(),
                        durationMs.toString(),
                    ).joinToString("|")
            return sha1Hex(identity + "|" + qualityBucket(quality)).take(24)
        }

        private fun sha1Hex(value: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; prettyPrint = false }
    private val indexFile = File(context.filesDir, "spotiflac/$INDEX_FILE_NAME")

    private val entries = LinkedHashMap<String, SpotiFLACCacheEntry>()
    private val mediaIdIndex = HashMap<String, String>()

    /**
     * Track keys whose file is being written right now.
     *
     * A re-download writes to the same deterministic path as the entry it replaces, so
     * between the runtime truncating that file and [record] being called the index still
     * describes the *previous*, larger file. A concurrent cache lookup would read the
     * shorter file and - correctly for a finished file - call it truncated and delete it,
     * pulling the file out from under an in-progress download. Being rewritten is not the
     * same statement as being damaged, so those keys are simply not served while it is
     * happening: the caller resolves instead of reading a half-written file.
     */
    private val writingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var loaded = false

    /** Whether the one-time move out of the app's private data directory has been tried. */
    @Volatile
    private var legacyMigrationAttempted = false

    init {
        instance = this
    }

    // ---------------------------------------------------------------- settings

    /**
     * The Storage screen's "Max song cache size", in MB: `-1` unlimited, `0`
     * disabled, otherwise a cap. SpotiFLAC playback files are cached songs like
     * any other, so they follow that single setting rather than keeping a second
     * size control of their own.
     */
    fun storageSongCacheMegabytes(): Int =
        PreferenceStore.get(MaxSongCacheSizeKey) ?: DEFAULT_LIMIT_MB

    /** True when the folder the user manages in Storage is switched off entirely. */
    fun storageSongCacheDisabled(): Boolean = storageSongCacheMegabytes() == 0

    /** User preference: cache streamed tracks so replays are instant. */
    fun cacheStreamingEnabled(): Boolean =
        (PreferenceStore.get(SpotiFLACCacheStreamsKey) ?: true) && !storageSongCacheDisabled()

    /** Configured cap in bytes; 0 when eviction is unlimited. */
    fun limitBytes(): Long = limitBytesForMegabytes(storageSongCacheMegabytes())

    fun playbackDir(): File {
        val directory =
            StorageLocationRepository
                .cacheDirectory(context, StorageFolderKind.SONG_CACHE)
                .resolve(CACHE_SUBDIRECTORY_NAME)
        migrateLegacyPlaybackDir(directory)
        return directory.apply { mkdirs() }
    }

    /**
     * Moves files written before this folder was wired to the Storage setting.
     *
     * Only the bytes need moving: entries are keyed by track key and resolved from the file
     * found beside them ([repairFilePaths]), which is the same lookup an extension swap already
     * relies on. Attempted once per process, and it gives up quietly when the legacy folder is
     * gone - which is the normal case after the first run.
     */
    private fun migrateLegacyPlaybackDir(target: File) {
        if (legacyMigrationAttempted) return
        legacyMigrationAttempted = true
        val legacy = File(context.filesDir, LEGACY_PLAYBACK_DIR)
        if (!legacy.isDirectory) return
        val moved = runCatching {
            if (!target.isDirectory && !target.mkdirs()) return@runCatching 0
            var count = 0
            legacy.listFiles()?.forEach { source ->
                if (!source.isFile) return@forEach
                val destination = File(target, source.name)
                val relocated =
                    when {
                        destination.isFile -> source.delete()
                        source.renameTo(destination) -> true
                        // A different volume (an SD card) cannot be renamed across, so the
                        // bytes are copied and only then are the originals dropped.
                        else ->
                            runCatching {
                                source.copyTo(destination, overwrite = true)
                                source.delete()
                            }.getOrDefault(false)
                    }
                if (relocated) count++
            }
            if (legacy.listFiles().isNullOrEmpty()) legacy.delete()
            count
        }.getOrDefault(0)
        if (moved > 0) {
            SpotiFLACDiag.log("cache: moved $moved file(s) into the Storage cache folder")
        }
    }

    /**
     * Points entries whose recorded path no longer exists at the file beside them.
     *
     * A recorded absolute path stops being true the moment the cache folder moves - and it
     * also stops being true when the runtime replaces the requested `.media` name with the real
     * container. Both leave a valid file in the cache, so the entry is re-pointed rather than
     * discarded; without this, moving the cache folder would report every cached song as gone
     * until something happened to heal it one at a time.
     */
    private fun repairFilePaths() {
        // The directory is asked for *first*, because asking is what performs the one-time move
        // out of the app's private data directory. Judging entries before that would compare them
        // against paths the files are about to be moved away from - and by the time the index was
        // read again those files would look like orphans and be deleted, which is exactly the
        // opposite of what moving them was for.
        val directory = playbackDir()
        val missing = synchronized(this) { entries.values.filter { !File(it.filePath).isFile } }
        if (missing.isEmpty()) return
        val byTrackKey =
            runCatching {
                directory
                    .listFiles { file -> file.isFile && file.length() > 0L }
                    // A partial could still be growing, and it is not the finished file this
                    // lookup is healing towards in any case.
                    ?.filter { file ->
                        !SpotiFLACCacheFiles.isPartial(file.name) &&
                            !SpotiFLACCacheFiles.isBeingWritten(file.name, writingKeys)
                    }
                    ?.groupBy { SpotiFLACCacheFiles.trackKeyOf(it.name) }
            }.getOrNull() ?: return
        var repaired = 0
        synchronized(this) {
            missing.forEach { entry ->
                val match = byTrackKey[entry.trackKey]?.maxByOrNull { it.length() } ?: return@forEach
                entries[entry.trackKey] =
                    entry.copy(
                        filePath = match.absolutePath,
                        extension = match.extension,
                        sizeBytes = match.length(),
                    )
                repaired++
            }
        }
        if (repaired > 0) {
            SpotiFLACDiag.log("cache: re-pointed $repaired entr(ies) after the cache folder moved")
            persist()
        }
    }

    /** Deterministic output path handed to the runtime for a track. */
    fun outputFileFor(trackKey: String): File = File(playbackDir(), "$trackKey.media")

    /**
     * Stable cache key: `sha1(identity|qualityBucket)`.
     *
     * Identity prefers the strongest available provider id so the same song from
     * different screens collapses onto one file; it falls back to the metadata
     * tuple (which is what SpotiFLAC itself matches on).
     */
    fun trackKey(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        isrc: String?,
        spotifyTrackId: String?,
        quality: String?,
    ): String = cacheKeyFor(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        isrc = isrc,
        spotifyTrackId = spotifyTrackId,
        quality = quality,
    )

    // ------------------------------------------------------------------ lookup

    /** Cached file for a media id, when present and still on disk. */
    fun fileForMediaId(mediaId: String): File? {
        if (mediaId.isBlank()) return null
        ensureLoaded()
        val trackKey = synchronized(this) { mediaIdIndex[mediaId] } ?: return null
        val file = cachedFile(trackKey) ?: return null
        return file
    }

    /**
     * Every media id with a complete playback file in this cache.
     *
     * This store is now the *only* place a SpotiFLAC track's audio lives: the file is
     * read directly during playback and is deliberately kept out of Media3's song
     * cache, which used to hold a second copy of it. Anything that lists "songs on
     * this device" therefore has to ask here, or those tracks disappear from it.
     *
     * @param includePinned when false, user downloads are left out - the same way the
     *   cached-songs list excludes Media3's download cache.
     */
    fun cachedMediaIds(includePinned: Boolean = true): Set<String> {
        ensureLoaded()
        return synchronized(this) {
            mediaIdIndex.entries
                .asSequence()
                .filter { (_, trackKey) ->
                    val entry = entries[trackKey] ?: return@filter false
                    (includePinned || !entry.pinned) && File(entry.filePath).isFile
                }.map { it.key }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    /**
     * Cache entry for a media id. Used to report which SpotiFLAC source a track
     * actually came from, since a cached file is served without touching the
     * runtime that would otherwise name the source.
     */
    fun entryForMediaId(mediaId: String): SpotiFLACCacheEntry? {
        if (mediaId.isBlank()) return null
        ensureLoaded()
        val trackKey = synchronized(this) { mediaIdIndex[mediaId] } ?: return null
        return synchronized(this) { entries[trackKey] }
    }

    /** Marks a track key as being written, so no lookup serves or discards its file. */
    fun beginRewrite(trackKey: String) {
        if (trackKey.isNotBlank()) writingKeys.add(trackKey)
    }

    /** Ends a [beginRewrite] bracket. */
    fun endRewrite(trackKey: String) {
        writingKeys.remove(trackKey)
    }

    /**
     * Cached file for a track key, when present and still playable.
     *
     * "Present and non-empty" was the old test, and it accepted a truncated download
     * and a gateway error page written to the audio path with equal confidence. A file
     * that cannot play then failed on every single attempt, because the playback
     * recovery paths purge Media3's caches and this file is served straight off disk -
     * so re-preparing it read the same bad bytes again. Verifying here is what makes a
     * corrupt copy re-resolve through every source instead of describing a broken track.
     */
    fun cachedFile(trackKey: String): File? {
        ensureLoaded()
        val entry = synchronized(this) { entries[trackKey] } ?: return null
        val file = File(entry.filePath)
        val verdict =
            SpotiFLACFileIntegrity.verdict(
                fileLength = file.length(),
                recordedLength = entry.sizeBytes,
                head = if (file.isFile) SpotiFLACFileIntegrity.readHead(file) else null,
            )
        // Whether the file is mid-write is part of the decision, not a pre-check: a
        // re-download writes over the very file being judged, so it has to outrank the
        // "truncated" verdict that window produces.
        val rewriting = trackKey in writingKeys
        when (
            SpotiFLACFileIntegrity.actionFor(
                verdict = verdict,
                rewriting = rewriting,
                pinned = entry.pinned,
            )
        ) {
            SpotiFLACFileIntegrity.Action.SERVE -> {
                touch(trackKey, entry)
                return file
            }
            SpotiFLACFileIntegrity.Action.SERVE_KEEP -> {
                SpotiFLACDiag.log(
                    "cache serving pinned file despite verdict=$verdict key=$trackKey " +
                        "(user download is removed only on request)",
                )
                touch(trackKey, entry)
                return file
            }
            SpotiFLACFileIntegrity.Action.DISCARD -> {
                SpotiFLACDiag.log(
                    "cache discarding unreadable file key=$trackKey verdict=$verdict " +
                        "bytes=${file.length()} recorded=${entry.sizeBytes} source=${entry.sourceId}",
                )
                discardEntry(trackKey, entry)
                return null
            }
            // RESOLVE covers two different situations, and they need different handling:
            // a *missing* recorded path may just mean the runtime wrote the real
            // container beside it (adopt it below), while a file that is being rewritten
            // must not fall through at all - the sibling a download is filling in is
            // exactly the file that must not be served.
            SpotiFLACFileIntegrity.Action.RESOLVE -> if (rewriting) return null
        }
        // The runtime replaces the requested extension with the real container
        // (`.media` in, `.flac` out), so an entry can point at a name that no longer
        // exists while the audio file sits right beside it. Adopt any sibling with
        // the same track key instead of throwing away a valid download.
        val sibling = runCatching {
            playbackDir()
                .listFiles { candidate ->
                    candidate.isFile && candidate.length() > 0L &&
                        SpotiFLACCacheFiles.belongsTo(candidate.name, trackKey) &&
                        !SpotiFLACCacheFiles.isPartial(candidate.name) &&
                        !SpotiFLACCacheFiles.isBeingWritten(candidate.name, writingKeys)
                }
                ?.maxByOrNull { it.length() }
        }.getOrNull()
        if (sibling == null) {
            synchronized(this) {
                entries.remove(trackKey)
                mediaIdIndex.entries.removeIf { it.value == trackKey }
            }
            persistSoon()
            return null
        }
        val healed = entry.copy(filePath = sibling.absolutePath, extension = sibling.extension, sizeBytes = sibling.length())
        synchronized(this) { entries[trackKey] = healed }
        SpotiFLACDiag.log("cache healed key=$trackKey -> ${sibling.name}")
        persistSoon()
        return sibling
    }

    /**
     * Drops an entry and its file. Used when the stored copy is unusable, so the next
     * resolve downloads it again rather than being told it already exists.
     */
    private fun discardEntry(trackKey: String, entry: SpotiFLACCacheEntry) {
        runCatching { File(entry.filePath).takeIf { it.isFile }?.delete() }
        // A partially written file can also sit beside the recorded path when the runtime
        // swapped the requested extension for the real container. A discarded copy is
        // being thrown away on purpose, so its partial goes with it.
        runCatching {
            playbackDir()
                .listFiles { candidate ->
                    candidate.isFile &&
                        SpotiFLACCacheFiles.belongsTo(candidate.name, trackKey) &&
                        !SpotiFLACCacheFiles.isBeingWritten(candidate.name, writingKeys)
                }
                ?.forEach { it.delete() }
        }
        synchronized(this) {
            entries.remove(trackKey)
            mediaIdIndex.entries.removeIf { it.value == trackKey }
        }
        persistSoon()
    }

    /** Full entry (codec/quality metadata) for a track key. */
    fun entry(trackKey: String): SpotiFLACCacheEntry? {
        ensureLoaded()
        val entry = synchronized(this) { entries[trackKey] } ?: return null
        return File(entry.filePath).takeIf { it.isFile }?.let { entry }
    }

    private fun touch(
        trackKey: String,
        entry: SpotiFLACCacheEntry,
    ) {
        val now = System.currentTimeMillis()
        if (now - entry.lastUsedAtMs < TOUCH_INTERVAL_MS) return
        synchronized(this) {
            entries[trackKey] = entry.copy(lastUsedAtMs = now)
        }
        persistSoon()
    }

    /**
     * The key a queue item resolved to, without checking that its file still exists.
     *
     * Used as a second-chance lookup: the identity inputs can legitimately drift for
     * the same track, and the index is keyed on the queue item rather than on the
     * metadata that happens to be available at the time.
     */
    fun trackKeyForMediaId(mediaId: String?): String? {
        if (mediaId.isNullOrBlank()) return null
        ensureLoaded()
        return synchronized(this) { mediaIdIndex[mediaId] }?.takeIf { it.isNotBlank() }
    }

    /** Remembers which queue item resolved to which cache entry. */
    fun associateMediaId(
        mediaId: String?,
        trackKey: String,
    ) {
        if (mediaId.isNullOrBlank()) return
        ensureLoaded()
        synchronized(this) { mediaIdIndex[mediaId] = trackKey }
        persistSoon()
    }

    // ------------------------------------------------------------------ record

    /** Records a freshly written playback file and applies the size cap. */
    fun record(
        trackKey: String,
        file: File,
        sourceId: String?,
        codec: String?,
        bitDepth: Int?,
        sampleRate: Int?,
        mediaId: String? = null,
        pinned: Boolean = false,
        qualityBucket: String? = null,
    ) {
        ensureLoaded()
        val entry = SpotiFLACCacheEntry(
            trackKey = trackKey,
            filePath = file.absolutePath,
            extension = file.extension.takeIf { it.isNotBlank() },
            codec = codec,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
            sourceId = sourceId,
            sizeBytes = file.length(),
            lastUsedAtMs = System.currentTimeMillis(),
            pinned = pinned,
            qualityBucket = qualityBucket,
        )
        synchronized(this) {
            entries[trackKey] = entry
            if (!mediaId.isNullOrBlank()) mediaIdIndex[mediaId] = trackKey
        }
        // One key owns one finished file. Anything else wearing this key is the runtime's
        // container-suffixed copy or an interrupted transfer's fragment, and neither is
        // named by an entry - so nothing else would ever remove it, and the song would sit on
        // the device twice while only one copy counted against the cache size.
        pruneLeftovers(trackKey = trackKey, keep = file)
        evictIfNeeded()
        persist()
    }

    /**
     * Deletes every file of [trackKey] except [keep], returning how many went.
     *
     * Called where a track's finished file is known, which is the only moment at which "this
     * file is the track, those are not" can be stated without guessing. Deliberately not guarded
     * by [writingKeys]: the caller *is* the write, and a leftover of an abandoned attempt is
     * exactly what this exists to remove.
     */
    fun pruneLeftovers(trackKey: String, keep: File): Int {
        if (trackKey.isBlank()) return 0
        var removed = 0
        runCatching {
            playbackDir()
                .listFiles { candidate ->
                    candidate.isFile &&
                        SpotiFLACCacheFiles.belongsTo(candidate.name, trackKey) &&
                        candidate.absolutePath != keep.absolutePath
                }
                ?.forEach { leftover -> if (leftover.delete()) removed++ }
        }
        if (removed > 0) {
            SpotiFLACDiag.log("cache pruned $removed leftover file(s) key=$trackKey")
        }
        return removed
    }

    /**
     * Reconciles the index with the filesystem: drops entries whose file is gone
     * and deletes orphan files (the pre-cache builds leaked one file per resolve).
     */
    fun reconcile() {
        ensureLoaded()
        val known = synchronized(this) { entries.values.map { it.filePath }.toSet() }
        var removed = 0
        runCatching {
            playbackDir().listFiles { file -> file.isFile }?.forEach { file ->
                // An in-flight transfer is not an orphan just because the index does not
                // name it yet - and its bytes include the partial it is filling in.
                if (SpotiFLACCacheFiles.isBeingWritten(file.name, writingKeys)) return@forEach
                // An unfinished transfer is never a file anything can play, so it goes
                // whether or not an entry names it. Left alone, an attempt that died
                // mid-download keeps its bytes beside the real file for the life of the
                // install, and every later resolve of that track pays for them again.
                val orphan = file.absolutePath !in known
                if ((orphan || SpotiFLACCacheFiles.isPartial(file.name)) && file.delete()) {
                    removed++
                }
            }
        }
        val stale = synchronized(this) {
            entries.filterValues { !File(it.filePath).isFile }
        }.keys
        if (stale.isNotEmpty()) {
            synchronized(this) {
                stale.forEach { key ->
                    entries.remove(key)
                    mediaIdIndex.entries.removeIf { it.value == key }
                }
            }
        }
        if (removed > 0 || stale.isNotEmpty()) {
            SpotiFLACDiag.log("cache reconcile: orphans=$removed stale=$stale")
            persist()
        }
    }

    // ---------------------------------------------------------------- eviction

    /** Evicts least-recently-used unpinned entries until the cap is respected. */
    fun evictIfNeeded(): Long {
        val limit = limitBytes()
        if (limit <= 0L) return 0L
        var freed = 0L
        while (true) {
            val total = totalBytes()
            if (total <= limit) break
            val victim = synchronized(this) {
                entries.values
                    .filter { !it.pinned && it.trackKey !in writingKeys }
                    .minByOrNull { it.lastUsedAtMs }
            } ?: break
            runCatching { File(victim.filePath).delete() }
            synchronized(this) {
                entries.remove(victim.trackKey)
                mediaIdIndex.entries.removeIf { it.value == victim.trackKey }
            }
            freed += victim.sizeBytes
            Timber.tag(TAG).i("Evicted cached track %s (%d bytes)", victim.trackKey, victim.sizeBytes)
        }
        if (freed > 0L) {
            SpotiFLACDiag.log("cache evicted bytes=$freed")
            persist()
        }
        return freed
    }

    fun totalBytes(): Long =
        synchronized(this) { entries.values.sumOf { entry ->
            File(entry.filePath).takeIf { it.isFile }?.length() ?: 0L
        } }

    fun stats(): SpotiFLACCacheStats =
        SpotiFLACCacheStats(
            bytes = totalBytes(),
            fileCount = synchronized(this) { entries.size },
            limitBytes = limitBytes(),
            enabled = cacheStreamingEnabled(),
        )

    /**
     * Drops the cached playback file for one track and returns the freed byte count.
     *
     * Used when the user removes a download: the media3 download record and this cache
     * are separate stores, so without this the lossless file would stay on disk and the
     * next play would be served from it, silently bypassing the source priority order.
     * Pinned entries are excluded so a configured-cache eviction can never delete a
     * file the user explicitly kept.
     */
    fun removeForMediaId(mediaId: String): Long = removeEntryForMediaId(mediaId, pinnedIsRemovable = false)

    /**
     * Drops the cached copy of [mediaId], pinned or not.
     *
     * Used when a download is removed. That cached file is the same song a second time, and
     * leaving it behind made the next download of the song finish instantly from bytes the user
     * had just deleted - and kept answering a resolve with the discarded copy, including its
     * quality. Only this track's files are touched.
     */
    fun discardForMediaId(mediaId: String): Long = removeEntryForMediaId(mediaId, pinnedIsRemovable = true)

    private fun removeEntryForMediaId(mediaId: String, pinnedIsRemovable: Boolean): Long {
        if (mediaId.isBlank()) return 0L
        ensureLoaded()
        val trackKey = synchronized(this) { mediaIdIndex[mediaId] } ?: return 0L
        val entry = synchronized(this) { entries[trackKey] } ?: return 0L
        if (entry.pinned && !pinnedIsRemovable) return 0L
        var freed = 0L
        val file = File(entry.filePath)
        if (file.isFile) {
            freed += file.length()
            runCatching { file.delete() }
        }
        // Every file of this key goes, not just the recorded one: the runtime's own
        // extension-suffixed copy and any partial left by an interrupted transfer are the
        // same song, and leaving them behind answers the next resolve with bytes the user
        // asked to be rid of.
        runCatching {
            playbackDir()
                .listFiles { candidate ->
                    candidate.isFile &&
                        SpotiFLACCacheFiles.belongsTo(candidate.name, trackKey) &&
                        !SpotiFLACCacheFiles.isBeingWritten(candidate.name, writingKeys)
                }
                ?.forEach { sibling ->
                    if (sibling.absolutePath != file.absolutePath) {
                        freed += sibling.length()
                        sibling.delete()
                    }
                }
        }
        synchronized(this) {
            entries.remove(trackKey)
            mediaIdIndex.entries.removeIf { it.value == trackKey }
        }
        persistSoon()
        SpotiFLACDiag.log(
            "cache removed mediaId=$mediaId key=$trackKey bytes=$freed " +
                if (pinnedIsRemovable) "(download removed)" else "(cache eviction)",
        )
        return freed
    }

    /** Deletes every unpinned cached file and returns the freed byte count. */
    fun clear(): Long {
        ensureLoaded()
        var freed = 0L
        synchronized(this) {
            entries.values.filter { !it.pinned }.forEach { entry ->
                val file = File(entry.filePath)
                if (file.isFile) {
                    freed += file.length()
                    runCatching { file.delete() }
                }
                entries.remove(entry.trackKey)
                mediaIdIndex.entries.removeIf { it.value == entry.trackKey }
            }
        }
        persist()
        SpotiFLACDiag.log("cache cleared bytes=$freed")
        return freed
    }

    fun warmUp() {
        ensureLoaded()
    }

    // -------------------------------------------------------------- internals

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                if (indexFile.isFile) {
                    val index = json.decodeFromString<SpotiFLACCacheIndex>(indexFile.readText())
                    index.entries.forEach { entry -> entries[entry.trackKey] = entry }
                    mediaIdIndex.putAll(index.mediaIds)
                }
            }.onFailure {
                SpotiFLACDiag.log("cache index load failed: ${it.message}")
                entries.clear()
                mediaIdIndex.clear()
            }
            // Before anything reads or reconciles: an entry still naming its old path would
            // otherwise be dropped as stale, and its file deleted as an orphan.
            repairFilePaths()
            loaded = true
        }
    }

    /**
     * Forgets every entry, files included.
     *
     * Used after Storage clears the song cache, whose folder these files live in: the entries
     * are bookkeeping for bytes that no longer exist, and keeping them would leave a source
     * label, a codec row and an offline badge describing songs this device does not have.
     */
    fun forgetAll() {
        ensureLoaded()
        val files = synchronized(this) { entries.values.map { it.filePath } }
        files.forEach { path -> runCatching { File(path).takeIf { it.isFile }?.delete() } }
        synchronized(this) {
            entries.clear()
            mediaIdIndex.clear()
        }
        persist()
        SpotiFLACDiag.log("cache index cleared (song cache cleared in Storage)")
    }

    private fun persist() {
        runCatching {
            val snapshot = synchronized(this) {
                SpotiFLACCacheIndex(
                    entries = entries.values.toList(),
                    mediaIds = mediaIdIndex.toMap(),
                )
            }
            indexFile.parentFile?.mkdirs()
            val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
            temp.writeText(json.encodeToString(snapshot))
            if (!temp.renameTo(indexFile)) {
                indexFile.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { SpotiFLACDiag.log("cache index persist failed: ${it.message}") }
    }

    private fun persistSoon() {
        // Synchronous, but the index is small (a few hundred entries) and writes
        // only happen on cache hits / new downloads.
        persist()
    }

}
