/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.storage

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import app.hush.music.extensions.directorySizeBytes
import java.io.File

data class LegacyMusicApp(
    val id: String,
    val displayName: String,
    val packageNames: List<String>,
    val externalRootNames: List<String>,
) {
    val primaryPackageName: String
        get() = packageNames.first()
}

enum class LegacyStorageAccess {
    /** Folder is readable and contains media/cache files. */
    READABLE,
    /** Folder exists but Android blocks cross-app reads (common on Android 11+). */
    BLOCKED,
}

data class LegacyStorageCandidate(
    val app: LegacyMusicApp,
    val downloadDir: File,
    val streamCacheDir: File?,
    val exoPlayerDatabase: File?,
    val totalBytes: Long,
    val isWritable: Boolean,
    val access: LegacyStorageAccess = LegacyStorageAccess.READABLE,
) {
    val summaryPath: String
        get() = downloadDir.parentFile?.name?.takeIf { it.isNotBlank() } ?: downloadDir.name

    val canImport: Boolean
        get() = access == LegacyStorageAccess.READABLE && totalBytes > 0L
}

/** Installed fork app whose downloads are not reachable without user action. */
data class InstalledLegacyAppHint(
    val app: LegacyMusicApp,
    val typicalPaths: List<String>,
)

data class LegacyStorageScanResult(
    val importableCandidates: List<LegacyStorageCandidate>,
    val installedHints: List<InstalledLegacyAppHint>,
    val hasAllFilesAccess: Boolean,
)

object LegacyStorageCompatibility {
    private val KNOWN_APPS =
        listOf(
            LegacyMusicApp(
                id = "hush",
                displayName = "Hush",
                packageNames = listOf("app.hush.music"),
                externalRootNames = listOf("", "Hush", "ArchiveTune"),
            ),
            LegacyMusicApp(
                id = "archivetune",
                displayName = "ArchiveTune",
                packageNames = listOf("moe.rukamori.archivetune"),
                externalRootNames = listOf("", "ArchiveTune", "Hush"),
            ),
            LegacyMusicApp(
                id = "outertune",
                displayName = "OuterTune",
                packageNames = listOf("com.dd3boh.outertune"),
                externalRootNames = listOf("", "OuterTune", "Hush"),
            ),
            LegacyMusicApp(
                id = "metrolist",
                displayName = "Metrolist",
                packageNames = listOf("com.metrolist.music"),
                externalRootNames = listOf("", "Metrolist", "Hush"),
            ),
            LegacyMusicApp(
                id = "innertune",
                displayName = "InnerTune",
                packageNames = listOf("com.zionhuang.music"),
                externalRootNames = listOf("", "InnerTune"),
            ),
            LegacyMusicApp(
                id = "vimusic",
                displayName = "ViMusic",
                packageNames = listOf("it.vfsfitvnm.vimusic"),
                externalRootNames = listOf("", "ViMusic"),
            ),
            LegacyMusicApp(
                id = "vivi",
                displayName = "ViVi Music",
                packageNames = listOf("com.vivi.vivimusic"),
                externalRootNames = listOf("", "ViVi", "Hush"),
            ),
            LegacyMusicApp(
                id = "echo",
                displayName = "Echo Music",
                packageNames = listOf("iad1tya.echo.music", "com.echo.music"),
                externalRootNames = listOf("", "Echo", "Hush"),
            ),
            LegacyMusicApp(
                id = "opentune",
                displayName = "OpenTune",
                packageNames = listOf("com.arturo254.opentune"),
                externalRootNames = listOf("", "OpenTune", "Hush"),
            ),
        )

    fun probeLegacyStorages(context: Context): List<LegacyStorageCandidate> =
        scan(context).importableCandidates

    fun scan(context: Context): LegacyStorageScanResult {
        val hasAllFilesAccess = hasAllFilesAccess()
        val hushDownloadDir =
            StorageLocationRepository
                .cacheDirectory(context, StorageFolderKind.DOWNLOADS)
                .canonicalFile
        val candidates = mutableListOf<LegacyStorageCandidate>()
        val volumeRoots = storageVolumeRoots(context)

        KNOWN_APPS
            .filter { it.id != "hush" }
            .forEach { app ->
                if (!isAppInstalled(context, app)) return@forEach

                collectExternalCandidates(
                    app = app,
                    volumeRoots = volumeRoots,
                    hushDownloadDir = hushDownloadDir,
                    candidates = candidates,
                )

                if (hasAllFilesAccess) {
                    collectInternalCandidates(
                        context = context,
                        app = app,
                        hushDownloadDir = hushDownloadDir,
                        candidates = candidates,
                    )
                }
            }

        val importable =
            candidates
                .filter { candidate -> candidate.canImport }
                .distinctBy { candidate -> candidate.downloadDir.canonicalPath }
                .sortedByDescending { candidate -> candidate.totalBytes }

        val coveredPackages =
            importable
                .flatMap { candidate -> candidate.app.packageNames }
                .toSet()
        val installedHints =
            detectInstalledApps(context)
                .filter { hint ->
                    hint.app.packageNames.none { packageName -> packageName in coveredPackages }
                }

        return LegacyStorageScanResult(
            importableCandidates = importable,
            installedHints = installedHints,
            hasAllFilesAccess = hasAllFilesAccess,
        )
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun isPackageInstalled(
        context: Context,
        packageName: String,
    ): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)

