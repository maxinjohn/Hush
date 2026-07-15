/*
 * Hush — GPL-3.0
 * Decrypt JioSaavn encrypted_media_url values (DES-ECB, key 38346591).
 */

package app.hush.music.jiosaavn

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object SaavnUrlDecryptor {
    private const val TAG = "SaavnService"
    private const val DES_KEY = "38346591"
    private val qualitySuffixPattern =
        Regex("_(?:12|48|96|160|320)(?:_p)?(\\.(?:mp4|m4a|aac))$", RegexOption.IGNORE_CASE)

    fun decryptMediaUrl(encryptedMediaUrl: String): String? {
        val trimmed = encryptedMediaUrl.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES"),
            )
            val decrypted =
                cipher.doFinal(
                    Base64.decode(trimmed, Base64.DEFAULT),
                )
            String(decrypted, Charsets.UTF_8).trim().takeIf { it.startsWith("http") }
        }.onFailure {
            Log.w(TAG, "decryptMediaUrl failed", it)
        }.getOrNull()
    }

    fun urlForQuality(
        decryptedBaseUrl: String,
        quality: String,
    ): String? {
        val bitrate =
            when (quality.lowercase()) {
                "320kbps" -> "320"
                "160kbps" -> "160"
                "96kbps", "48kbps", "12kbps" -> "96"
                else -> "320"
            }
        val suffixStart = decryptedBaseUrl.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: decryptedBaseUrl.length
        val path = decryptedBaseUrl.substring(0, suffixStart)
        val qualityMatch = qualitySuffixPattern.find(path) ?: return null
        return path.replaceRange(qualityMatch.range, "_$bitrate${qualityMatch.groupValues[1]}") +
            decryptedBaseUrl.substring(suffixStart)
    }

    fun buildDownloadUrls(encryptedMediaUrl: String): List<SaavnDownloadUrl> {
        val base = decryptMediaUrl(encryptedMediaUrl) ?: return emptyList()
        return qualityUrlsFromBase(base)
    }

    fun buildDownloadUrlsFromPreview(
        mediaPreviewUrl: String,
        supports320: Boolean,
    ): List<SaavnDownloadUrl> {
        val preview = mediaPreviewUrl.trim()
        if (preview.isBlank()) return emptyList()
        val base =
            rewriteUrlPath(preview) { path ->
                path
                    .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
                    .replace("//preview.", "//aac.", ignoreCase = true)
                    .replace(
                        Regex("_(?:12|48|96)_p(\\.(?:mp4|m4a|aac))$", RegexOption.IGNORE_CASE),
                        "_96$1",
                    )
            }
        if (!base.startsWith("http")) return emptyList()
        val urls = qualityUrlsFromBase(base).toMutableList()
        if (urls.all { it.quality == "96kbps" } || urls.size < 2) {
            // Try constructing from alternative base URL patterns
            val altBase = rewriteUrlPath(base) { it.replace("//aac.", "//sd.", ignoreCase = true) }
            if (altBase != base) {
                val altUrls = qualityUrlsFromBase(altBase)
                if (altUrls.size > urls.size || altUrls.firstOrNull { it.quality == "320kbps" } != null) {
                    urls.addAll(altUrls.filter { it.url.isNotBlank() })
                }
            }
        }
        if (!supports320) {
            urls.removeAll { it.quality == "320kbps" }
        }
        return urls.distinctBy { it.quality }.filter { it.url.isNotBlank() }
    }

    private fun qualityUrlsFromBase(base: String): List<SaavnDownloadUrl> =
        listOfNotNull(
            urlForQuality(base, "320kbps")?.let { SaavnDownloadUrl("320kbps", it) },
            urlForQuality(base, "160kbps")?.let { SaavnDownloadUrl("160kbps", it) },
            urlForQuality(base, "96kbps")?.let { SaavnDownloadUrl("96kbps", it) },
        )

    private fun rewriteUrlPath(
        url: String,
        transform: (String) -> String,
    ): String {
        val suffixStart = url.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: url.length
        return transform(url.substring(0, suffixStart)) + url.substring(suffixStart)
    }
}
