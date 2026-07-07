package app.hush.music.innertube.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteCipherConfig(
    val version: Int = 1,
    @SerialName("signature_timestamp")
    val signatureTimestamp: Int? = null,
    @SerialName("client_overrides")
    val clientOverrides: Map<String, ClientOverride>? = null,
    @SerialName("disabled_clients")
    val disabledClients: List<String>? = null,
    @SerialName("player_url_pattern")
    val playerUrlPattern: String? = null,
    @SerialName("player_url_exclude_pattern")
    val playerUrlExcludePattern: String? = null,
    @SerialName("updated_at")
    val updatedAt: Long? = null,
    @SerialName("js_player_url")
    val jsPlayerUrl: String? = null,
    @SerialName("n_param_pattern")
    val nParamPattern: String? = null,
    @SerialName("cipher_pattern")
    val cipherPattern: String? = null,
) {
    @Serializable
    data class ClientOverride(
        @SerialName("client_version")
        val clientVersion: String? = null,
        @SerialName("user_agent")
        val userAgent: String? = null,
        @SerialName("client_id")
        val clientId: Int? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val DEFAULT_CONFIG_URL = "https://hush-music.github.io/config/cipher.json"

        private const val FALLBACK_SIGNATURE_TIMESTAMP = 0

        const val CONFIG_CACHE_DURATION_MS = 4 * 60 * 60 * 1000L

        fun parse(raw: String): Result<RemoteCipherConfig> = runCatching { json.decodeFromString<RemoteCipherConfig>(raw) }

        fun defaultConfigUrl(): String = DEFAULT_CONFIG_URL

        fun defaultSignatureTimestamp(): Int = FALLBACK_SIGNATURE_TIMESTAMP

        fun disabledClientSet(config: RemoteCipherConfig?): Set<String> =
            config?.disabledClients?.toSet().orEmpty()

        fun clientOverride(
            config: RemoteCipherConfig?,
            clientName: String,
        ): ClientOverride? = config?.clientOverrides?.get(clientName)

        fun effectiveSignatureTimestamp(
            config: RemoteCipherConfig?,
            localTimestamp: Int?,
        ): Int = config?.signatureTimestamp ?: localTimestamp ?: FALLBACK_SIGNATURE_TIMESTAMP

        val EMPTY = RemoteCipherConfig()
    }
}
