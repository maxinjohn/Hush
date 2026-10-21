/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.datasource.cache.Cache
import coil3.imageLoader
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.hush.music.constants.GitHubContributorsEtagKey
import app.hush.music.constants.GitHubContributorsJsonKey
import app.hush.music.constants.GitHubContributorsLastCheckedAtKey
import app.hush.music.constants.GitHubTranslationContributorsJsonKey
import app.hush.music.constants.GitHubTranslationContributorsLastCheckedAtKey
import app.hush.music.constants.DownloadsStorageDisplayNameKey
import app.hush.music.constants.DownloadsStoragePathKey
import app.hush.music.constants.SongCacheStorageDisplayNameKey
import app.hush.music.constants.SongCacheStoragePathKey
import app.hush.music.constants.StorageFolderDisplayNameKey
import app.hush.music.constants.StorageFolderIdKey
import app.hush.music.constants.StorageFolderPathKey
import app.hush.music.constants.StorageFolderTreeUriKey
import app.hush.music.di.DownloadCache
import app.hush.music.di.PlayerCache
import app.hush.music.playback.DownloadUtil
import app.hush.music.spotiflac.SpotiFLACPlaybackCache
import app.hush.music.ui.player.CanvasArtworkPlaybackCache
import app.hush.music.utils.ArtworkStorage
import app.hush.music.utils.PreferenceStore
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import java.io.File
import javax.inject.Inject

enum class StorageFolderKind(
    val defaultDirectoryName: String,
) {
    SONG_CACHE(defaultDirectoryName = "exoplayer"),
    DOWNLOADS(defaultDirectoryName = "download"),
    IMAGE_CACHE(defaultDirectoryName = "coil"),
    CANVAS_CACHE(defaultDirectoryName = "canvas"),
    ARTWORK_CACHE(defaultDirectoryName = "artwork"),
}

@Immutable
data class StorageFolderSelection(
    val selectedOption: StorageLocationOption,
    val options: StorageLocationOptions,
)

@Immutable
data class StorageLocationOptions(
    private val values: List<StorageLocationOption>,
) {
    val size: Int get() = values.size

    operator fun get(index: Int): StorageLocationOption = values[index]

    fun firstOrNull(predicate: (StorageLocationOption) -> Boolean): StorageLocationOption? = values.firstOrNull(predicate)

    fun forEach(action: (StorageLocationOption) -> Unit) {
        values.forEach(action)
    }
}

@Immutable
data class StorageLocationOption(
    val id: String,
    val kind: StorageLocationKind,
    val volumeLabel: String?,
    val rootPath: String,
    val availableBytes: Long,
    val isSelected: Boolean,
)

enum class StorageLocationKind {
    INTERNAL,
    REMOVABLE,
}

@Immutable
data class StorageMigrationProgress(
    val phase: StorageMigrationPhase,
    val percent: Int,
)

@Immutable
data class StorageCacheClearProgress(
    val kind: StorageCacheKind,
    val percent: Int,
)

enum class StorageMigrationPhase {
    CACHE,
    DOWNLOADS,
}

sealed interface StorageFolderUpdateResult {
    data object Success : StorageFolderUpdateResult

    /**
     * Saved, and already in effect: the resolved folder is the one the app was already using,
     * so there is nothing to move and nothing to restart for.
     */
    data object SuccessWithoutRestart : StorageFolderUpdateResult

    data object InvalidTree : StorageFolderUpdateResult

    data object UnsupportedProvider : StorageFolderUpdateResult

    data object NotWritable : StorageFolderUpdateResult

    /**
     * The folder is real, and this app is not allowed into it yet.
     *
     * Said apart from [NotWritable] because it has an answer the user can give: Android's All
     * files access, which the app asks for on demand. Distinguishing it is what turns "Hush
     * could not write a file in that folder" into a screen the user can act on - and it is why a
     * folder picked in Download no longer has to fail: since Android 11 an app may create files
     * in shared storage but may not write into a folder another app created, and the folder
     * chooser is another app.
     *
     * Nothing is saved when this is returned: a location that cannot be used is not a location,
     * and saving it anyway is what made a pick look successful while every download went to the
     * folder the app fell back to.
     */
    data object NeedsAllFilesAccess : StorageFolderUpdateResult
}

enum class StorageCacheKind {
    SONGS,
    DOWNLOADS,
    IMAGES,
    CANVAS,
}

sealed interface StorageCacheClearResult {
    data object Success : StorageCacheClearResult

    data object Failed : StorageCacheClearResult
}

/**
 * How usable the folder a storage kind is pointed at is, right now.
 *
 * Downloads are ordinary files in a folder the user chose, and nothing else holds them: when that
 * folder disappears - the card was pulled, the grant was revoked, the folder was deleted - every
 * download becomes unreachable and new ones cannot be written, while the rest of the app carries on
 * looking normal. This is the answer to "is the folder I was told to keep downloads in still
 * there, and can I still write in it?", so the screens can say so before the user finds out by
 * losing a download.
 */
sealed interface StorageFolderHealth {
    /** The app's own directory, or the chosen folder, and it can be written in. */
    data object Healthy : StorageFolderHealth

    /** The chosen folder is not there: the card was removed, or the folder was deleted. */
    data class Missing(
        val path: String,
    ) : StorageFolderHealth

    /** The folder is there and this app cannot write in it, for a reason no access switch fixes. */
    data class NotWritable(
        val path: String,
    ) : StorageFolderHealth

    /** The folder is there, and only Android's All files access is standing in the way. */
    data class NeedsAllFilesAccess(
        val path: String,
    ) : StorageFolderHealth
}

object StorageRestartScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var restartJob: Job? = null

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        restartJob?.cancel()
        restartJob =
            scope.launch {
                delay(AppRestartDelayMillis)
                appContext.restartApp()
            }
    }
}

class ObserveStorageFoldersUseCase
    @Inject
    constructor(
        private val repository: StorageLocationRepository,
    ) {
        operator fun invoke(): Flow<StorageFolderSelection> = repository.selection
    }

/**
 * Watches a folder kind for becoming unusable, or usable again.
 *
 * Re-checked on a slow timer rather than only when asked, because a folder can stop being usable
 * while the app is running and nothing in the app is told: the card is pulled, the system access
 * is revoked in Settings, the folder is deleted from a file manager. The check is a real write
 * probe, which is the only reliable answer on Android 11+; it is skipped entirely while the app's
 * own directory is in use, so the common case costs nothing.
 */
class ObserveStorageFolderHealthUseCase
    @Inject
    constructor(
        private val repository: StorageLocationRepository,
    ) {
        operator fun invoke(kind: StorageFolderKind): Flow<StorageFolderHealth> =
            flow {
                while (true) {
                    emit(repository.folderHealth(kind))
                    delay(StorageFolderHealthCheckIntervalMillis)
                }
            }.flowOn(Dispatchers.IO)
                .distinctUntilChanged()
    }

class SetStorageFolderUseCase
    @Inject
    constructor(
        private val repository: StorageLocationRepository,
    ) {
        suspend operator fun invoke(
            optionId: String,
            onProgress: suspend (StorageMigrationProgress) -> Unit,
        ): StorageFolderUpdateResult = repository.setStorageLocationAndMoveCache(optionId, onProgress)
    }

class SetCustomStorageFolderUseCase
    @Inject
    constructor(
        private val repository: StorageLocationRepository,
    ) {
        suspend operator fun invoke(
            kind: StorageFolderKind,
            path: String?,
            displayName: String?,
        ): StorageFolderUpdateResult = repository.setCustomFolderPath(kind, path, displayName)
    }

