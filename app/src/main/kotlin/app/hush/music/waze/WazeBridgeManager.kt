package app.hush.music.waze

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import app.hush.music.BuildConfig
import app.hush.music.constants.WazeBridgeUpdateDismissalsKey
import app.hush.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class WazeBridgeDefinition(
    val id: String,
    val displayName: String,
    val packageName: String,
    val assetPath: String,
    val protocolVersion: Int,
    val legacyLabel: String,
)

enum class WazeBridgeStatus {
    NOT_INSTALLED,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    UPDATE_REQUIRED,
    INSTALLED_VERSION_NEWER,
    PACKAGE_CONFLICT,
    SIGNATURE_MISMATCH,
    UNKNOWN,
}

data class WazeBridgeInspection(
    val definition: WazeBridgeDefinition,
    val status: WazeBridgeStatus,
    val installedVersionCode: Long? = null,
    val installedVersionName: String? = null,
    val bundledVersionCode: Long? = null,
    val bundledVersionName: String? = null,
    val installedProtocolVersion: Int? = null,
    val requiredProtocolVersion: Int,
) {
    val isInstalled: Boolean
        get() = status != WazeBridgeStatus.NOT_INSTALLED

    val canUpdate: Boolean
        get() =
            (status == WazeBridgeStatus.UPDATE_AVAILABLE || status == WazeBridgeStatus.UPDATE_REQUIRED) &&
                installedVersionCode != null &&
                bundledVersionCode != null &&
                bundledVersionCode > installedVersionCode
}

object WazeBridgeManager {
    private const val BRIDGE_ARCHIVE = "waze-shims.zip"
    private const val BRIDGE_MARKER = "app.hush.music.waze.SHIM"
    private const val BRIDGE_PROTOCOL = "app.hush.music.waze.PROTOCOL_VERSION"

    val definitions = listOf(
        WazeBridgeDefinition(
            id = "spotify",
            displayName = "Spotify Bridge",
            packageName = "com.spotify.music",
            assetPath = "waze-shim-spotify-release.apk",
            protocolVersion = 2,
            legacyLabel = "Hush (Spotify)",
        ),
        WazeBridgeDefinition(
            id = "youtube_music",
            displayName = "YouTube Music Bridge",
            packageName = "com.google.android.apps.youtube.music",
            assetPath = "waze-shim-youtubeMusic-release.apk",
            protocolVersion = 2,
            legacyLabel = "Hush (YouTube Music)",
        ),
    )

    fun definitionForPackage(packageName: String?): WazeBridgeDefinition? =
        definitions.firstOrNull { it.packageName == packageName }

    fun inspectRelevantBridges(
        context: Context,
        selectedPackageName: String?,
    ): List<WazeBridgeInspection> = definitions
        .filter { definition ->
            definition.packageName == selectedPackageName ||
                installedPackageInfo(context.packageManager, definition.packageName) != null
        }
        .map { definition -> inspectBridge(context, definition) }

    fun inspectBridge(
        context: Context,
        definition: WazeBridgeDefinition,
    ): WazeBridgeInspection {
        val bundledApk = extractBundledBridge(context, definition) ?: return unknown(definition)
        val bundledInfo = packageArchiveInfo(context.packageManager, bundledApk) ?: return unknown(definition)
        if (bundledInfo.packageName != definition.packageName) return unknown(definition)

        val bundledProtocol = bundledInfo.applicationInfo?.metaData?.getInt(BRIDGE_PROTOCOL, 0) ?: 0
        val bundledSigners = signingCertificateDigests(bundledInfo)
        val bundledVersionCode = versionCode(bundledInfo)
        val bundledVersionName = bundledInfo.versionName
        if (bundledProtocol != definition.protocolVersion || bundledSigners.isEmpty()) {
            return unknown(definition, bundledVersionCode, bundledVersionName, bundledProtocol)
        }

        val installedInfo = installedPackageInfo(context.packageManager, definition.packageName)
            ?: return WazeBridgeInspection(
                definition = definition,
                status = WazeBridgeStatus.NOT_INSTALLED,
                bundledVersionCode = bundledVersionCode,
                bundledVersionName = bundledVersionName,
                requiredProtocolVersion = definition.protocolVersion,
            )
        val installedVersionCode = versionCode(installedInfo)
        val installedVersionName = installedInfo.versionName
        val installedMarker = installedInfo.applicationInfo?.metaData?.getBoolean(BRIDGE_MARKER, false) == true
        val installedLabel = installedInfo.applicationInfo?.loadLabel(context.packageManager)?.toString()
        if (!installedMarker && installedLabel != definition.legacyLabel) {
            return WazeBridgeInspection(
                definition = definition,
                status = WazeBridgeStatus.PACKAGE_CONFLICT,
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                bundledVersionCode = bundledVersionCode,
                bundledVersionName = bundledVersionName,
                requiredProtocolVersion = definition.protocolVersion,
            )
        }

        if (signingCertificateDigests(installedInfo) != bundledSigners) {
            return WazeBridgeInspection(
                definition = definition,
                status = WazeBridgeStatus.SIGNATURE_MISMATCH,
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                bundledVersionCode = bundledVersionCode,
                bundledVersionName = bundledVersionName,
                requiredProtocolVersion = definition.protocolVersion,
            )
        }

        val installedProtocol = installedInfo.applicationInfo?.metaData?.getInt(BRIDGE_PROTOCOL, 0) ?: 0
        val status = when {
            installedProtocol < definition.protocolVersion -> WazeBridgeStatus.UPDATE_REQUIRED
            installedVersionCode < bundledVersionCode -> WazeBridgeStatus.UPDATE_AVAILABLE
            installedVersionCode > bundledVersionCode -> WazeBridgeStatus.INSTALLED_VERSION_NEWER
            else -> WazeBridgeStatus.UP_TO_DATE
        }
        return WazeBridgeInspection(
            definition = definition,
            status = status,
            installedVersionCode = installedVersionCode,
            installedVersionName = installedVersionName,
            bundledVersionCode = bundledVersionCode,
            bundledVersionName = bundledVersionName,
            installedProtocolVersion = installedProtocol,
            requiredProtocolVersion = definition.protocolVersion,
        )
    }

