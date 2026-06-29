/*
 * Hush — GPL-3.0
 * Decrypt JioSaavn encrypted_media_url values (DES-ECB, key 38346591).
 */

package moe.rukamori.archivetune.jiosaavn

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object SaavnUrlDecryptor {
    private const val TAG = "SaavnService"
    private const val DES_KEY = "38346591"

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
    ): String {
        val suffix =
            when (quality.lowercase()) {
                "320kbps" -> "_320.mp4"
                "160kbps" -> "_160.mp4"
                "96kbps", "48kbps", "12kbps" -> "_96.mp4"
                else -> "_320.mp4"
            }
        return decryptedBaseUrl.replace(Regex("_\\d+\\.mp4$"), suffix)
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
            preview
                .replace("http://", "https://")
                .replace("//preview.", "//aac.")
                .replace("_96_p.mp4", "_96.mp4")
        if (!base.startsWith("http")) return emptyList()
        val urls = qualityUrlsFromBase(base).toMutableList()
        if (!supports320) {
            urls.removeAll { it.quality == "320kbps" }
        }
        return urls.filter { it.url.isNotBlank() }
    }

    private fun qualityUrlsFromBase(base: String): List<SaavnDownloadUrl> =
        listOf(
            SaavnDownloadUrl("320kbps", urlForQuality(base, "320kbps")),
            SaavnDownloadUrl("160kbps", urlForQuality(base, "160kbps")),
            SaavnDownloadUrl("96kbps", urlForQuality(base, "96kbps")),
        ).filter { it.url.isNotBlank() }
}