class UsePickedFolderUseCase
    @Inject
    constructor(
        private val repository: StorageLocationRepository,
    ) {
        suspend operator fun invoke(
            treeUri: Uri,
            targetKind: StorageFolderKind,
        ): StorageFolderUpdateResult = repository.usePickedFolderAsLocation(treeUri, targetKind)
    }

class ClearStorageCacheUseCase
    @Inject
    constructor(
        private val repository: StorageLocationRepository,
    ) {
        suspend operator fun invoke(
            kind: StorageCacheKind,
            onProgress: suspend (StorageCacheClearProgress) -> Unit,
        ): StorageCacheClearResult = repository.clearCache(kind, onProgress)
    }

class StorageLocationRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @PlayerCache private val playerCache: Cache,
        @DownloadCache private val downloadCache: Cache,
        private val downloadUtil: DownloadUtil,
    ) {
        val selection: Flow<StorageFolderSelection> =
            context.dataStore.data.map { preferences ->
                preferences.selectionFor(context.storageLocationOptions(preferences))
            }

        /**
         * How usable the folder [kind] is pointed at is, right now.
         *
         * [cacheDirectory] answers where files go *now*, silently falling back to the app's own
         * directory when the chosen one cannot be entered - the right answer for opening a cache
         * and the wrong one for telling the user their folder is gone. This answers that question
         * instead, and is what the storage screen and the warning banner read.
         */
        fun folderHealth(kind: StorageFolderKind): StorageFolderHealth = folderHealth(context, kind)

        suspend fun clearCache(
            kind: StorageCacheKind,
            onProgress: suspend (StorageCacheClearProgress) -> Unit,
        ): StorageCacheClearResult =
            withContext(Dispatchers.IO) {
                try {
                    onProgress(StorageCacheClearProgress(kind = kind, percent = 0))
                    val cleared =
                        when (kind) {
                            StorageCacheKind.SONGS -> {
                                val cleared =
                                    clearReleasedMediaCache(playerCache, StorageFolderKind.SONG_CACHE, onProgress)
                                // SpotiFLAC's cached songs live in this folder too, so their index
                                // has to go with it. An entry kept past its bytes would keep a
                                // source label, a codec row and an offline badge describing songs
                                // this device no longer has.
                                if (cleared) {
                                    runCatching { SpotiFLACPlaybackCache.getInstance()?.forgetAll() }
                                }
                                cleared
                            }
                            StorageCacheKind.DOWNLOADS -> clearDownloads(onProgress)
                            StorageCacheKind.IMAGES -> clearImageCache(onProgress)
                            StorageCacheKind.CANVAS -> clearCanvasCache(onProgress)
                        }
                    if (cleared) {
                        onProgress(StorageCacheClearProgress(kind = kind, percent = 100))
                    }
                    if (cleared) StorageCacheClearResult.Success else StorageCacheClearResult.Failed
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    StorageCacheClearResult.Failed
                }
            }

        /**
         * Points a folder kind at a user-picked folder.
         *
         * The failure answers are deliberately distinct, because they need different responses
         * from the user: [StorageFolderUpdateResult.InvalidTree] if the selection cannot be
         * turned into a directory at all, [StorageFolderUpdateResult.NotWritable] if this app is
         * not allowed to keep files there, [StorageFolderUpdateResult.NeedsAllFilesAccess] if the
         * platform is refusing shared storage until the user grants All files access (an answer
         * the settings screen can ask for), and [StorageFolderUpdateResult.UnsupportedProvider]
         * if the selection is not on the filesystem (a cloud or media provider), which the
         * file-backed cache cannot use.
         *
         * A folder that is not usable is never saved. The old code saved first and reported
         * success, then resolved the saved path at every later use and quietly fell back to the
         * app's own directory when it could not write there - so the setting screen showed the
         * picked folder, the app stored elsewhere, and the only trace was a log line.
         */
        suspend fun setCustomFolderPath(
            kind: StorageFolderKind,
            path: String?,
            displayName: String?,
        ): StorageFolderUpdateResult =
            withContext(Dispatchers.IO) {
                if (kind != StorageFolderKind.DOWNLOADS && kind != StorageFolderKind.SONG_CACHE) {
                    return@withContext StorageFolderUpdateResult.UnsupportedProvider
                }

                val preferencesSnapshot = context.dataStore.data.first()
                val currentDirectory = activeCacheDirectory(preferencesSnapshot, kind)
                val normalizedPath = path?.trim()?.takeIf(String::isNotBlank)
                val destination =
                    if (normalizedPath == null) {
                        defaultDirectoryForKind(preferencesSnapshot, kind)
                    } else {
                        runCatching { File(normalizedPath).canonicalFile }.getOrNull()
                            ?: return@withContext StorageFolderUpdateResult.InvalidTree
                    }

                if (!destination.ensureWritableDirectory()) {
                    return@withContext if (destination.requiresAllFilesAccess(context)) {
                        StorageFolderUpdateResult.NeedsAllFilesAccess
                    } else {
                        StorageFolderUpdateResult.NotWritable
                    }
                }

                val locationChanged = currentDirectory.canonicalPath != destination.canonicalPath

                context.dataStore.edit { preferences ->
                    when (kind) {
                        StorageFolderKind.DOWNLOADS -> {
                            if (normalizedPath == null) {
                                preferences.remove(DownloadsStoragePathKey)
                                preferences.remove(DownloadsStorageDisplayNameKey)
                            } else {
                                preferences[DownloadsStoragePathKey] = destination.canonicalPath
                                if (!displayName.isNullOrBlank()) {
                                    preferences[DownloadsStorageDisplayNameKey] = displayName
                                } else {
                                    preferences.remove(DownloadsStorageDisplayNameKey)
                                }
                            }
                        }

                        StorageFolderKind.SONG_CACHE -> {
                            if (normalizedPath == null) {
                                preferences.remove(SongCacheStoragePathKey)
                                preferences.remove(SongCacheStorageDisplayNameKey)
                            } else {
                                preferences[SongCacheStoragePathKey] = destination.canonicalPath
                                if (!displayName.isNullOrBlank()) {
                                    preferences[SongCacheStorageDisplayNameKey] = displayName
                                } else {
                                    preferences.remove(SongCacheStorageDisplayNameKey)
                                }
                            }
                        }

                        else -> Unit
                    }
                }
                context.storageLocationPreferences().edit().apply {
                    when (kind) {
                        StorageFolderKind.DOWNLOADS -> {
                            if (normalizedPath == null) {
                                remove(DownloadsStoragePathMirrorKey)
                            } else {
                                putString(DownloadsStoragePathMirrorKey, destination.canonicalPath)
                            }
                        }

                        StorageFolderKind.SONG_CACHE -> {
                            if (normalizedPath == null) {
                                remove(SongCacheStoragePathMirrorKey)
                            } else {
                                putString(SongCacheStoragePathMirrorKey, destination.canonicalPath)
                            }
                        }

                        else -> Unit
                    }
                    // commit(), not apply(): the app is restarted seconds from now, and this
                    // SharedPreferences mirror is what builds the cache directory at startup.
                    // An asynchronous write could lose the setting to the process kill, which
                    // is exactly how a chosen folder used to come back as the default.
                }.commit()
                if (locationChanged) {
                    // The contents of the folder being left behind are carried over as work,
                    // not as part of saving the setting: a library is tens of gigabytes, and
                    // a pick that only takes effect once every byte has been copied is a pick
                    // that looks like nothing happened - and is undone by the app being
                    // closed, which is exactly what a user does when no progress appears.
                    // Until each file lands, it still plays from the old folder.
                    StorageFolderMoveScheduler.enqueue(context, kind, currentDirectory)
                    // The restart re-points the caches that were opened against the old
                    // directory. Re-picking the folder already in use changes no directory, so
                    // it must not kill the app: that is the "restart needed after choosing the
                    // location" the settings screen used to demand for a no-op.
                    StorageRestartScheduler.schedule(context)
                    StorageFolderUpdateResult.Success
                } else {
                    StorageFolderUpdateResult.SuccessWithoutRestart
                }
            }

        /**
         * Uses a folder the user picked as the storage location for [targetKind].
         *
         * This used to be wired to an "import" routine that copied the picked folder's
         * contents *into* the current location and never recorded a path, so choosing a
         * custom folder appeared to succeed, restarted the app, and came back with the old
         * location - and downloads kept going to the default folder. A SAF tree is not a
         * location until its document id is turned back into a real directory, which is
         * what [context.treeUriToDirectory] does here.
         */
        suspend fun usePickedFolderAsLocation(
            treeUri: Uri,
            targetKind: StorageFolderKind,
        ): StorageFolderUpdateResult {
            if (targetKind != StorageFolderKind.DOWNLOADS && targetKind != StorageFolderKind.SONG_CACHE) {
                return StorageFolderUpdateResult.UnsupportedProvider
            }
            // A folder behind a cloud/media provider has no filesystem path, and the
            // file-backed cache (Media3's SimpleCache) writes with plain File IO. Saying so
            // is the honest answer; the old code copied such a folder into the app's cache
            // directory instead, which could not have produced a playable cache anyway.
            val directory =
                context.treeUriToDirectory(treeUri)
                    ?: return StorageFolderUpdateResult.UnsupportedProvider
            val displayName =
                runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: directory.name
            val result =
                setCustomFolderPath(
                    kind = targetKind,
                    path = directory.absolutePath,
                    displayName = displayName,
                )
            if (result != StorageFolderUpdateResult.Success && result != StorageFolderUpdateResult.SuccessWithoutRestart) {
                // The grant the chooser handed back is for a folder the app is not keeping, so
                // it is handed back rather than held: a grant nobody uses is a folder this app
                // can still read on every later launch, which is not what the user agreed to.
                releasePersistedPermission(treeUri.toString(), replacementUri = null)
            }
            return result
        }

        suspend fun setStorageLocationAndMoveCache(
            optionId: String,
            onProgress: suspend (StorageMigrationProgress) -> Unit,
        ): StorageFolderUpdateResult =
            withContext(Dispatchers.IO) {
                val preferencesSnapshot = context.dataStore.data.first()
                val previousUri = preferencesSnapshot[StorageFolderTreeUriKey]
                val options = context.storageLocationOptions(preferencesSnapshot)
                val selectedOption =
                    options.firstOrNull { option -> option.id == optionId }
                        ?: return@withContext StorageFolderUpdateResult.UnsupportedProvider
                if (selectedOption.kind == StorageLocationKind.INTERNAL) {
                    return@withContext resetFolderAndMoveCache(onProgress)
                }

                val targetRoot = File(selectedOption.rootPath).canonicalFile
                if (!targetRoot.ensureStorageRoot()) return@withContext StorageFolderUpdateResult.NotWritable
                val movedCache =
                    moveAllCacheDirectories(preferencesSnapshot) { kind ->
                        targetRoot.resolve(kind.defaultDirectoryName)
                    }.withProgress(onProgress)
                if (!movedCache) {
                    return@withContext StorageFolderUpdateResult.NotWritable
                }
                context.dataStore.edit { preferences ->
                    preferences[StorageFolderIdKey] = selectedOption.id
                    preferences[StorageFolderPathKey] = targetRoot.canonicalPath
                    preferences[StorageFolderDisplayNameKey] = selectedOption.id
                    preferences.remove(StorageFolderTreeUriKey)
                }
                context
                    .storageLocationPreferences()
                    .edit()
                    .putString(StorageRootPathMirrorKey, targetRoot.canonicalPath)
                    .commit()
                // A whole-volume choice moves every cache directory, downloads included, so a
                // per-kind folder chosen earlier would still win at read time and send that
                // one kind back to a folder the move just emptied. Clearing it keeps the
                // volume the single source of truth.
                clearPerKindFolderOverrides()
                releasePersistedPermission(previousUri, replacementUri = null)
                StorageRestartScheduler.schedule(context)
                StorageFolderUpdateResult.Success
            }

        suspend fun resetFolderAndMoveCache(onProgress: suspend (StorageMigrationProgress) -> Unit): StorageFolderUpdateResult =
            withContext(Dispatchers.IO) {
                val preferencesSnapshot = context.dataStore.data.first()
                val previousUri = preferencesSnapshot[StorageFolderTreeUriKey]
                val movedCache =
                    moveAllCacheDirectories(preferencesSnapshot) { kind ->
                        context.defaultCacheDirectory(kind)
                    }.withProgress(onProgress)
                if (!movedCache) {
                    return@withContext StorageFolderUpdateResult.NotWritable
                }
                context.dataStore.edit { preferences ->
                    preferences.remove(StorageFolderIdKey)
                    preferences.remove(StorageFolderTreeUriKey)
                    preferences.remove(StorageFolderPathKey)
                    preferences.remove(StorageFolderDisplayNameKey)
                }
                context
                    .storageLocationPreferences()
                    .edit()
                    .remove(StorageRootPathMirrorKey)
                    .commit()
                clearPerKindFolderOverrides()
                releasePersistedPermission(previousUri, replacementUri = null)
                StorageRestartScheduler.schedule(context)
                StorageFolderUpdateResult.Success
            }

        /**
         * Drops the per-kind custom folder overrides, in both the DataStore and the mirror
         * that [resolveCacheDirectory] actually reads at startup. Used by the whole-volume
         * flows, where the chosen volume governs every folder kind.
         */
        private suspend fun clearPerKindFolderOverrides() {
            context.dataStore.edit { preferences ->
                preferences.remove(DownloadsStoragePathKey)
                preferences.remove(DownloadsStorageDisplayNameKey)
                preferences.remove(SongCacheStoragePathKey)
                preferences.remove(SongCacheStorageDisplayNameKey)
            }
            context
                .storageLocationPreferences()
                .edit()
                .remove(DownloadsStoragePathMirrorKey)
                .remove(SongCacheStoragePathMirrorKey)
                .commit()
        }

        private fun Preferences.selectionFor(options: StorageLocationOptions): StorageFolderSelection {
            val configuredId = this[StorageFolderIdKey]?.takeIf(String::isNotBlank)
            val selectedOption =
                options.firstOrNull { option -> option.id == configuredId }
                    ?: options.firstOrNull { option -> option.isSelected }
                    ?: options.firstOrNull { option -> option.kind == StorageLocationKind.INTERNAL }
                    ?: StorageLocationOption(
                        id = InternalStorageOptionId,
                        kind = StorageLocationKind.INTERNAL,
                        volumeLabel = null,
                        rootPath = context.defaultStorageRootDirectory().absolutePath,
                        availableBytes = context.defaultStorageRootDirectory().usableSpace,
                        isSelected = true,
                    )
            return StorageFolderSelection(
                selectedOption = selectedOption,
                options = options,
            )
        }

        private fun releasePersistedPermission(
            previousUri: String?,
            replacementUri: String?,
        ) {
            if (previousUri.isNullOrBlank() || previousUri == replacementUri) return
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    previousUri.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }

        private fun moveAllCacheDirectories(
            preferences: Preferences,
            targetDirectory: (StorageFolderKind) -> File,
        ): StorageMigrationPlan =
            StorageMigrationPlan(
                cacheDirectories =
                    StorageFolderKind.entries
                        .filterNot { kind -> kind == StorageFolderKind.DOWNLOADS }
                        .map { kind ->
                            StorageDirectoryMove(
                                source = activeCacheDirectory(preferences, kind),
                                target = targetDirectory(kind),
                            )
                        },
                downloadDirectories =
                    listOf(
                        StorageDirectoryMove(
                            source = activeCacheDirectory(preferences, StorageFolderKind.DOWNLOADS),
                            target = targetDirectory(StorageFolderKind.DOWNLOADS),
                        ),
                    ),
                cacheFiles =
                    listOf(
                        StorageFileMove(
                            source = context.filesDir.resolve(CanvasArtworkCacheFileName),
                            target = targetDirectory(StorageFolderKind.CANVAS_CACHE).resolve(CanvasArtworkCacheFileName),
                        ),
                        StorageFileMove(
                            source = context.filesDir.resolve(SavedArtworkCacheFileName),
                            target = targetDirectory(StorageFolderKind.ARTWORK_CACHE).resolve(SavedArtworkCacheFileName),
                        ),
                    ),
            )

        private fun activeCacheDirectory(
            preferences: Preferences,
            kind: StorageFolderKind,
        ): File = resolveCacheDirectory(context, kind, preferences)

        private fun defaultDirectoryForKind(
            preferences: Preferences,
            kind: StorageFolderKind,
        ): File {
            val configuredPath = preferences[StorageFolderPathKey]?.takeIf(String::isNotBlank)
            val configuredDirectory = configuredPath?.let(::File)?.resolve(kind.defaultDirectoryName)
            return configuredDirectory?.takeIf { it.ensureWritableDirectory() }
                ?: context.defaultCacheDirectory(kind)
        }

        private fun releaseCachesForMigration() {
            runCatching { playerCache.release() }
            runCatching { downloadCache.release() }
        }

        private suspend fun clearReleasedMediaCache(
            cache: Cache,
            folderKind: StorageFolderKind,
            onProgress: suspend (StorageCacheClearProgress) -> Unit,
        ): Boolean {
            runCatching {
                cache.keys.toList().forEach(cache::removeResource)
            }
            val released = runCatching { cache.release() }.isSuccess
            return released && clearCacheDirectory(folderKind, onProgress)
        }

        private suspend fun clearDownloads(onProgress: suspend (StorageCacheClearProgress) -> Unit): Boolean =
            runCatching {
                // The file downloads are cleared first, and they are the point of the
                // button. They exist as ordinary files this app wrote, so Media3's manager
                // knows nothing about them: clearing the manager alone left every
                // downloaded song on disk, still listed as downloaded and still playable
                // offline, while the user was told the downloads had been cleared.
                downloadUtil.removeAllDownloads()
                // Then Media3's own records, which are the other shape a download can have
                // (an older cache-pinned one).
                downloadUtil.downloadManager.removeAllDownloads()
                onProgress(StorageCacheClearProgress(kind = StorageCacheKind.DOWNLOADS, percent = 100))
            }.isSuccess

        private suspend fun clearCanvasCache(onProgress: suspend (StorageCacheClearProgress) -> Unit): Boolean {
            val memoryAndIndexCleared = CanvasArtworkPlaybackCache.clearAndPersist()
            return memoryAndIndexCleared && clearCacheDirectory(StorageFolderKind.CANVAS_CACHE, onProgress)
        }

        private suspend fun clearImageCache(onProgress: suspend (StorageCacheClearProgress) -> Unit): Boolean {
            val imageLoader = context.imageLoader
            val diskCache = imageLoader.diskCache
            val diskCacheCleared =
                if (diskCache == null) {
                    clearCacheDirectory(
                        kind = StorageFolderKind.IMAGE_CACHE,
                        onProgress = onProgress,
                        minPercent = 0,
                        maxPercent = 70,
                    )
                } else {
                    runCatching {
                        imageLoader.memoryCache?.clear()
                        diskCache.clear()
                        diskCache.size == 0L
                    }.getOrDefault(false)
                }
            onProgress(StorageCacheClearProgress(kind = StorageCacheKind.IMAGES, percent = 70))
            val artworkCacheCleared =
                clearCacheDirectory(
                    kind = StorageFolderKind.ARTWORK_CACHE,
                    onProgress = onProgress,
                    minPercent = 70,
                    maxPercent = 90,
                )
            val artworkCleared = ArtworkStorage.clear(context)
            onProgress(StorageCacheClearProgress(kind = StorageCacheKind.IMAGES, percent = 95))
            val cacheCleared = diskCacheCleared && artworkCacheCleared && artworkCleared
            val contributorCacheCleared = if (cacheCleared) clearGitHubContributorCache() else false
            return cacheCleared && contributorCacheCleared
        }

        private suspend fun clearGitHubContributorCache(): Boolean =
            runCatching {
                context.dataStore.edit { preferences ->
                    preferences.remove(GitHubContributorsEtagKey)
                    preferences.remove(GitHubContributorsJsonKey)
                    preferences.remove(GitHubContributorsLastCheckedAtKey)
                    preferences.remove(GitHubTranslationContributorsJsonKey)
                    preferences.remove(GitHubTranslationContributorsLastCheckedAtKey)
                }
            }.isSuccess

        private suspend fun clearCacheDirectory(
            kind: StorageFolderKind,
            onProgress: suspend (StorageCacheClearProgress) -> Unit,
            minPercent: Int = 0,
            maxPercent: Int = 100,
        ): Boolean {
            val directory = cacheDirectory(context, kind)
            val parent = directory.parentFile ?: return false
            val trashDirectory = parent.resolve("${directory.name}.delete-${System.currentTimeMillis()}")
            val cleared =
                runCatching {
                    if (!directory.exists()) return@runCatching true
                    if (directory.renameTo(trashDirectory)) {
                        trashDirectory.deleteTreeSafely(
                            deleteRoot = true,
                            progressReporter =
                                StorageCacheClearReporter(
                                    kind = kind.toCacheKind(),
                                    totalWorkUnits = trashDirectory.deletionWorkUnits(),
                                    minPercent = minPercent,
                                    maxPercent = maxPercent,
                                    onProgress = onProgress,
                                ),
                        )
                    } else {
                        directory.deleteTreeSafely(
                            deleteRoot = false,
                            progressReporter =
                                StorageCacheClearReporter(
                                    kind = kind.toCacheKind(),
                                    totalWorkUnits = directory.deletionWorkUnits(),
                                    minPercent = minPercent,
                                    maxPercent = maxPercent,
                                    onProgress = onProgress,
                                ),
                        )
                    }
                }.getOrDefault(false)
            return cleared && directory.ensureWritableDirectory() && directory.isDirectoryEmpty()
        }

        private suspend fun StorageMigrationPlan.withProgress(onProgress: suspend (StorageMigrationProgress) -> Unit): Boolean =
            try {
                releaseCachesForMigration()
                moveMigrationPhase(
                    phase = StorageMigrationPhase.CACHE,
                    directories = cacheDirectories,
                    files = cacheFiles,
                    onProgress = onProgress,
                )
                moveMigrationPhase(
                    phase = StorageMigrationPhase.DOWNLOADS,
                    directories = downloadDirectories,
                    files = emptyList(),
                    onProgress = onProgress,
                )
                true
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                false
            }

        private suspend fun moveMigrationPhase(
            phase: StorageMigrationPhase,
            directories: List<StorageDirectoryMove>,
            files: List<StorageFileMove>,
            onProgress: suspend (StorageMigrationProgress) -> Unit,
        ) {
            // A plan carries both phases and usually has work in only one of them. Reporting
            // the empty one reported 0 of 0 bytes as "100%" under its own label, so changing
            // the downloads folder announced "Moving Cache...100%" and left it on screen for
            // as long as the downloads themselves took.
            if (directories.isEmpty() && files.isEmpty()) return
            val totalBytes =
                directories.sumOf { move -> move.source.migrationByteCount(move.target) } +
                    files.sumOf { move -> move.source.migrationByteCount(move.target) }
            val progressReporter =
                StorageProgressReporter(
                    phase = phase,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                )
            progressReporter.emit(0L)
            val buffer = ByteArray(StorageCopyBufferSizeBytes)
            var movedBytes = 0L
            directories.forEach { move ->
                movedBytes =
                    moveCacheDirectory(
                        source = move.source,
                        target = move.target,
                        replaceTarget = move.replaceTarget,
                        movedBytes = movedBytes,
                        progressReporter = progressReporter,
                        buffer = buffer,
                    )
            }
            files.forEach { move ->
                movedBytes =
                    moveFile(
                        source = move.source,
                        target = move.target,
                        movedBytes = movedBytes,
                        progressReporter = progressReporter,
                        buffer = buffer,
                    )
            }
            progressReporter.emit(totalBytes)
        }

        private suspend fun moveCacheDirectory(
            source: File,
            target: File,
            replaceTarget: Boolean,
            movedBytes: Long,
            progressReporter: StorageProgressReporter,
            buffer: ByteArray,
        ): Long {
            val canonicalSource = source.canonicalFile
            val canonicalTarget = target.canonicalFile
            var currentMovedBytes = movedBytes
            if (canonicalSource == canonicalTarget) {
                canonicalTarget.ensureWritableDirectory()
                return currentMovedBytes
            }
            if (!canonicalSource.exists()) {
                canonicalTarget.ensureWritableDirectory()
                return currentMovedBytes
            }
            if (replaceTarget) canonicalTarget.deleteRecursively()
            canonicalTarget.parentFile?.mkdirs()
            canonicalSource
                .walkTopDown()
                .filter { file -> file.isDirectory }
                .forEach { directory ->
                    canonicalTarget
                        .resolve(directory.relativeTo(canonicalSource).path)
                        .mkdirs()
                }
            canonicalSource
                .walkTopDown()
                .filter { file -> file.isFile }
                .forEach { file ->
                    currentMovedBytes =
                        copyFileWithProgress(
                            source = file,
                            target = canonicalTarget.resolve(file.relativeTo(canonicalSource).path),
                            movedBytes = currentMovedBytes,
                            progressReporter = progressReporter,
                            buffer = buffer,
                        )
                }
            canonicalSource.deleteRecursively()
            return currentMovedBytes
        }

        private suspend fun moveFile(
            source: File,
            target: File,
            movedBytes: Long,
            progressReporter: StorageProgressReporter,
            buffer: ByteArray,
        ): Long {
            val canonicalSource = source.canonicalFile
            val canonicalTarget = target.canonicalFile
            if (canonicalSource == canonicalTarget || !canonicalSource.exists()) return movedBytes
            val currentMovedBytes =
                copyFileWithProgress(
                    source = canonicalSource,
                    target = canonicalTarget,
                    movedBytes = movedBytes,
                    progressReporter = progressReporter,
                    buffer = buffer,
                )
            canonicalSource.delete()
            return currentMovedBytes
        }

        companion object {
            fun cacheDirectory(
                context: Context,
                kind: StorageFolderKind,
            ): File = resolveCacheDirectory(context, kind)

            /**
             * Where Media3's download cache lives.
             *
             * Deliberately *inside the cache folder*, never in the downloads folder. That
             * cache stores bytes as 5 MiB fragments named after a numeric uid
             * (`15.10485760....v3.exo`) plus a `.uid` identity file, so pointing it at the
             * downloads folder is what filled a folder meant for the user's music with cache
             * bookkeeping - and it tied the two settings together, so moving one appeared to
             * drag the other along.
             *
             * It is a subdirectory rather than the cache folder itself because Media3 refuses
             * to open two caches on one directory, and the player's streaming cache already
             * owns the folder root.
             */
            fun downloadCacheDirectory(context: Context): File =
                resolveCacheDirectory(context, StorageFolderKind.SONG_CACHE)
                    .resolve(DOWNLOAD_CACHE_DIRECTORY_NAME)

            /**
             * Where Media3's streaming cache lives: a *subdirectory* of the song cache.
             *
             * It used to be the song-cache folder itself, and that made the player cache the
             * authority over files that are not its own. `SimpleCache` deletes files it does not
             * recognise anywhere under its directory, and this root also holds Hush's SpotiFLAC
             * playback files and the download cache.
             *
             * Measured on a force-stop and cold start with a marker file planted beside them:
             * every file in `spotiflac-playback/` was gone afterwards - the marker included - 
             * while the directories stayed, and the engine's own index then reported
             * `ISRCIndex ... 0 files` for that folder. So a track that had just been resolved and
             * cached was missing on the next launch and was downloaded from the providers all over
             * again, however recently it had been played. The same sweep can delete the download
             * cache's own `.exo` spans, which are files it has no entry for.
             *
             * A subfolder rather than the root, for the same reason Media3 refuses two caches on
             * one directory: one cache owns one tree.
             */
            fun playerCacheDirectory(context: Context): File =
                resolveCacheDirectory(context, StorageFolderKind.SONG_CACHE)
                    .resolve(PLAYER_CACHE_DIRECTORY_NAME)

            private const val PLAYER_CACHE_DIRECTORY_NAME = "player-cache"

            private const val DOWNLOAD_CACHE_DIRECTORY_NAME = "download-cache"

            fun resolveCacheDirectory(
                context: Context,
                kind: StorageFolderKind,
                preferences: Preferences? = null,
            ): File {
                val perKindPath =
                    when (kind) {
                        StorageFolderKind.DOWNLOADS ->
                            context
                                .storageLocationPreferences()
                                .getString(DownloadsStoragePathMirrorKey, null)
                                ?.takeIf(String::isNotBlank)
                                ?: PreferenceStore.get(DownloadsStoragePathKey)?.takeIf(String::isNotBlank)

                        StorageFolderKind.SONG_CACHE ->
                            context
                                .storageLocationPreferences()
                                .getString(SongCacheStoragePathMirrorKey, null)
                                ?.takeIf(String::isNotBlank)
                                ?: PreferenceStore.get(SongCacheStoragePathKey)?.takeIf(String::isNotBlank)

                        else -> null
                    }

                perKindPath?.let { path ->
                    context.allowedCustomStorageDirectory(path)?.let { directory ->
                        if (directory.ensureWritableDirectory()) return directory
                    }
                    // A saved location that is no longer usable (the card was removed, or the
                    // app lost access to it) previously fell back to the default in silence,
                    // which is indistinguishable from the setting never having been saved.
                    Timber.tag("StorageLocation").w(
                        "Configured %s location %s is not usable; falling back to the default",
                        kind.name,
                        path,
                    )
                }

                val configuredPath =
                    preferences?.get(StorageFolderPathKey)?.takeIf(String::isNotBlank)
                        ?: context
                            .storageLocationPreferences()
                            .getString(StorageRootPathMirrorKey, null)
                            ?.takeIf(String::isNotBlank)
                        ?: PreferenceStore.get(StorageFolderPathKey)?.takeIf(String::isNotBlank)
                val configuredRootDirectory =
                    configuredPath?.let { path ->
                        context.allowedConfiguredStorageRoot(path)
                    }
                val configuredDirectory = configuredRootDirectory?.resolve(kind.defaultDirectoryName)
                return configuredDirectory
                    ?.takeIf { it.ensureWritableDirectory() }
                    ?: context.defaultCacheDirectory(kind)
            }

            fun cacheFile(
                context: Context,
                kind: StorageFolderKind,
                fileName: String,
            ): File = cacheDirectory(context, kind).resolve(fileName)

            /**
             * The folder a kind is pointed at for a move, even when this app cannot enter it yet.
             *
             * [cacheDirectory] answers where files go *now* and quietly falls back to the app's
             * own directory when the configured one cannot be written. That is the right answer
             * for opening a cache and the wrong one for a move: a move has to stay pointed at the
             * folder the user chose, so it lands there the moment the access that was missing is
             * granted - rather than being carried into the fallback and reported as finished.
             */
            fun moveTargetDirectory(
                context: Context,
                kind: StorageFolderKind,
            ): File =
                currentCustomFolderPath(context, kind)
                    ?.let { path -> runCatching { File(path).canonicalFile }.getOrNull() }
                    ?: cacheDirectory(context, kind)

            /**
             * Whether this app holds Android's All files access.
             *
             * Below Android 11 raw file access to shared storage needs no special access, so the
             * answer is yes there - the switch the user is sent to does not exist before it.
             */
            fun hasAllFilesAccess(context: Context): Boolean = context.hasAllFilesAccess()

            /**
             * The folder a kind is pointed at, when it exists and is unusable only because the
             * platform is refusing shared storage to this app. Null in every other case.
             *
             * This is what lets the storage screen say "that folder needs All files access"
             * about a location saved before this existed, rather than showing a folder that
             * silently is not the one being used.
             */
            fun configuredFolderNeedingAllFilesAccess(
                context: Context,
                kind: StorageFolderKind,
            ): File? =
                (folderHealth(context, kind) as? StorageFolderHealth.NeedsAllFilesAccess)
                    ?.let { health -> File(health.path) }

            /**
             * How usable the folder [kind] is pointed at is, right now.
             *
             * The folder that counts is the one the user *chose*, which is not always the one
             * being written to: [resolveCacheDirectory] falls back to the app's own directory when
             * the chosen folder cannot be entered, so a missing or unwritable folder is otherwise
             * invisible - files simply start landing somewhere else. Reading the choice directly is
             * what lets this notice that. Nothing is created here: a chosen folder that is not
             * there is reported as gone rather than quietly made again, because the downloads it
             * held are gone with it and recreating an empty folder would hide that.
             */
            fun folderHealth(
                context: Context,
                kind: StorageFolderKind,
            ): StorageFolderHealth {
                val configuredPath = configuredFolderPath(context, kind)
                val directory =
                    configuredPath?.let { path -> runCatching { File(path).canonicalFile }.getOrNull() }
                val exists = directory?.exists() == true
                val isDirectory = exists && directory?.isDirectory == true
                val writable = isDirectory && directory?.ensureWritableDirectory() == true
                return classifyStorageFolderHealth(
                    configuredPath = directory?.path ?: configuredPath,
                    exists = exists,
                    isDirectory = isDirectory,
                    writable = writable,
                    // Only asked when the probe failed: it is the question that turns "cannot
                    // write" into "and here is the one thing that fixes it" versus "and no access
                    // switch can".
                    needsAllFilesAccess =
                        isDirectory && !writable && directory?.requiresAllFilesAccess(context) == true,
                )
            }

            /**
             * The folder the user chose for [kind], or null when the app's own directory is in use.
             *
             * Two settings can point a kind somewhere - a folder picked for that kind alone, and a
             * whole storage volume under which every kind keeps its own subfolder - and the per-kind
             * choice wins. This reads them in the same order [resolveCacheDirectory] does, from the
             * same places (the synchronous mirror first, then the DataStore cache), so health and
             * the place files actually go can never disagree about which folder was chosen.
             */
            internal fun configuredFolderPath(
                context: Context,
                kind: StorageFolderKind,
            ): String? {
                currentCustomFolderPath(context, kind)
                    ?.takeIf(String::isNotBlank)
                    ?.let { path -> return path }
                val configuredRoot =
                    context
                        .storageLocationPreferences()
                        .getString(StorageRootPathMirrorKey, null)
                        ?.takeIf(String::isNotBlank)
                        ?: PreferenceStore.get(StorageFolderPathKey)?.takeIf(String::isNotBlank)
                return configuredRoot?.let { root -> File(root).resolve(kind.defaultDirectoryName).absolutePath }
            }

            fun currentCustomFolderPath(
                context: Context,
                kind: StorageFolderKind,
            ): String? =
                when (kind) {
                    StorageFolderKind.DOWNLOADS ->
                        context
                            .storageLocationPreferences()
                            .getString(DownloadsStoragePathMirrorKey, null)
                            ?.takeIf(String::isNotBlank)
                            ?: PreferenceStore.get(DownloadsStoragePathKey)?.takeIf(String::isNotBlank)

                    StorageFolderKind.SONG_CACHE ->
                        context
                            .storageLocationPreferences()
                            .getString(SongCacheStoragePathMirrorKey, null)
                            ?.takeIf(String::isNotBlank)
                            ?: PreferenceStore.get(SongCacheStoragePathKey)?.takeIf(String::isNotBlank)

                    else -> null
                }
        }
    }

