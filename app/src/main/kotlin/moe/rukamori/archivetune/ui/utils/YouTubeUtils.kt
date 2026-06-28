/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:Suppress("LocalVariableName")

package moe.rukamori.archivetune.ui.utils

private const val PlayerArtworkHighResPx = 1080

private val wHPathRegex = Regex("w\\d+-h\\d+")
private val wHParamRegex = Regex("=w(\\d+)-h(\\d+)")
private val sParamRegex = Regex("=s(\\d+)")
private val brokenSAppendRegex = Regex("-s\\d+")
private val lh3Yt3ResizeRegex =
    Regex("https://(?:lh3|yt3)\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*")
private val yt3GgphtRegex = Regex("https://yt3\\.ggpht\\.com/.*=s(\\d+)")
private val ytimgVideoIdRegex = Regex("/vi(?:_webp)?/([^/]+)/")

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this

    lh3Yt3ResizeRegex.matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        if (w != null && h == null) h = (w / W) * H
        if (w == null && h != null) w = (h / H) * W
        return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
    }

    if (this matches yt3GgphtRegex) {
        return "$this-s${width ?: height}"
    }

    val isGoogleCdn = contains("googleusercontent.com") || contains("ggpht.com")
    val isYtimg = contains("i.ytimg.com")

    if (isGoogleCdn) {
        val w = width ?: height!!
        val h = height ?: width!!

        if (wHPathRegex.containsMatchIn(this)) {
            return replace(wHPathRegex, "w$w-h$h")
        }

        wHParamRegex.find(this)?.let {
            return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
        }

        sParamRegex.find(this)?.let { match ->
            val before = substring(0, match.range.first)
            val after = substring(match.range.last + 1)
            return "$before=s${maxOf(w, h)}${after.replace(brokenSAppendRegex, "")}"
        }

        return this
    }

    if (isYtimg) {
        return resizeYtimg(width, height)
    }

    return this
}

private fun String.resizeYtimg(
    width: Int?,
    height: Int?,
): String {
    val w = width ?: height!!
    val videoId = ytimgVideoIdRegex.find(this)?.groupValues?.get(1) ?: return this

    return when {
        w >= 800 -> {
            if (contains("maxresdefault") || contains("sddefault")) {
                "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            } else {
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }
        }
        w >= 320 -> {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }
        else -> {
            "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        }
    }
}

fun String.highRes(): String = resize(PlayerArtworkHighResPx, PlayerArtworkHighResPx)