    fun isAppInstalled(
        context: Context,
        app: LegacyMusicApp,
    ): Boolean = app.packageNames.any { packageName -> isPackageInstalled(context, packageName) }

    fun mergeExoPlayerDatabase(
        sourceDb: File,
        targetDb: File,
    ): Boolean {
        if (!sourceDb.exists() || sourceDb.length() <= 0L) return false
        return runCatching {
            targetDb.parentFile?.mkdirs()
            if (!targetDb.exists() || targetDb.length() <= 0L) {
                sourceDb.copyTo(targetDb, overwrite = true)
                return@runCatching true
            }
            val database =
                SQLiteDatabase.openDatabase(
                    targetDb.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
            database.use { db ->
                val escapedPath = sourceDb.absolutePath.replace("'", "''")
                db.execSQL("ATTACH DATABASE '$escapedPath' AS legacy")
                try {
                    db.execSQL(
                        "INSERT OR IGNORE INTO ExoPlayerCacheIndex " +
                            "SELECT * FROM legacy.ExoPlayerCacheIndex",
                    )
                    db.execSQL(
                        "INSERT OR IGNORE INTO ExoPlayerCacheContentMetadata " +
                            "SELECT * FROM legacy.ExoPlayerCacheContentMetadata",
                    )
                } finally {
                    db.execSQL("DETACH DATABASE legacy")
                }
            }
            true
        }.getOrDefault(false)
    }

    fun resolveExoPlayerDatabase(context: Context): File = context.getDatabasePath("exoplayer_internal.db")

    private fun collectInternalCandidates(
        context: Context,
        app: LegacyMusicApp,
        hushDownloadDir: File,
        candidates: MutableList<LegacyStorageCandidate>,
    ) {
        val packageName = resolveInstalledPackageName(context, app) ?: return
        val internalFilesRoots =
            listOf(
                File("/data/user/0/$packageName/files"),
                File("/data/data/$packageName/files"),
            ).distinctBy { it.path }

        internalFilesRoots.forEach { filesRoot ->
            buildInternalProbeTargets(app, filesRoot).forEach { target ->
                collectCandidate(
                    app = app,
                    downloadDir = target.downloadDir,
                    streamCacheDir = target.streamCacheDir,
                    exoPlayerDatabase = target.exoPlayerDatabase,
                    hushDownloadDir = hushDownloadDir,
                    candidates = candidates,
                )
            }
        }

        val internalDatabase = File("/data/user/0/$packageName/databases/exoplayer_internal.db")
        if (internalDatabase.exists() && internalDatabase.isFile && internalDatabase.length() > 0L) {
            val downloadDir = File("/data/user/0/$packageName/files/download")
            collectCandidate(
                app = app,
                downloadDir = downloadDir,
                streamCacheDir = File("/data/user/0/$packageName/files/exoplayer"),
                exoPlayerDatabase = internalDatabase,
                hushDownloadDir = hushDownloadDir,
                candidates = candidates,
            )
        }
    }

    private fun buildInternalProbeTargets(
        app: LegacyMusicApp,
        filesRoot: File,
    ): List<ProbeTarget> {
        val storageRoots =
            buildList {
                add(filesRoot)
                app.externalRootNames
                    .filter { it.isNotBlank() }
                    .forEach { brand -> add(filesRoot.resolve(brand)) }
            }.distinctBy { it.path }

        return storageRoots.flatMap { root ->
            listOf(
                ProbeTarget(
                    downloadDir = root.resolve("download"),
                    streamCacheDir = root.resolve("exoplayer"),
                    exoPlayerDatabase = root.resolve("exoplayer_internal.db"),
                ),
                ProbeTarget(
                    downloadDir = root.resolve("downloads"),
                    streamCacheDir = root.resolve("exoplayer"),
                    exoPlayerDatabase = root.resolve("exoplayer_internal.db"),
                ),
                ProbeTarget(
                    downloadDir = root.resolve("media"),
                    streamCacheDir = root.resolve("exoplayer"),
                    exoPlayerDatabase = root.resolve("exoplayer_internal.db"),
                ),
            )
        }
    }

    private fun collectExternalCandidates(
        app: LegacyMusicApp,
        volumeRoots: Set<String>,
        hushDownloadDir: File,
        candidates: MutableList<LegacyStorageCandidate>,
    ) {
        val probeTargets = mutableListOf<ProbeTarget>()
        volumeRoots.forEach { volumeRoot ->
            app.packageNames.forEach { packageName ->
                probeTargets += buildExternalProbeTargets(app, volumeRoot, packageName)
            }
        }

        probeTargets
            .distinctBy { target -> target.key }
            .forEach { target ->
                collectCandidate(
                    app = app,
                    downloadDir = target.downloadDir,
                    streamCacheDir = target.streamCacheDir,
                    exoPlayerDatabase = target.exoPlayerDatabase,
                    hushDownloadDir = hushDownloadDir,
                    candidates = candidates,
                )
            }
    }

    private fun buildExternalProbeTargets(
        app: LegacyMusicApp,
        volumeRoot: String,
        packageName: String,
    ): List<ProbeTarget> {
        val filesBase = File(volumeRoot, "Android/data/$packageName/files")
        val storageRoots =
            buildList {
                add(filesBase)
                app.externalRootNames
                    .filter { it.isNotBlank() }
                    .forEach { brand -> add(filesBase.resolve(brand)) }
            }.distinctBy { it.path }

        return storageRoots.flatMap { root ->
            listOf(
                ProbeTarget(
                    downloadDir = root.resolve("download"),
                    streamCacheDir = root.resolve("exoplayer"),
                    exoPlayerDatabase = root.resolve("exoplayer_internal.db"),
                ),
                ProbeTarget(
                    downloadDir = root.resolve("downloads"),
                    streamCacheDir = root.resolve("exoplayer"),
                    exoPlayerDatabase = root.resolve("exoplayer_internal.db"),
                ),
                ProbeTarget(
                    downloadDir = root.resolve("media"),
                    streamCacheDir = root.resolve("exoplayer"),
                    exoPlayerDatabase = root.resolve("exoplayer_internal.db"),
                ),
            )
        }
    }

    private fun detectInstalledApps(context: Context): List<InstalledLegacyAppHint> =
        KNOWN_APPS
            .filter { it.id != "hush" }
            .mapNotNull { app ->
                if (!isAppInstalled(context, app)) return@mapNotNull null
                InstalledLegacyAppHint(
                    app = app,
                    typicalPaths = typicalStoragePaths(app),
                )
            }

    private fun resolveInstalledPackageName(
        context: Context,
        app: LegacyMusicApp,
    ): String? =
        app.packageNames.firstOrNull { packageName -> isPackageInstalled(context, packageName) }

    private fun typicalStoragePaths(app: LegacyMusicApp): List<String> {
        val packageName = app.primaryPackageName
        val internalBase = "/data/user/0/$packageName/files"
        val externalBase = "/storage/emulated/0/Android/data/$packageName/files"
        val paths = linkedSetOf<String>()
        paths += "$internalBase/download"
        paths += "$internalBase/exoplayer"
        paths += "$externalBase/download"
        paths += "$externalBase/exoplayer"
        app.externalRootNames.filter { it.isNotBlank() }.forEach { brand ->
            paths += "$internalBase/$brand/download"
            paths += "$externalBase/$brand/download"
            paths += "$externalBase/$brand/exoplayer"
            paths += "$externalBase/media"
        }
        if (app.id == "innertune") {
            paths += "$externalBase/media"
            paths += "$internalBase/media"
        }
        return paths.toList()
    }

    private fun collectCandidate(
        app: LegacyMusicApp,
        downloadDir: File,
        streamCacheDir: File?,
        exoPlayerDatabase: File?,
        hushDownloadDir: File,
        candidates: MutableList<LegacyStorageCandidate>,
    ) {
        val downloadProbe = probeDirectory(downloadDir)
        val streamProbe = streamCacheDir?.let(::probeDirectory) ?: DirectoryProbe.NotFound
        if (downloadProbe == DirectoryProbe.NotFound && streamProbe == DirectoryProbe.NotFound) return

        val access =
            when {
                downloadProbe == DirectoryProbe.ReadableWithContent ||
                    streamProbe == DirectoryProbe.ReadableWithContent -> LegacyStorageAccess.READABLE

                downloadProbe == DirectoryProbe.Blocked ||
                    streamProbe == DirectoryProbe.Blocked -> LegacyStorageAccess.BLOCKED

                else -> return
            }

        val canonicalDownload =
            when {
                downloadProbe == DirectoryProbe.ReadableWithContent -> downloadDir.canonicalOrSelf()
                streamProbe == DirectoryProbe.ReadableWithContent -> streamCacheDir!!.canonicalOrSelf()
                downloadProbe == DirectoryProbe.Blocked -> downloadDir
                streamProbe == DirectoryProbe.Blocked -> streamCacheDir!!
                else -> return
            }

        if (canonicalDownload.canonicalOrSelf() == hushDownloadDir) return

        val streamDir =
            streamCacheDir
                ?.takeIf { streamProbe == DirectoryProbe.ReadableWithContent }
                ?.canonicalOrSelf()
        val databaseFile =
            exoPlayerDatabase?.takeIf { file -> file.exists() && file.isFile && file.length() > 0L }
                ?: resolveLegacyDatabaseNear(canonicalDownload)

        val totalBytes =
            when (access) {
                LegacyStorageAccess.READABLE -> {
                    val downloadBytes =
                        if (downloadProbe == DirectoryProbe.ReadableWithContent) {
                            canonicalDownload.directorySizeBytes()
                        } else {
                            0L
                        }
                    val streamBytes = streamDir?.directorySizeBytes() ?: 0L
                    val databaseBytes = databaseFile?.length() ?: 0L
                    downloadBytes + streamBytes + databaseBytes
                }

                LegacyStorageAccess.BLOCKED -> 0L
            }

        if (access == LegacyStorageAccess.READABLE && totalBytes <= 0L) return

        candidates +=
            LegacyStorageCandidate(
                app = app,
                downloadDir = canonicalDownload,
                streamCacheDir = streamDir,
                exoPlayerDatabase = databaseFile,
                totalBytes = totalBytes,
                isWritable = canonicalDownload.canWrite(),
                access = access,
            )
    }

    private enum class DirectoryProbe {
        NotFound,
        Blocked,
        ReadableWithContent,
    }

    private fun probeDirectory(directory: File): DirectoryProbe {
        if (!directory.exists() || !directory.isDirectory) return DirectoryProbe.NotFound
        val children = directory.listFiles()
        return when {
            children == null -> DirectoryProbe.Blocked
            children.any { child -> child.isFile && child.length() > 0L } -> DirectoryProbe.ReadableWithContent
            children.any { child -> child.isDirectory && hasCacheContent(child) } ->
                DirectoryProbe.ReadableWithContent

            directory.canRead() -> DirectoryProbe.NotFound
            else -> DirectoryProbe.Blocked
        }
    }

    private fun resolveLegacyDatabaseNear(downloadDir: File): File? {
        val parent = downloadDir.parentFile ?: return null
        return listOf(
            parent.resolve("exoplayer_internal.db"),
            parent.resolve("../databases/exoplayer_internal.db"),
            File("/data/user/0/${extractPackageName(downloadDir)}/databases/exoplayer_internal.db"),
        ).firstOrNull { file ->
            runCatching { file.canonicalFile }.getOrDefault(file).let { candidate ->
                candidate.exists() && candidate.isFile && candidate.length() > 0L
            }
        }?.let { file -> runCatching { file.canonicalFile }.getOrDefault(file) }
    }

    private fun extractPackageName(directory: File): String? {
        val path = directory.absolutePath.replace('\\', '/')
        val marker = "/Android/data/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null
        val remainder = path.substring(idx + marker.length)
        return remainder.substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun hasCacheContent(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val children = directory.listFiles() ?: return false
        return children.any { child ->
            (child.isFile && child.length() > 0L) ||
                (child.isDirectory && hasCacheContent(child))
        }
    }

    private fun storageVolumeRoots(context: Context): Set<String> {
        val roots = linkedSetOf<String>()
        runCatching {
            Environment.getExternalStorageDirectory()?.absolutePath?.let { path ->
                roots += path
            }
        }
        roots += "/storage/emulated/0"
        context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
            directory.storageVolumeRootPath()?.let { roots += it }
        }
        return roots
    }

    private data class ProbeTarget(
        val downloadDir: File,
        val streamCacheDir: File?,
        val exoPlayerDatabase: File?,
    ) {
        val key: String =
            listOfNotNull(
                downloadDir.absolutePath,
                streamCacheDir?.absolutePath,
                exoPlayerDatabase?.absolutePath,
            ).joinToString("|")
    }

    private fun File.canonicalOrSelf(): File = runCatching { canonicalFile }.getOrDefault(this)
}

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