/**
 * Turns a picked SAF folder back into a real directory, or null when there isn't one.
 *
 * The file-backed media cache writes with plain `File` IO, so a folder has to exist as a
 * path to be usable - which rules out cloud and virtual providers by construction. Only the
 * external-storage provider addresses real paths, and it does so through its document id:
 * `<volumeId>:<relative/path>`, where `primary` is the shared internal storage and any other
 * volume id is a mount name under `/storage`. A tree on anything else has no directory to
 * return, and the caller reports that rather than pretending to have changed location.
 */
internal fun Context.treeUriToDirectory(treeUri: Uri): File? {
    val isExternalStorageProvider =
        treeUri.authority == ExternalStorageDocumentsAuthority
    if (!isExternalStorageProvider) return null
    val documentId =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val volumeId = documentId.substring(0, separator)
    val relativePath = documentId.substring(separator + 1).trim('/')
    val volumeRoot =
        if (volumeId.equals(ExternalStoragePrimaryVolumeId, ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", volumeId)
        }
    val canonicalRoot = runCatching { volumeRoot.canonicalFile }.getOrNull() ?: return null
    val candidate = if (relativePath.isEmpty()) canonicalRoot else canonicalRoot.resolve(relativePath)
    if (!candidate.isDirectory) return null
    return runCatching { candidate.canonicalFile }.getOrNull()
}

private fun Context.allowedCustomStorageDirectory(path: String): File? {
    val configured = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    if (!configured.exists() && !configured.mkdirs()) return null
    // Deliberately not gated on canRead() either. On a folder in shared storage the scoped view
    // answers that question wrongly in both directions - a persisted SAF grant makes a folder
    // readable while access() says no, and a folder this app may not enter can still say yes -
    // so the write probe every caller runs (ensureWritableDirectory) is the only answer trusted.
    if (!configured.isDirectory) return null

    val appInternalRoot = defaultStorageRootDirectory().canonicalFile
    if (configured.path.startsWith(appInternalRoot.path)) return configured

    val allowedExternalRoots =
        getExternalFilesDirs(null)
            .filterNotNull()
            .flatMap { directory ->
                listOf(
                    directory.canonicalFile,
                    directory.resolve(ExternalStorageRootDirectoryName).canonicalFile,
                )
            }

    if (allowedExternalRoots.any { root -> configured.path.startsWith(root.path) }) {
        return configured
    }

    // Deliberately not gated on File.canWrite(). That check is why a folder chosen with the
    // system picker could be selected and then refused at save time with "Hush cannot write
    // cache files to this folder": on Android 11+ access() is answered from the app's
    // scoped-storage view, which does not know about the persisted SAF grant the picker handed
    // back, while the open() this app actually performs is allowed. Every caller gates the
    // result with ensureWritableDirectory(), which writes a probe file and is therefore the
    // authoritative answer - a canWrite() that disagrees with a successful probe is wrong.
    return configured
}

private fun Context.defaultCacheDirectory(kind: StorageFolderKind): File =
    when (kind) {
        StorageFolderKind.IMAGE_CACHE -> cacheDir.resolve(kind.defaultDirectoryName)
        else -> filesDir.resolve(kind.defaultDirectoryName)
    }

private fun Context.defaultStorageRootDirectory(): File = filesDir

private fun Context.restartApp() {
    packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launchIntent)
    }
    kotlin.system.exitProcess(0)
}

