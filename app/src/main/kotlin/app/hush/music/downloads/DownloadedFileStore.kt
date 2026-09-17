/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.downloads

import android.content.Context
import app.hush.music.playback.StoredBytesOrigin
import app.hush.music.spotiflac.SpotiFLACDiag
import app.hush.music.storage.StorageFolderKind
import app.hush.music.storage.StorageLocationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One downloaded file on disk, and which engine produced its bytes. */
@Serializable
data class DownloadedFile(
    val mediaId: String,
    val path: String,
    val origin: String,
    val title: String = "",
    val artist: String = "",
    val bytes: Long = 0L,
    val createdAtMs: Long = 0L,
    /**
     * The name this download has inside the downloads folder, which is the part that does
     * not change when the folder does. Empty on records written before the index became
     * location-relative; those are repaired from [path] on load.
     */
    val fileName: String = "",
) {
    val file: File get() = File(path)
}

@Serializable
private data class DownloadedFileIndex(
    val version: Int = 1,
    val entries: List<DownloadedFile> = emptyList(),
)

/**
 * The index of downloads that live as ordinary files.
 *
 * A download used to be nothing but a pinned Media3 cache entry, which meant its bytes
 * were stored as 5 MiB cache fragments in shard directories under a name derived from a
 * numeric uid (`15.10485760.1789300859647.v3.exo`) - unreadable, unportable, and one
 * file per 5 MiB of audio. Downloads are now written once, as one file, named after the
 * track and carrying the extension of the container the source actually served.
 *
 * That file is the download, so this store is what answers "is this song downloaded?"
 * and "where is it?". Media3's own download index cannot: it knows only cache keys.
 * Entries whose file has disappeared are dropped on load rather than reported, so a
 * download deleted outside the app (a file manager, or a factory reset of the folder)
 * cannot keep pretending to be playable.
 */
