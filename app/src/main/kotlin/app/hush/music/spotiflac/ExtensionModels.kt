package app.hush.music.spotiflac

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class ExtensionSource(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = id,
    @SerialName("display_name") val displayNameFromRegistry: String? = null,
    @SerialName("description") val description: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("author") val author: String = "",
    @SerialName("icon") val icon: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("min_app_version") val minAppVersion: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("relay_url") val relayUrl: String? = null,
    @SerialName("provider_key") val providerKey: String? = null,
    @SerialName("repository_id") val repositoryId: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("priority") val priority: Int = 0,
    @SerialName("types") val types: List<String> = emptyList(),
    @SerialName("required_runtime_features") val requiredRuntimeFeatures: List<String> = emptyList(),
) {
    val displayName: String get() = displayNameFromRegistry?.ifBlank { null } ?: name.ifBlank { id }

    val displayDescription: String get() = description.ifBlank { "No description" }

    val hasVerifiedPackageDigest: Boolean
        get() = sha256?.matches(Regex("[0-9a-fA-F]{64}")) == true

    val isRuntimeCompatible: Boolean
        get() = hasVerifiedPackageDigest && !downloadUrl.isNullOrBlank()

    /** Registry metadata is accepted only when it cannot redirect Hush to an unsafe host. */
    val hasSafeNetworkMetadata: Boolean
        get() = listOf(downloadUrl, icon, iconUrl, relayUrl)
            .filterNotNull()
            .all { value ->
                runCatching {
                    val uri = URI(value.trim())
                    uri.scheme.equals("https", ignoreCase = true) && uri.userInfo.isNullOrBlank()
                }.getOrDefault(false)
            }

    val supportsDownload: Boolean
        get() = types.any { it.equals("download_provider", ignoreCase = true) } ||
            category.equals("download", ignoreCase = true)

    /**
     * What this source is for, in the terms the session list describes rows with.
     *
     * A registry entry carries no `types` list - that lives in the extension's own manifest, readable
     * only once the package has been extracted - so `category` and `tags` are what is known about a
     * source before anything is downloaded. Without this the session list had no role to go on and
     * described Apple Music and Spotify Web with the same sentence it uses for a download provider,
     * which is exactly what made them look like sources that had lost a session rather than ones that
     * never had one. Checked against the registries Hush reads: every download source publishes
     * `category: "download"`, and the two that cannot download publish `"integration"` - with
     * Apple Music carrying a `lyrics` tag and Spotify Web a plain metadata one.
     */
    val declaredRoles: List<String>
        get() =
            if (types.isNotEmpty()) {
                types
            } else {
                buildList {
                    val kind = category.orEmpty().trim()
                    when {
                        kind.equals("download", ignoreCase = true) -> add("download_provider")
                        kind.isNotEmpty() -> add("metadata_provider")
                    }
                    if (tags.any { it.equals("lyrics", ignoreCase = true) }) add("lyrics_provider")
                }
            }

    val isSafeRegistryEntry: Boolean
        get() {
            if (!id.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) return false
            val packageUrl = downloadUrl?.trim()
            if (packageUrl != null && !isSafeHttpsUrl(packageUrl)) return false
            if (sha256 != null && !hasVerifiedPackageDigest) return false
            return listOf(icon, iconUrl, relayUrl).filterNotNull().all(::isSafeHttpsUrl)
        }

    private fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrBlank()
    }.getOrDefault(false)
}

@Serializable
data class ExtensionRegistry(
    @SerialName("version") val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("extensions") val extensions: List<ExtensionSource> = emptyList(),
)

@Serializable
data class ExtensionRegistryResponse(
    @SerialName("version") val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("extensions") val extensions: List<ExtensionSource> = emptyList(),
)