private fun Context.storageLocationPreferences() = getSharedPreferences(StorageLocationPreferencesName, Context.MODE_PRIVATE)

private fun Context.allowedConfiguredStorageRoot(path: String): File? {
    val configuredRoot = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    val appRoot = defaultStorageRootDirectory().canonicalFile
    if (configuredRoot == appRoot) return appRoot
    return getExternalFilesDirs(null)
        .filterNotNull()
        .map { directory ->
            directory
                .resolve(ExternalStorageRootDirectoryName)
                .canonicalFile
        }.firstOrNull { root -> root == configuredRoot }
}

private fun Context.storageLocationOptions(preferences: Preferences): StorageLocationOptions {
    val selectedPath = preferences[StorageFolderPathKey]?.takeIf(String::isNotBlank)
    val appRoot = defaultStorageRootDirectory().canonicalFile
    val internalOption =
        appRoot.toStorageLocationOption(
            id = InternalStorageOptionId,
            kind = StorageLocationKind.INTERNAL,
            volumeLabel = null,
            selectedPath = selectedPath,
        )
    val externalOptions =
        getExternalFilesDirs(null)
            .filterNotNull()
            .mapIndexedNotNull { index, directory ->
                if (directory.isPrimaryExternalStorage()) return@mapIndexedNotNull null
                directory
                    .resolve(ExternalStorageRootDirectoryName)
                    .canonicalFile
                    .takeIf { it.ensureWritableDirectory() }
                    ?.toStorageLocationOption(
                        id = ExternalStorageOptionIdPrefix + directory.storageVolumeRootPath().orEmpty().ifBlank { index.toString() },
                        kind = StorageLocationKind.REMOVABLE,
                        volumeLabel = directory.storageVolumeLabel(),
                        selectedPath = selectedPath,
                    )
            }.distinctBy { option -> option.rootPath }
    return StorageLocationOptions(listOf(internalOption) + externalOptions)
}