@Singleton
class DownloadedFileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private companion object {
            const val INDEX_FILE_NAME = "downloaded_files.json"
            const val MAX_ENTRIES = 5000
        }

        private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; prettyPrint = false }
        private val indexFile = File(context.filesDir, INDEX_FILE_NAME)

        /** Insertion ordered, so overflow evicts the oldest record first. */
        private val entries = LinkedHashMap<String, DownloadedFile>()

        @Volatile
        private var loaded = false

        fun all(): List<DownloadedFile> {
            ensureLoaded()
            return synchronized(this) { entries.values.toList() }
        }

        fun get(mediaId: String): DownloadedFile? {
            if (mediaId.isBlank()) return null
            ensureLoaded()
            return synchronized(this) { entries[mediaId] }
        }

        /**
         * The downloaded file for [mediaId], or null when there is no record *or* the
         * recorded file is gone.
         *
         * Both halves matter to callers: a download whose file vanished must not be
         * served, or playback fails at open time instead of resolving the track normally.
         */
        fun fileFor(mediaId: String): File? {
            val recorded = get(mediaId) ?: return null
            return DownloadedFileLocation.resolve(downloadsDirectory(), recorded)
        }

        /**
         * The folder downloads live in *now*, read from the storage setting rather than
         * remembered from when the file was written - that is what makes a folder change
         * (or a volume change, or a reset) leave this index valid instead of orphaned.
         */
        private fun downloadsDirectory(): File = StorageLocationRepository.cacheDirectory(context, StorageFolderKind.DOWNLOADS)

        /**
         * Adopts a file the downloads folder already holds for a track.
         *
         * The index does not survive a reinstall, a data clear, or a restore onto another
         * device, but the *files* do: they are ordinary files in a folder the user chose, named
         * `<Artist> - <Title>.<container>`. So when a track is about to be resolved and nothing is
         * recorded for it, the folder is asked whether it already holds this song. The rule is the
         * one the user can see in their own file manager, which is what makes it predictable
         * rather than clever. The index is re-established, so this costs one directory read per
         * track, once.
         *
         * A file another entry already claims is skipped: two tracks with the same name are
         * common, and the one whose download wrote the file is the one that owns it. The rules
         * themselves live in [DownloadsFolderAdoption], where they can be tested directly.
         *
         * @return the adopted record, or null when the folder holds nothing for this track.
         */
        fun adoptExistingFile(
            mediaId: String,
            title: String?,
            artist: String?,
        ): DownloadedFile? {
            if (mediaId.isBlank()) return null
            ensureLoaded()
            if (synchronized(this) { entries.containsKey(mediaId) }) return null
            val stems = DownloadsFolderAdoption.candidateFileStems(title, artist)
            if (stems.isEmpty()) return null
            val candidates = runCatching {
                downloadsDirectory().listFiles { file -> file.isFile && file.length() > 0L }?.toList()
            }.getOrNull() ?: return null
            if (candidates.isEmpty()) return null
            val claimed =
                synchronized(this) {
                    entries.values.mapNotNull { it.fileName.takeIf { name -> name.isNotBlank() } }.toSet()
                }
            val match = DownloadsFolderAdoption.match(candidates, claimed, stems) ?: return null
            val adopted =
                DownloadedFile(
                    mediaId = mediaId,
                    path = match.absolutePath,
                    // Which engine wrote these bytes is not knowable from the file alone, and
                    // saying "YouTube" or "SpotiFLAC" here would be a guess that decides whether
                    // the track may play with YouTube switched off. Unknown is the honest
                    // value, and it is what a download recorded by an older build carries too.
                    origin = StoredBytesOrigin.UNKNOWN.name,
                    title = title.orEmpty(),
                    artist = artist.orEmpty(),
                    bytes = match.length(),
                    createdAtMs = match.lastModified(),
                    fileName = match.name,
                )
            record(adopted)
            SpotiFLACDiag.log(
                "downloads: re-adopted ${match.name} (${adopted.bytes} bytes) for mediaId=$mediaId",
            )
            return adopted
        }

        fun totalBytes(): Long = all().sumOf { it.bytes }

        fun record(entry: DownloadedFile) {
            if (entry.mediaId.isBlank() || entry.path.isBlank()) return
            ensureLoaded()
            synchronized(this) {
                entries.remove(entry.mediaId)
                entries[entry.mediaId] = entry
                while (entries.size > MAX_ENTRIES) {
                    val oldest = entries.keys.firstOrNull() ?: break
                    entries.remove(oldest)
                }
            }
            persist()
        }

        /** Drops the record for [mediaId], returning it so the caller can delete the file. */
        fun forget(mediaId: String): DownloadedFile? {
            if (mediaId.isBlank()) return null
            ensureLoaded()
            val removed = synchronized(this) { entries.remove(mediaId) }
            if (removed != null) persist()
            return removed
        }

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                runCatching {
                    if (indexFile.isFile) {
                        val index = json.decodeFromString<DownloadedFileIndex>(indexFile.readText())
                        index.entries.forEach { entry ->
                            if (entry.mediaId.isNotBlank()) {
                                entries[entry.mediaId] = entry
                            }
                        }
                    }
                    // Resolution, not a raw path check: a record whose name is present in
                    // the current downloads folder is this download, wherever the folder
                    // has moved to. Anything that cannot be resolved is a download this
                    // device no longer has, and is dropped rather than reported.
                    val directory = downloadsDirectory()
                    var repaired = false
                    val stale = mutableListOf<String>()
                    entries.entries.forEach { (mediaId, entry) ->
                        val resolved = DownloadedFileLocation.resolve(directory, entry)
                        if (resolved == null) {
                            stale += mediaId
                            return@forEach
                        }
                        val name = resolved.name
                        if (entry.fileName != name || entry.path != resolved.absolutePath) {
                            entries[mediaId] =
                                entry.copy(
                                    fileName = name,
                                    path = resolved.absolutePath,
                                    bytes = entry.bytes.takeIf { it > 0L } ?: resolved.length(),
                                )
                            repaired = true
                        }
                    }
                    stale.forEach { entries.remove(it) }
                    // Written back immediately, so a repair happens once rather than on
                    // every load - and so the old absolute paths stop being consulted.
                    if (repaired) persist()
                }.onFailure {
                    SpotiFLACDiag.log("downloaded file index load failed: ${it.message}")
                    entries.clear()
                }
                loaded = true
            }
        }

        private fun persist() {
            runCatching {
                val snapshot =
                    synchronized(this) {
                        DownloadedFileIndex(entries = entries.values.toList())
                    }
                val directory = indexFile.parentFile
                directory?.mkdirs()
                val temp = File(directory, "${indexFile.name}.tmp")
                temp.writeText(json.encodeToString(snapshot))
                if (!temp.renameTo(indexFile)) {
                    indexFile.writeText(temp.readText())
                    temp.delete()
                }
            }.onFailure { SpotiFLACDiag.log("downloaded file index persist failed: ${it.message}") }
        }
    }
