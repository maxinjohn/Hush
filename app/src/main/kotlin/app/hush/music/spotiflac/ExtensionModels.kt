package app.hush.music.spotiflac

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExtensionSource(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = id,
    @SerialName("description") val description: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("author") val author: String = "",
    @SerialName("icon") val icon: String? = null,
    @SerialName("relay_url") val relayUrl: String? = null,
    @SerialName("provider_key") val providerKey: String? = null,
    @SerialName("repository_id") val repositoryId: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("priority") val priority: Int = 0,
) {
    val displayName: String get() = name.ifBlank { id }

    val displayDescription: String get() = description.ifBlank { "No description" }
}

@Serializable
data class ExtensionRegistry(
    @SerialName("extensions") val extensions: List<ExtensionSource> = emptyList(),
)

@Serializable
data class ExtensionRegistryResponse(
    @SerialName("extensions") val extensions: List<ExtensionSource> = emptyList(),
)