/**
 * Whether this app holds All files access, the special access Android 11 introduced in place of
 * raw access to shared storage.
 */
internal fun Context.hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/**
 * Whether [this] is a folder the platform is refusing only until All files access is granted.
 *
 * True for a directory in shared storage (Download, Music, the top of the card) while the access
 * is missing, and false for the app's own directories - which never need it - so a failure that
 * no access switch can fix is never answered with a switch that cannot help.
 */
/**
 * The rule that turns what was found about a chosen folder into what the user is told.
 *
 * Split out from the checking so it can be tested directly - the subtle part is which answer wins
 * when several are true at once (a folder that is gone cannot also be unwritable, and a folder this
 * app cannot write in is only "needs access" when a grant would actually change that).
 *
 * A null or blank path means no folder was chosen and the app's own directory is in use, which
 * nothing can go wrong with and which is reported as healthy without touching the disk at all.
 */
internal fun classifyStorageFolderHealth(
    configuredPath: String?,
    exists: Boolean,
    isDirectory: Boolean,
    writable: Boolean,
    needsAllFilesAccess: Boolean,
): StorageFolderHealth {
    if (configuredPath.isNullOrBlank()) return StorageFolderHealth.Healthy
    if (!exists) return StorageFolderHealth.Missing(configuredPath)
    if (!isDirectory) return StorageFolderHealth.NotWritable(configuredPath)
    if (writable) return StorageFolderHealth.Healthy
    return if (needsAllFilesAccess) {
        StorageFolderHealth.NeedsAllFilesAccess(configuredPath)
    } else {
        StorageFolderHealth.NotWritable(configuredPath)
    }
}

