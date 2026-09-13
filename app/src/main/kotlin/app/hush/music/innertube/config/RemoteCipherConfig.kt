package app.hush.music.innertube.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteCipherConfig(
    val version: Int = 1,
    @SerialName("schemaVersion")
    val schemaVersion: Int? = null,
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
    @SerialName("players")
    val players: Map<String, PlayerConfig>? = null,
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

    @Serializable
    data class PlayerConfig(
        @SerialName("sig") val signatureFunction: String? = null,
        @SerialName("nClass") val throttlingClass: String? = null,
        @SerialName("sts") val signatureTimestamp: Int? = null,
        @SerialName("aliases") val aliases: List<String> = emptyList(),
    ) {
        val isUsable: Boolean
            get() = !signatureFunction.isNullOrBlank() && !throttlingClass.isNullOrBlank()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val DEFAULT_CONFIG_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

        private const val FALLBACK_SIGNATURE_TIMESTAMP = 0

        const val CONFIG_CACHE_DURATION_MS = 6 * 60 * 60 * 1000L

        fun parse(raw: String): Result<RemoteCipherConfig> = runCatching { json.decodeFromString<RemoteCipherConfig>(raw) }

        fun defaultConfigUrl(): String = DEFAULT_CONFIG_URL

        fun defaultSignatureTimestamp(): Int = FALLBACK_SIGNATURE_TIMESTAMP

        fun disabledClientSet(config: RemoteCipherConfig?): Set<String> =
            config?.disabledClients?.toSet().orEmpty()

        fun clientOverride(
            config: RemoteCipherConfig?,
            clientName: String,
        ): ClientOverride? = config?.clientOverrides?.get(clientName)

        fun playerConfig(config: RemoteCipherConfig?, playerHash: String): PlayerConfig? {
            val players = config?.players ?: return null
            val normalizedHash = playerHash.trim().lowercase()
            return players[normalizedHash]
                ?: players.entries.firstOrNull { entry ->
                    entry.key.equals(normalizedHash, ignoreCase = true) ||
                        entry.value.aliases.any { it.equals(normalizedHash, ignoreCase = true) }
                }?.value
        }

        fun latestPlayerSignatureTimestamp(config: RemoteCipherConfig?): Int? =
            config?.players
                ?.values
                ?.filter { it.isUsable && it.signatureTimestamp != null && it.signatureTimestamp > 0 }
                ?.maxOfOrNull { it.signatureTimestamp!! }

        fun effectiveSignatureTimestamp(
            config: RemoteCipherConfig?,
            playerHash: String?,
            localTimestamp: Int?,
        ): Int {
            val playerTimestamp = playerHash
                ?.takeIf { it.isNotBlank() }
                ?.let { playerConfig(config, it)?.signatureTimestamp }
            return playerTimestamp
                ?: config?.signatureTimestamp
                ?: localTimestamp
                ?: latestPlayerSignatureTimestamp(config)
                ?: FALLBACK_SIGNATURE_TIMESTAMP
        }

        fun effectiveSignatureTimestamp(
            config: RemoteCipherConfig?,
            localTimestamp: Int?,
        ): Int = effectiveSignatureTimestamp(config, null, localTimestamp)

        val EMPTY = RemoteCipherConfig()
    }
}
