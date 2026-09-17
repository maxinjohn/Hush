/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a provider said has to be done to make its download playable.
 *
 * Amazon's extension downloads a stream exactly as Amazon serves it - encrypted - and answers
 * with the key instead of decrypting:
 *
 * ```
 * decryption: { strategy: "ffmpeg.mov_key", key: "<hex>", input_format: "mov", output_extension: "" }
 * decryption_key: "<hex>"
 * ```
 *
 * The Go runtime parses those two fields and forwards them to the platform, and does not act on
 * them: the decryption the provider asked for is the host's job. This is the host's reading of
 * what it asked for.
 *
 * The strategy names mirror the runtime's own normaliser (`extension_provider_types.go`), so a
 * provider that spells the MOV/MP4 key contract one of the accepted ways is understood here too
 * rather than looking like a contract nothing implements.
 */
data class SpotiFLACDecryptionContract(
    /** The strategy the provider named, normalised. */
    val strategy: String,
    val keyHex: String,
    /** `mov`/`mp4` for the ISO-BMFF key contract. */
    val inputFormat: String,
    /** What the decrypted file should be called: empty means "the format's own". */
    val outputExtension: String,
) {
    /** Whether the stream is encrypted in a way this app can undo. */
    val isSupported: Boolean
        get() = strategy == MOV_KEY && SpotiFLACMovKeyDecryptor.isUsableKey(keyHex)

    companion object {
        /** The contract name for decrypting an MOV/MP4 with a key, as ffmpeg spells it. */
        const val MOV_KEY = "ffmpeg.mov_key"

        private val MOV_KEY_ALIASES = setOf(
            "",
            MOV_KEY,
            "ffmpeg_mov_key",
            "mov_decryption_key",
            "mp4_decryption_key",
            "ffmpeg.mp4_decryption_key",
        )

        /**
         * The contract in a download response, or null when the response names none.
         *
         * Both spellings are read: the structured `decryption` object (which carries the output
         * extension and the input format) and the older bare `decryption_key`, which is how a
         * provider that only has a key says the same thing.
         */
        fun parse(response: JsonObject): SpotiFLACDecryptionContract? {
            val legacyKey = response["decryption_key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val topLevelExtension = response["output_extension"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val encoded = response["decryption"]?.let { element ->
                runCatching { element.jsonObject }.getOrNull()
            }
            if (encoded == null) {
                if (legacyKey.isBlank()) return null
                return SpotiFLACDecryptionContract(
                    strategy = MOV_KEY,
                    keyHex = legacyKey,
                    inputFormat = "mov",
                    outputExtension = topLevelExtension,
                )
            }
            val key = encoded["key"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { legacyKey }
            val strategy = normalize(encoded["strategy"]?.jsonPrimitive?.contentOrNull)
            val inputFormat = encoded["input_format"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()
            val extension = encoded["output_extension"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { topLevelExtension }
            if (key.isBlank() && strategy.isEmpty()) return null
            return SpotiFLACDecryptionContract(
                strategy = strategy,
                keyHex = key,
                inputFormat = inputFormat,
                outputExtension = extension,
            )
        }

        private fun normalize(strategy: String?): String {
            val value = strategy?.trim()?.lowercase(Locale.US).orEmpty()
            return if (value in MOV_KEY_ALIASES) MOV_KEY else value
        }

        /** A response body that named no key at all, for logging. */
        fun describeAbsent(response: JsonObject): String {
            val hasDecryption = response["decryption"] is JsonObject
            val hasKey = (response["decryption_key"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
            return when {
                hasDecryption -> "decryption present without a usable key"
                hasKey -> "decryption_key present without a strategy"
                else -> "no decryption contract"
            }
        }
    }
}
