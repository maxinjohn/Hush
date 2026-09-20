/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.auto

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import app.hush.music.spotify.SpotifyLibraryRepository

/**
 * Lets a debug-only component ask the app the same question the car's browse tree asks: is a
 * Spotify account connected? A second, private answer to that question would be a second thing to
 * get wrong, and it is exactly the answer that decides whether the Spotify folder exists.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoDebugEntryPoint {
    fun spotifyLibraryRepository(): SpotifyLibraryRepository
}