internal fun File.requiresAllFilesAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    if (context.hasAllFilesAccess()) return false
    val directory = runCatching { canonicalFile }.getOrNull() ?: return false
    return !context.ownsStorageDirectory(directory)
}

/** Whether [directory] is inside a tree this app owns, where File access never needs a grant. */
private fun Context.ownsStorageDirectory(directory: File): Boolean =
    buildList {
        add(filesDir)
        add(cacheDir)
        add(noBackupFilesDir)
        getExternalFilesDirs(null).filterNotNull().forEach { root -> add(root) }
    }.mapNotNull { root -> runCatching { root.canonicalFile }.getOrNull() }
        .any { root -> directory.isWithin(root) }

/**
 * Whether [this] is [root] itself, or below it.
 *
 * The separator is part of the test because a bare prefix match is wrong in the one place it
 * matters: "/data/files2" starts with "/data/files", and reading that as the app's own tree
 * would silently skip the access a shared folder needs.
 */
internal fun File.isWithin(root: File): Boolean =
    path == root.path || path.startsWith(root.path.trimEnd(File.separatorChar) + File.separatorChar)

internal fun File.ensureWritableDirectory(): Boolean =
    runCatching {
        if (exists() && !isDirectory) return@runCatching false
        if (!exists() && !mkdirs()) return@runCatching false
        val probe = File(this, ".archivetune-storage-probe")
        probe.writeText("ok")
        probe.delete()
    }.isSuccess

