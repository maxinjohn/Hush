/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.di

import android.content.Context
import app.hush.music.utils.NetworkConnectivityObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * The folder a JavaScript module's script is cached in, as a folder rather than as a Context.
 *
 * The module host runs third-party scripts in a sandbox, so it is the one class in this area that has
 * to be provable rather than plausible - and a test can own a directory and a stubbed transport, but
 * not an Android context.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ModuleScriptDirectory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideKtorHttpClient(): HttpClient = HttpClient(OkHttp)

    @Provides
    @Singleton
    fun provideNetworkConnectivityObserver(@ApplicationContext context: Context): NetworkConnectivityObserver =
        NetworkConnectivityObserver(context)

    /**
     * Where a fetched module script lives.
     *
     * Beside the rest of the SpotiFLAC state (`files/spotiflac/`) because it *is* SpotiFLAC state: a
     * source's script is what plays that source, and the app's own data directory is the only place a
     * script fetched from a repository may be written.
     */
    @Provides
    @Singleton
    @ModuleScriptDirectory
    fun provideModuleScriptDirectory(@ApplicationContext context: Context): File =
        File(context.filesDir, "spotiflac/modules")
}