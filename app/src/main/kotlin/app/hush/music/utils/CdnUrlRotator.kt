package app.hush.music.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

object CdnUrlRotator {
    private const val TAG = "CdnUrlRotator"

    private val cdnHostPattern = Regex("""^r(\d+)---sn-""", RegexOption.IGNORE_CASE)

    private val knownAlternates = listOf("r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10")

    fun isCdnUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return host.endsWith(".googlevideo.com", ignoreCase = true)
    }

    fun alternateCdnUrl(originalUrl: String): String? {
        val httpUrl = originalUrl.toHttpUrlOrNull() ?: return null
        val host = httpUrl.host
        val match = cdnHostPattern.find(host) ?: return null
        val currentNum = match.groupValues[1].toIntOrNull() ?: return null
        val prefix = match.groupValues[0]

        val altPrefix = knownAlternates.firstOrNull { alt ->
            val altNum = alt.removePrefix("r").toIntOrNull() ?: return@firstOrNull false
            altNum != currentNum
        } ?: return null

        val newHost = host.replace(prefix, "$altPrefix---sn-")
        if (newHost == host) return null

        return httpUrl.newBuilder()
            .host(newHost)
            .build()
            .toString()
    }

    fun allAlternateCdnUrls(originalUrl: String): List<String> {
        val httpUrl = originalUrl.toHttpUrlOrNull() ?: return emptyList()
        val host = httpUrl.host
        val match = cdnHostPattern.find(host) ?: return emptyList()
        val currentNum = match.groupValues[1].toIntOrNull() ?: return emptyList()
        val prefix = match.groupValues[0]

        return knownAlternates.mapNotNull { alt ->
            val altNum = alt.removePrefix("r").toIntOrNull() ?: return@mapNotNull null
            if (altNum == currentNum) return@mapNotNull null
            val newHost = host.replace(prefix, "$alt---sn-")
            if (newHost == host) return@mapNotNull null
            httpUrl.newBuilder()
                .host(newHost)
                .build()
                .toString()
        }
    }

    fun tryAlternateCdnUrl(
        originalUrl: String,
        maxAttempts: Int = 3,
    ): String? {
        val alternates = allAlternateCdnUrls(originalUrl)
        for (altUrl in alternates.take(maxAttempts - 1)) {
            Timber.tag(TAG).d("Trying alternate CDN URL for %s", originalUrl)
            return altUrl
        }
        return null
    }
}