private fun File.ensureStorageRoot(): Boolean =
    ensureWritableDirectory() &&
        StorageFolderKind.entries.all { kind ->
            resolve(kind.defaultDirectoryName).ensureWritableDirectory()
        }

private suspend fun copyFileWithProgress(
    source: File,
    target: File,
    movedBytes: Long,
    progressReporter: StorageProgressReporter,
    buffer: ByteArray,
): Long {
    currentCoroutineContext().ensureActive()
    target.parentFile?.mkdirs()
    var currentMovedBytes = movedBytes
    source.inputStream().use { inputStream ->
        target.outputStream().use { outputStream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = inputStream.read(buffer)
                if (read < 0) return@use
                outputStream.write(buffer, 0, read)
                currentMovedBytes += read
                progressReporter.emit(currentMovedBytes)
            }
        }
    }
    return currentMovedBytes
}

private fun File.migrationByteCount(target: File): Long {
    val canonicalSource = canonicalFile
    val canonicalTarget = target.canonicalFile
    if (canonicalSource == canonicalTarget || !canonicalSource.exists()) return 0L
    if (canonicalSource.isFile) return canonicalSource.length().takeIf { !canonicalTarget.exists() } ?: 0L
    return canonicalSource
        .walkTopDown()
        .filter { file -> file.isFile }
        .sumOf { file -> file.length().takeIf { !canonicalTarget.resolve(file.relativeTo(canonicalSource).path).exists() } ?: 0L }
}

