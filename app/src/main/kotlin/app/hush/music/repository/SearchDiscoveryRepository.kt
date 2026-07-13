/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.Artist
import app.hush.music.db.entities.Song
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.AlbumItem
import app.hush.music.innertube.models.ArtistItem
import app.hush.music.innertube.models.SongItem
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.innertube.pages.ChartsPage
import app.hush.music.innertube.pages.MoodAndGenres
import javax.inject.Inject
import javax.inject.Singleton

data class SearchDiscoveryData(
    val moodAndGenres: List<MoodAndGenres.Item> = emptyList(),
    val newReleaseAlbums: List<AlbumItem> = emptyList(),
    val chartSections: List<ChartsPage.ChartSection> = emptyList(),
    val suggestedSongs: List<SongItem> = emptyList(),
    val searchedAlbums: List<AlbumItem> = emptyList(),
    val suggestedArtists: List<ArtistItem> = emptyList(),
)

@Singleton
class SearchDiscoveryRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        suspend fun loadExplore(): Result<SearchDiscoveryData> =
            withContext(Dispatchers.IO) {
                try {
                    val explorePage = YouTube.explore().getOrThrow()
                    Result.success(
                        SearchDiscoveryData(
                            moodAndGenres = explorePage.moodAndGenres,
                        ),
                    )
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    Result.failure(throwable)
                }
            }

        suspend fun loadSuggestions(): Result<SearchDiscoveryData> =
            withContext(Dispatchers.IO) {
                try {
                    // Suggestions are intentionally deferred until their tab is selected.
                    val chartsPage = YouTube.getChartsPage().getOrThrow()
                    Result.success(
                        SearchDiscoveryData(
                            chartSections = chartsPage.sections,
                            suggestedSongs = loadSuggestedSongs(),
                            searchedAlbums =
                                searchItems<AlbumItem>(
                                    query = TopAlbumsQuery,
                                    filter = YouTube.SearchFilter.FILTER_ALBUM,
                                ),
                            suggestedArtists = loadSuggestedArtists(),
                        ),
                    )
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    Result.failure(throwable)
                }
            }

        private suspend inline fun <reified T> searchItems(
            query: String,
            filter: YouTube.SearchFilter,
        ): List<T> =
            try {
                YouTube
                    .search(
                        query = query,
                        filter = filter,
                        useAccountContext = false,
                    ).getOrThrow()
                    .items
                    .filterIsInstance<T>()
                    .take(MaxDiscoverySourceItems)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                emptyList()
            }

        private suspend fun loadSuggestedSongs(): List<SongItem> =
            run {
                val seedSongs =
                    database
                        .mostPlayedSongs(
                            fromTimeStamp = AllHistoryTimestamp,
                            limit = MaxHistoryLookupItems,
                        ).first()
                        .filterNot { song -> song.song.isLocal }
                        .take(MaxSuggestionSeedItems)
                val seedSongIds = seedSongs.mapTo(HashSet()) { song -> song.id }
                val suggestions = LinkedHashMap<String, SongItem>()

                for (song in seedSongs) {
                    loadRelatedSongs(song)
                        .ifEmpty { searchRelatedSongs(song) }
                        .forEach { candidate ->
                            if (candidate.id !in seedSongIds) suggestions.putIfAbsent(candidate.id, candidate)
                        }
                    if (suggestions.size >= MaxSuggestedItems) break
                }
                suggestions.values.take(MaxSuggestedItems)
            }

        private suspend fun loadRelatedSongs(song: Song): List<SongItem> =
            try {
                val nextResult = YouTube.next(WatchEndpoint(videoId = song.id)).getOrThrow()
                val relatedSongs =
                    nextResult
                        .relatedEndpoint
                        ?.let { endpoint -> YouTube.related(endpoint).getOrNull()?.songs }
                        .orEmpty()
                (relatedSongs + nextResult.items).distinctBy { item -> item.id }.take(MaxDiscoverySourceItems)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                emptyList()
            }

        private suspend fun searchRelatedSongs(song: Song): List<SongItem> =
            searchItems(
                query =
                    buildString {
                        append(song.title)
                        song.artists
                            .firstOrNull()
                            ?.name
                            ?.takeIf(String::isNotBlank)
                            ?.let { artistName ->
                                append(' ')
                                append(artistName)
                            }
                    },
                filter = YouTube.SearchFilter.FILTER_SONG,
            )

        private suspend fun loadSuggestedArtists(): List<ArtistItem> =
            run {
                val seedArtists =
                    database
                        .mostPlayedArtists(
                            fromTimeStamp = AllHistoryTimestamp,
                            limit = MaxHistoryLookupItems,
                        ).first()
                        .filter { artist -> artist.artist.isYouTubeArtist }
                        .take(MaxSuggestionSeedItems)
                val seedArtistIds = seedArtists.mapTo(HashSet()) { artist -> artist.id }
                val suggestions = LinkedHashMap<String, ArtistItem>()

                for (artist in seedArtists) {
                    loadRelatedArtists(artist)
                        .ifEmpty { searchRelatedArtists(artist) }
                        .forEach { candidate ->
                            if (candidate.id !in seedArtistIds) suggestions.putIfAbsent(candidate.id, candidate)
                        }
                    if (suggestions.size >= MaxSuggestedItems) break
                }
                suggestions.values.take(MaxSuggestedItems)
            }

        private suspend fun loadRelatedArtists(artist: Artist): List<ArtistItem> =
            try {
                YouTube
                    .artist(artist.id)
                    .getOrThrow()
                    .sections
                    .flatMap { section -> section.items }
                    .filterIsInstance<ArtistItem>()
                    .take(MaxDiscoverySourceItems)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                emptyList()
            }

        private suspend fun searchRelatedArtists(artist: Artist): List<ArtistItem> =
            searchItems(
                query = artist.title,
                filter = YouTube.SearchFilter.FILTER_ARTIST,
            )

        private companion object {
            const val AllHistoryTimestamp = 0L
            const val MaxHistoryLookupItems = 36
            const val MaxSuggestionSeedItems = 6
            const val MaxSuggestedItems = 12
            const val MaxDiscoverySourceItems = 24
            const val TopAlbumsQuery = "top albums"
        }
    }