    fun extractInstallableBridge(
        context: Context,
        inspection: WazeBridgeInspection,
    ): File? {
        if (!inspection.canUpdate && inspection.status != WazeBridgeStatus.NOT_INSTALLED) return null
        val apk = extractBundledBridge(context, inspection.definition) ?: return null
        val bundledInfo = packageArchiveInfo(context.packageManager, apk) ?: return null
        if (bundledInfo.packageName != inspection.definition.packageName) return null
        if (signingCertificateDigests(bundledInfo).isEmpty()) return null

        val installedInfo = installedPackageInfo(context.packageManager, inspection.definition.packageName)
        if (installedInfo != null) {
            if (signingCertificateDigests(installedInfo) != signingCertificateDigests(bundledInfo)) return null
            if (versionCode(bundledInfo) <= versionCode(installedInfo)) return null
        }
        return apk
    }

    suspend fun isUpdateDismissed(
        context: Context,
        inspection: WazeBridgeInspection,
    ): Boolean {
        val key = dismissalKey(inspection) ?: return false
        return key in (context.dataStore.data.first()[WazeBridgeUpdateDismissalsKey] ?: emptySet())
    }

    suspend fun dismissUpdate(
        context: Context,
        inspection: WazeBridgeInspection,
    ) {
        val key = dismissalKey(inspection) ?: return
        context.dataStore.edit { preferences ->
            preferences[WazeBridgeUpdateDismissalsKey] =
                (preferences[WazeBridgeUpdateDismissalsKey] ?: emptySet()) + key
        }
    }

    private fun dismissalKey(inspection: WazeBridgeInspection): String? {
        if (inspection.status != WazeBridgeStatus.UPDATE_AVAILABLE &&
            inspection.status != WazeBridgeStatus.UPDATE_REQUIRED
        ) {
            return null
        }
        return listOf(
            BuildConfig.VERSION_CODE,
            inspection.definition.packageName,
            inspection.installedVersionCode,
            inspection.bundledVersionCode,
        ).joinToString(":")
    }

    private fun unknown(
        definition: WazeBridgeDefinition,
        bundledVersionCode: Long? = null,
        bundledVersionName: String? = null,
        bundledProtocol: Int? = null,
    ) = WazeBridgeInspection(
        definition = definition,
        status = WazeBridgeStatus.UNKNOWN,
        bundledVersionCode = bundledVersionCode,
        bundledVersionName = bundledVersionName,
        requiredProtocolVersion = bundledProtocol ?: definition.protocolVersion,
    )

    private fun extractBundledBridge(
        context: Context,
        definition: WazeBridgeDefinition,
    ): File? = runCatching {
        val apk = File(context.cacheDir, definition.assetPath)
        context.assets.open(BRIDGE_ARCHIVE).use { archive ->
            ZipInputStream(archive).use { entries ->
                while (true) {
                    val entry = entries.nextEntry ?: break
                    if (entry.name == definition.assetPath) {
                        apk.outputStream().use { output -> entries.copyTo(output) }
                        return@runCatching apk
                    }
                }
            }
        }
        null
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo? =
        runCatching { packageManager.getPackageInfo(packageName, packageInfoFlags()) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(packageManager: PackageManager, apk: File): PackageInfo? =
        packageManager.getPackageArchiveInfo(apk.absolutePath, packageInfoFlags())

    @Suppress("DEPRECATION")
    private fun packageInfoFlags(): Int =
        PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(packageInfo: PackageInfo): Set<String> {
        val signatures: Array<Signature> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                packageInfo.signatures ?: emptyArray()
            }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
}