private suspend fun File.deleteTreeSafely(
    deleteRoot: Boolean,
    progressReporter: StorageCacheClearReporter,
): Boolean =
    runCatching {
        if (!exists()) return@runCatching true
        val directories = ArrayList<File>()
        val stack = ArrayDeque<File>()
        var deleted = true
        stack.add(this)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val file = stack.removeLast()
            if (file.isDirectory) {
                directories += file
                file.listFiles()?.forEach { child -> stack.add(child) }
            } else {
                val workUnits = file.deleteWorkUnits()
                if (!runCatching { file.delete() || !file.exists() }.getOrDefault(false)) {
                    deleted = false
                }
                progressReporter.emit(workUnits)
            }
        }
        directories.asReversed().forEach { directory ->
            currentCoroutineContext().ensureActive()
            if (deleteRoot || directory != this) {
                if (!runCatching { directory.delete() || !directory.exists() }.getOrDefault(false)) {
                    deleted = false
                }
            }
        }
        progressReporter.emitRemaining()
        deleted
    }.getOrDefault(false)

private fun File.deletionWorkUnits(): Long {
    if (!exists()) return 0L
    val stack = ArrayDeque<File>()
    stack.add(this)
    var totalWorkUnits = 0L
    while (stack.isNotEmpty()) {
        val file = stack.removeLast()
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> stack.add(child) }
        } else {
            totalWorkUnits += file.deleteWorkUnits()
        }
    }
    return totalWorkUnits
}

private fun File.deleteWorkUnits(): Long = length().coerceAtLeast(1L)

private fun File.isDirectoryEmpty(): Boolean =
    runCatching {
        val children = listFiles() ?: return@runCatching false
        children.isEmpty()
    }.getOrDefault(false)

private class StorageProgressReporter(
    private val phase: StorageMigrationPhase,
    private val totalBytes: Long,
    private val onProgress: suspend (StorageMigrationProgress) -> Unit,
) {
    private var lastPercent = -1

    suspend fun emit(movedBytes: Long) {
        currentCoroutineContext().ensureActive()
        val percent =
            if (totalBytes <= 0L) {
                100
            } else {
                ((movedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
            }
        if (percent == lastPercent) return
        lastPercent = percent
        onProgress(StorageMigrationProgress(phase = phase, percent = percent))
    }
}

private class StorageCacheClearReporter(
    private val kind: StorageCacheKind,
    private val totalWorkUnits: Long,
    private val minPercent: Int,
    private val maxPercent: Int,
    private val onProgress: suspend (StorageCacheClearProgress) -> Unit,
) {
    private var completedWorkUnits = 0L
    private var lastPercent = -1

    suspend fun emit(workUnits: Long) {
        currentCoroutineContext().ensureActive()
        completedWorkUnits = (completedWorkUnits + workUnits).coerceAtMost(totalWorkUnits)
        emitPercent(completedWorkUnits)
    }

    suspend fun emitRemaining() {
        currentCoroutineContext().ensureActive()
        emitPercent(totalWorkUnits)
    }

    private suspend fun emitPercent(workUnits: Long) {
        val percent =
            if (totalWorkUnits <= 0L) {
                maxPercent
            } else {
                minPercent + (((workUnits.coerceIn(0L, totalWorkUnits) * (maxPercent - minPercent)) / totalWorkUnits).toInt())
            }.coerceIn(minPercent, maxPercent)
        if (percent == lastPercent) return
        lastPercent = percent
        onProgress(StorageCacheClearProgress(kind = kind, percent = percent))
    }
}

private data class StorageMigrationPlan(
    val cacheDirectories: List<StorageDirectoryMove>,
    val downloadDirectories: List<StorageDirectoryMove>,
    val cacheFiles: List<StorageFileMove>,
)

private data class StorageDirectoryMove(
    val source: File,
    val target: File,
    /**
     * Whether pre-existing content at [target] may be deleted before the move.
     *
     * True for the app-owned targets of the volume-switch flow, where a stale directory
     * should not survive the move. False when [target] is a folder the user picked, because
     * that folder's unrelated files are not ours to delete.
     */
    val replaceTarget: Boolean = true,
)

private data class StorageFileMove(
    val source: File,
    val target: File,
)

private fun StorageFolderKind.toCacheKind(): StorageCacheKind =
    when (this) {
        StorageFolderKind.SONG_CACHE -> StorageCacheKind.SONGS
        StorageFolderKind.DOWNLOADS -> StorageCacheKind.DOWNLOADS
        StorageFolderKind.IMAGE_CACHE,
        StorageFolderKind.ARTWORK_CACHE,
        -> StorageCacheKind.IMAGES

        StorageFolderKind.CANVAS_CACHE -> StorageCacheKind.CANVAS
    }

private fun File.toStorageLocationOption(
    id: String,
    kind: StorageLocationKind,
    volumeLabel: String?,
    selectedPath: String?,
): StorageLocationOption =
    StorageLocationOption(
        id = id,
        kind = kind,
        volumeLabel = volumeLabel,
        rootPath = canonicalPath,
        availableBytes = usableSpace,
        isSelected =
            selectedPath?.let { configuredPath ->
                runCatching { File(configuredPath).canonicalPath == canonicalPath }.getOrDefault(false)
            } ?: (kind == StorageLocationKind.INTERNAL),
    )

private fun File.isPrimaryExternalStorage(): Boolean = storageVolumeRootPath() == "/storage/emulated/0"

private fun File.storageVolumeLabel(): String? =
    storageVolumeRootPath()
        ?.substringAfterLast('/')
        ?.takeIf { label -> label.isNotBlank() && label != "0" }

private fun File.storageVolumeRootPath(): String? {
    val path =
        runCatching { canonicalPath }
            .getOrDefault(absolutePath)
            .replace('\\', '/')
            .trimEnd('/')
    val segments = path.trim('/').split('/')
    return when {
        segments.size >= 3 && segments[0] == "mnt" && segments[1] == "media_rw" -> "/storage/${segments[2]}"
        segments.size >= 3 && segments[0] == "storage" && segments[1] == "emulated" -> "/storage/emulated/${segments[2]}"
        segments.size >= 2 && segments[0] == "storage" && segments[1].isNotBlank() -> "/storage/${segments[1]}"
        else -> null
    }
}

private const val StorageLocationPreferencesName = "storage_locations"
private const val StorageRootPathMirrorKey = "storage_root_path"

/**
 * How often a folder kind is re-checked while something is watching it.
 *
 * Slow on purpose: the check writes a probe file, and a folder does not stop being usable often.
 * Long enough not to matter for the battery, short enough that a card pulled while the app is open
 * is noticed while the user is still looking at it.
 */
private const val StorageFolderHealthCheckIntervalMillis = 15_000L
private const val DownloadsStoragePathMirrorKey = "downloads_storage_path"
private const val SongCacheStoragePathMirrorKey = "song_cache_storage_path"
private const val InternalStorageOptionId = "internal"
private const val ExternalStorageOptionIdPrefix = "external:"
private const val ExternalStorageRootDirectoryName = "Hush"

/** The one document provider whose tree ids address real filesystem paths. */
private const val ExternalStorageDocumentsAuthority = "com.android.externalstorage.documents"

/** Volume id the external-storage provider uses for shared internal storage. */
private const val ExternalStoragePrimaryVolumeId = "primary"
private const val AppRestartDelayMillis = 3_000L
private const val StorageCopyBufferSizeBytes = 64 * 1024
private const val CanvasArtworkCacheFileName = "canvas_artwork_cache.json"
private const val SavedArtworkCacheFileName = "archivetune_saved_artworks.json"
