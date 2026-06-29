/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.ArchiveTuneCanvas
import moe.rukamori.archivetune.canvas.HushMusicCanvasProvider
import moe.rukamori.archivetune.canvas.TidalCanvasProvider
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.constants.CanvasSource
import timber.log.Timber

internal suspend fun resolveCanvasArtworkForPlayback(
    mediaId: String,
    songTitleRaw: String,
    artistNameRaw: String,
    albumId: String? = null,
    albumTitleRaw: String? = null,
    storefront: String,
    requireVertical: Boolean,
    allowNetwork: Boolean,
    canvasSource: CanvasSource = CanvasSource.AUTO,
): CanvasArtwork? {
    val cacheKey = "$mediaId:${canvasSource.name}"

    withContext(Dispatchers.IO) {
        CanvasArtworkPlaybackCache.get(
            mediaId = cacheKey,
            preferCachedOnly = !allowNetwork,
        )
    }
        ?.takeIf { artwork -> artwork.hasRequiredCanvasVariant(requireVertical) }
        ?.let { return it }

    if (!allowNetwork || mediaId.isBlank()) {
        Timber.tag(CanvasArtworkLogTag).d("Skipping canvas network lookup for %s", mediaId)
        return null
    }

    return withContext(Dispatchers.IO) {
        val fetched =
            fetchCanvasArtworkForPlayback(
                songTitleRaw = songTitleRaw,
                artistNameRaw = artistNameRaw,
                albumTitleRaw = albumTitleRaw,
                storefront = storefront,
                requireVertical = requireVertical,
                canvasSource = canvasSource,
            ) ?: fetchCanvasArtworkByAlbumFallback(
                albumId = albumId,
                albumTitleRaw = albumTitleRaw,
                artistNameRaw = artistNameRaw,
                storefront = storefront,
                requireVertical = requireVertical,
                canvasSource = canvasSource,
            )

        if (fetched == null) {
            Timber.tag(CanvasArtworkLogTag).d("No playable canvas resolved for %s", mediaId)
            return@withContext null
        }

        CanvasArtworkPlaybackCache.put(cacheKey, fetched)
    }
}

internal suspend fun fetchCanvasArtworkForPlayback(
    songTitleRaw: String,
    artistNameRaw: String,
    albumTitleRaw: String? = null,
    storefront: String,
    requireVertical: Boolean,
    canvasSource: CanvasSource,
): CanvasArtwork? {
    val songTitle = normalizeCanvasSongTitle(songTitleRaw)
    val artistName = normalizeCanvasArtistName(artistNameRaw)
    val albumName = albumTitleRaw?.trim().orEmpty()
    val candidates =
        linkedSetOf(
            songTitle to artistName,
            songTitleRaw to artistName,
            songTitle to artistNameRaw,
            songTitleRaw to artistNameRaw,
        ).filter { (song, artist) ->
            song.isNotBlank() && artist.isNotBlank()
        }

    return candidates.firstNotNullOfOrNull { (song, artist) ->
        fetchFromSource(
            canvasSource = canvasSource,
            song = song,
            artist = artist,
            albumName = albumName,
            storefront = storefront,
            requireVertical = requireVertical,
        )
    }
}

private suspend fun fetchFromSource(
    canvasSource: CanvasSource,
    song: String,
    artist: String,
    albumName: String,
    storefront: String,
    requireVertical: Boolean,
): CanvasArtwork? {
    fun CanvasArtwork?.validated(): CanvasArtwork? =
        this?.takeIf { artwork ->
            if (requireVertical) {
                !artwork.preferredVerticalAnimationUrl.isNullOrBlank()
            } else {
                !artwork.preferredAnimationUrl.isNullOrBlank()
            }
        }

    return when (canvasSource) {
        CanvasSource.AUTO -> {
            AppleMusicProvider
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    album = albumName.takeIf { it.isNotBlank() },
                    storefront = storefront,
                ).validated() ?: HushMusicCanvasProvider
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    album = albumName,
                ).validated() ?: TidalCanvasProvider
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    album = albumName.takeIf { it.isNotBlank() },
                ).validated() ?: ArchiveTuneCanvas
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    storefront = storefront,
                ).validated()
        }

        CanvasSource.APPLE_MUSIC ->
            AppleMusicProvider
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    album = albumName.takeIf { it.isNotBlank() },
                    storefront = storefront,
                ).validated()

        CanvasSource.HUSH_CANVAS ->
            HushMusicCanvasProvider
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    album = albumName,
                ).validated() ?: ArchiveTuneCanvas
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    storefront = storefront,
                ).validated()

        CanvasSource.TIDAL ->
            TidalCanvasProvider
                .getBySongArtist(
                    song = song,
                    artist = artist,
                    album = albumName.takeIf { it.isNotBlank() },
                ).validated()
    }
}

private suspend fun fetchCanvasArtworkByAlbumFallback(
    albumId: String?,
    albumTitleRaw: String?,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    canvasSource: CanvasSource,
): CanvasArtwork? {
    if (canvasSource == CanvasSource.TIDAL || canvasSource == CanvasSource.HUSH_CANVAS) {
        return null
    }

    albumId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { nonBlankAlbumId ->
            ArchiveTuneCanvas
                .getByAlbumId(nonBlankAlbumId)
                ?.takeIf { artwork -> artwork.hasRequiredCanvasVariant(requireVertical) }
                ?.let { return it }
        }

    val albumTitle = albumTitleRaw?.trim().orEmpty()
    val artistName = artistNameRaw.trim()
    if (albumTitle.isBlank() || artistName.isBlank()) return null

    return ArchiveTuneCanvas
        .getBySongArtist(
            song = albumTitle,
            artist = artistName,
            storefront = storefront,
        )?.takeIf { artwork -> artwork.hasRequiredCanvasVariant(requireVertical) }
}

private fun CanvasArtwork.hasRequiredCanvasVariant(requireVertical: Boolean): Boolean =
    if (requireVertical) {
        !preferredVerticalAnimationUrl.isNullOrBlank()
    } else {
        !preferredAnimationUrl.isNullOrBlank()
    }

private const val CanvasArtworkLogTag = "CanvasArtwork"

private fun normalizeCanvasSongTitle(raw: String): String {
    val stripped =
        raw
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(
                Regex(
                    "\\s*\\((?:feat\\.?|ft\\.?|featuring|with)\\b[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(
                Regex(
                    "\\s*\\((?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(
                Regex(
                    "\\s*-\\s*(?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)\\b.*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(Regex("\\s+"), " ")
            .trim()

    return stripped
        .trim('-')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeCanvasArtistName(raw: String): String {
    val first =
        raw
            .split(
                Regex(
                    "(?:\\s*,\\s*|\\s*&\\s*|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
                    RegexOption.IGNORE_CASE,
                ),
                limit = 2,
            ).firstOrNull()
            .orEmpty()

    return first.replace(Regex("\\s+"), " ").trim()
}
