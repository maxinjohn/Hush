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
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class WazeBridgeDefinition(
    val id: String,
    val displayName: String,
    val providerName: String,
    val packageName: String,
    val assetPath: String,
    val requiredProtocolVersion: Int,
)

enum class WazeBridgeState {
    NOT_INSTALLED,
    ORIGINAL_APP_INSTALLED,
    BRIDGE_CURRENT,
    BRIDGE_UPDATE_AVAILABLE,
    BRIDGE_UPDATE_REQUIRED,
    BRIDGE_NEWER_THAN_BUNDLED,
    BUNDLED_APK_MISSING,
    BUNDLED_APK_INVALID,
    UNKNOWN,
}

data class WazeBridgeInspection(
    val definition: WazeBridgeDefinition,
    val state: WazeBridgeState,
    val installedVersionCode: Long? = null,
    val installedVersionName: String? = null,
    val bundledVersionCode: Long? = null,
    val bundledVersionName: String? = null,
    val installedProtocolVersion: Int? = null,
    val requiredProtocolVersion: Int,
    val installedCertificateFingerprints: Set<String> = emptySet(),
    val bundledCertificateFingerprints: Set<String> = emptySet(),
) {
    val isInstalled: Boolean
        get() = installedVersionCode != null

    val isValidBridge: Boolean
        get() = state in setOf(
            WazeBridgeState.BRIDGE_CURRENT,
            WazeBridgeState.BRIDGE_UPDATE_AVAILABLE,
            WazeBridgeState.BRIDGE_UPDATE_REQUIRED,
            WazeBridgeState.BRIDGE_NEWER_THAN_BUNDLED,
        )

    val canInstall: Boolean
        get() = state == WazeBridgeState.NOT_INSTALLED

    val canUpdate: Boolean
        get() =
            state in setOf(
                WazeBridgeState.BRIDGE_UPDATE_AVAILABLE,
                WazeBridgeState.BRIDGE_UPDATE_REQUIRED,
            ) &&
                bundledVersionCode != null &&
                installedVersionCode != null &&
                bundledVersionCode > installedVersionCode

    val canUninstall: Boolean
        get() = isValidBridge
}

object WazeBridgeManager {
    private const val BRIDGE_ARCHIVE = "waze-shims.zip"
    private const val BRIDGE_PROTOCOL = "app.hush.music.waze.PROTOCOL_VERSION"

    val definitions = listOf(
        WazeBridgeDefinition(
            id = "spotify",
            displayName = "Spotify Bridge",
            providerName = "Spotify",
            packageName = "com.spotify.music",
            assetPath = "waze-shim-spotify-release.apk",
            requiredProtocolVersion = 3,
        ),
        WazeBridgeDefinition(
            id = "youtube_music",
            displayName = "YouTube Music Bridge",
            providerName = "YouTube Music",
            packageName = "com.google.android.apps.youtube.music",
            assetPath = "waze-shim-youtubeMusic-release.apk",
            requiredProtocolVersion = 3,
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
        val installedInfo = installedPackageInfo(context.packageManager, definition.packageName)
        val installedVersionCode = installedInfo?.let(::versionCode)
        val installedVersionName = installedInfo?.versionName
        val installedProtocol = installedInfo?.applicationInfo?.metaData?.getInt(BRIDGE_PROTOCOL, 0)
        val installedCertificates = installedInfo?.let(::signingCertificateFingerprints) ?: emptySet()
        val bundled = readBundledBridge(context, definition)

        val inspection = when (bundled) {
            is BundledBridgeResult.Missing -> WazeBridgeInspection(
                definition = definition,
                state = WazeBridgeState.BUNDLED_APK_MISSING,
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                installedProtocolVersion = installedProtocol,
                requiredProtocolVersion = definition.requiredProtocolVersion,
                installedCertificateFingerprints = installedCertificates,
            )

            is BundledBridgeResult.Invalid -> WazeBridgeInspection(
                definition = definition,
                state = WazeBridgeState.BUNDLED_APK_INVALID,
                installedVersionCode = installedVersionCode,
                installedVersionName = installedVersionName,
                installedProtocolVersion = installedProtocol,
                requiredProtocolVersion = definition.requiredProtocolVersion,
                installedCertificateFingerprints = installedCertificates,
            )

            is BundledBridgeResult.Valid -> {
                val bridge = bundled.bridge
                if (installedInfo == null) {
                    WazeBridgeInspection(
                        definition = definition,
                        state = WazeBridgeState.NOT_INSTALLED,
                        bundledVersionCode = bridge.versionCode,
                        bundledVersionName = bridge.versionName,
                        requiredProtocolVersion = definition.requiredProtocolVersion,
                        bundledCertificateFingerprints = bridge.certificateFingerprints,
                    )
                } else {
                    val trustedFingerprints = trustedHushBridgeFingerprints(context)
                    val isTrustedBridge = isTrustedHushBridge(installedCertificates, trustedFingerprints)

                    diagnosticLog(
                        packageName = definition.packageName,
                        installedVersionCode = installedVersionCode,
                        installedVersionName = installedVersionName,
                        installedFingerprints = installedCertificates,
                        trustedFingerprints = trustedFingerprints,
                        isTrustedMatch = isTrustedBridge,
                    )

                    when {
                        !isTrustedBridge -> WazeBridgeInspection(
                            definition = definition,
                            state = WazeBridgeState.ORIGINAL_APP_INSTALLED,
                            installedVersionCode = installedVersionCode,
                            installedVersionName = installedVersionName,
                            bundledVersionCode = bridge.versionCode,
                            bundledVersionName = bridge.versionName,
                            installedProtocolVersion = installedProtocol,
                            requiredProtocolVersion = definition.requiredProtocolVersion,
                            installedCertificateFingerprints = installedCertificates,
                            bundledCertificateFingerprints = bridge.certificateFingerprints,
                        )

                        installedProtocol == null || installedProtocol < definition.requiredProtocolVersion -> WazeBridgeInspection(
                            definition = definition,
                            state = WazeBridgeState.BRIDGE_UPDATE_REQUIRED,
                            installedVersionCode = installedVersionCode,
                            installedVersionName = installedVersionName,
                            bundledVersionCode = bridge.versionCode,
                            bundledVersionName = bridge.versionName,
                            installedProtocolVersion = installedProtocol,
                            requiredProtocolVersion = definition.requiredProtocolVersion,
                            installedCertificateFingerprints = installedCertificates,
                            bundledCertificateFingerprints = bridge.certificateFingerprints,
                        )

                        installedVersionCode!! < bridge.versionCode -> WazeBridgeInspection(
                            definition = definition,
                            state = WazeBridgeState.BRIDGE_UPDATE_AVAILABLE,
                            installedVersionCode = installedVersionCode,
                            installedVersionName = installedVersionName,
                            bundledVersionCode = bridge.versionCode,
                            bundledVersionName = bridge.versionName,
                            installedProtocolVersion = installedProtocol,
                            requiredProtocolVersion = definition.requiredProtocolVersion,
                            installedCertificateFingerprints = installedCertificates,
                            bundledCertificateFingerprints = bridge.certificateFingerprints,
                        )

                        installedVersionCode > bridge.versionCode -> WazeBridgeInspection(
                            definition = definition,
                            state = WazeBridgeState.BRIDGE_NEWER_THAN_BUNDLED,
                            installedVersionCode = installedVersionCode,
                            installedVersionName = installedVersionName,
                            bundledVersionCode = bridge.versionCode,
                            bundledVersionName = bridge.versionName,
                            installedProtocolVersion = installedProtocol,
                            requiredProtocolVersion = definition.requiredProtocolVersion,
                            installedCertificateFingerprints = installedCertificates,
                            bundledCertificateFingerprints = bridge.certificateFingerprints,
                        )

                        else -> WazeBridgeInspection(
                            definition = definition,
                            state = WazeBridgeState.BRIDGE_CURRENT,
                            installedVersionCode = installedVersionCode,
                            installedVersionName = installedVersionName,
                            bundledVersionCode = bridge.versionCode,
                            bundledVersionName = bridge.versionName,
                            installedProtocolVersion = installedProtocol,
                            requiredProtocolVersion = definition.requiredProtocolVersion,
                            installedCertificateFingerprints = installedCertificates,
                            bundledCertificateFingerprints = bridge.certificateFingerprints,
                        )
                    }
                }
            }
        }
        logInspection(inspection)
        return inspection
    }

    fun extractInstallableBridge(
        context: Context,
        inspection: WazeBridgeInspection,
    ): File? {
        if (!inspection.canInstall && !inspection.canUpdate) return null
        val bundled = readBundledBridge(context, inspection.definition) as? BundledBridgeResult.Valid ?: return null
        if (bundled.bridge.packageName != inspection.definition.packageName) return null

        val installed = installedPackageInfo(context.packageManager, inspection.definition.packageName)
        if (installed != null) {
            val installedCerts = signingCertificateFingerprints(installed)
            val trustedFingerprints = trustedHushBridgeFingerprints(context)
            if (!isTrustedHushBridge(installedCerts, trustedFingerprints)) {
                return null
            }
            if (bundled.bridge.versionCode <= versionCode(installed)) return null
        }
        return bundled.bridge.apk
    }

    private fun trustedHushBridgeFingerprints(context: Context): Set<String> {
        val fingerprints = mutableSetOf<String>()

        for (definition in definitions) {
            when (val bundled = readBundledBridge(context, definition)) {
                is BundledBridgeResult.Valid -> {
                    fingerprints.addAll(bundled.bridge.certificateFingerprints)
                }
                else -> {}
            }
        }

        try {
            val myInfo = context.packageManager.getPackageInfo(
                context.packageName,
                packageInfoFlags(),
            )
            fingerprints.addAll(signingCertificateFingerprints(myInfo))
        } catch (_: Exception) {}

        return fingerprints
    }

    private fun isTrustedHushBridge(
        installedFingerprints: Set<String>,
        trustedFingerprints: Set<String>,
    ): Boolean = installedFingerprints.isNotEmpty() &&
        trustedFingerprints.isNotEmpty() &&
        installedFingerprints.any { it in trustedFingerprints }

    private fun diagnosticLog(
        packageName: String,
        installedVersionCode: Long?,
        installedVersionName: String?,
        installedFingerprints: Set<String>,
        trustedFingerprints: Set<String>,
        isTrustedMatch: Boolean,
    ) {
        Timber.tag("WazeBridge").d(
            "Package: %s\nInstalled version: %s (%s)\nInstalled fingerprints: [%s]\nTrusted fingerprints: [%s]\nTrusted Bridge match: %b",
            packageName,
            installedVersionName ?: "none",
            installedVersionCode ?: "none",
            installedFingerprints.joinToString(", ").ifBlank { "none" },
            trustedFingerprints.joinToString(", ").ifBlank { "none" },
            isTrustedMatch,
        )
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
        if (inspection.state !in setOf(
                WazeBridgeState.BRIDGE_UPDATE_AVAILABLE,
                WazeBridgeState.BRIDGE_UPDATE_REQUIRED,
            )
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

    private sealed interface BundledBridgeResult {
        data object Missing : BundledBridgeResult
        data object Invalid : BundledBridgeResult
        data class Valid(val bridge: BundledBridgeApk) : BundledBridgeResult
    }

    private data class BundledBridgeApk(
        val apk: File,
        val packageName: String,
        val versionCode: Long,
        val versionName: String?,
        val protocolVersion: Int,
        val certificateFingerprints: Set<String>,
    )

    private fun readBundledBridge(
        context: Context,
        definition: WazeBridgeDefinition,
    ): BundledBridgeResult {
        val apk = File(context.cacheDir, "waze-bridge-${definition.id}.apk")
        val entryFound = try {
            context.assets.open(BRIDGE_ARCHIVE).use { archive ->
                ZipInputStream(archive).use { entries ->
                    while (true) {
                        val entry = entries.nextEntry ?: break
                        if (entry.name == definition.assetPath) {
                            apk.outputStream().use { output -> entries.copyTo(output) }
                            return@use true
                        }
                    }
                    false
                }
            }
        } catch (error: IOException) {
            Timber.tag("WazeBridge").w(error, "Unable to read embedded %s", definition.displayName)
            false
        }
        if (!entryFound) {
            apk.delete()
            return BundledBridgeResult.Missing
        }

        val packageInfo = packageArchiveInfo(context.packageManager, apk)
            ?: return BundledBridgeResult.Invalid
        val packageName = packageInfo.packageName
        val protocolVersion = packageInfo.applicationInfo?.metaData?.getInt(BRIDGE_PROTOCOL, 0) ?: 0
        val certificates = signingCertificateFingerprints(packageInfo)
        if (packageName != definition.packageName ||
            protocolVersion < definition.requiredProtocolVersion ||
            certificates.isEmpty()
        ) {
            Timber.tag("WazeBridge").w(
                "Invalid embedded %s: package=%s protocol=%d certificates=%d",
                definition.displayName,
                packageName,
                protocolVersion,
                certificates.size,
            )
            return BundledBridgeResult.Invalid
        }
        return BundledBridgeResult.Valid(
            BundledBridgeApk(
                apk = apk,
                packageName = packageName,
                versionCode = versionCode(packageInfo),
                versionName = packageInfo.versionName,
                protocolVersion = protocolVersion,
                certificateFingerprints = certificates,
            ),
        )
    }

    private fun logInspection(inspection: WazeBridgeInspection) {
        Timber.tag("WazeBridge").d(
            "%s package=%s installed=%s (%s) bundled=%s (%s) installedCert=%s bundledCert=%s protocol=%s/%s state=%s",
            inspection.definition.displayName,
            inspection.definition.packageName,
            inspection.installedVersionName ?: "none",
            inspection.installedVersionCode ?: "none",
            inspection.bundledVersionName ?: "none",
            inspection.bundledVersionCode ?: "none",
            inspection.installedCertificateFingerprints.joinToString(",").ifBlank { "none" },
            inspection.bundledCertificateFingerprints.joinToString(",").ifBlank { "none" },
            inspection.installedProtocolVersion ?: "none",
            inspection.requiredProtocolVersion,
            inspection.state,
        )
    }

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
    private fun signingCertificateFingerprints(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            buildList {
                addAll(signingInfo?.apkContentsSigners.orEmpty())
                if (signingInfo?.hasPastSigningCertificates() == true) {
                    addAll(signingInfo.signingCertificateHistory.orEmpty())
                }
            }
        } else {
            packageInfo.signatures?.toList().orEmpty()
        }
        return signatures.mapTo(linkedSetOf(), ::signatureSha256)
    }

    private fun signatureSha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte) }

    @Suppress("DEPRECATION")
    private fun versionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
}
