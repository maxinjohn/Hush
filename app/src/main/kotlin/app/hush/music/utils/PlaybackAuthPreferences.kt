/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import app.hush.music.constants.AccountChannelHandleKey
import app.hush.music.constants.AccountEmailKey
import app.hush.music.constants.AccountNameKey
import app.hush.music.constants.DataSyncIdKey
import app.hush.music.constants.InnerTubeCookieKey
import app.hush.music.constants.PoTokenGvsKey
import app.hush.music.constants.PoTokenKey
import app.hush.music.constants.PoTokenPlayerKey
import app.hush.music.constants.PoTokenSourceUrlKey
import app.hush.music.constants.VisitorDataKey
import app.hush.music.constants.WebClientPoTokenEnabledKey
import app.hush.music.innertube.PlaybackAuthState
import app.hush.music.innertube.YouTube

fun Preferences.toPlaybackAuthState(): PlaybackAuthState =
    PlaybackAuthState(
        cookie = this[InnerTubeCookieKey],
        visitorData = this[VisitorDataKey],
        dataSyncId = this[DataSyncIdKey],
        poToken = this[PoTokenKey],
        poTokenGvs = this[PoTokenGvsKey],
        poTokenPlayer = this[PoTokenPlayerKey],
        webClientPoTokenEnabled = this[WebClientPoTokenEnabledKey] ?: false,
    ).normalized()

fun MutablePreferences.clearPlaybackAuthSession(clearAccountIdentity: Boolean = true) {
    remove(InnerTubeCookieKey)
    remove(VisitorDataKey)
    remove(DataSyncIdKey)
    remove(PoTokenKey)
    remove(PoTokenGvsKey)
    remove(PoTokenPlayerKey)
    remove(PoTokenSourceUrlKey)
    if (clearAccountIdentity) {
        remove(AccountNameKey)
        remove(AccountEmailKey)
        remove(AccountChannelHandleKey)
    }
}

fun MutablePreferences.clearPlaybackLoginContext() {
    remove(DataSyncIdKey)
}

fun PlaybackAuthState.withoutPlaybackLoginContext(): PlaybackAuthState = copy(dataSyncId = null).normalized()

fun MutablePreferences.putLegacyPoToken(value: String?) {
    val normalized = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    if (normalized == null) {
        remove(PoTokenKey)
    } else {
        this[PoTokenKey] = normalized
    }
    remove(PoTokenGvsKey)
    remove(PoTokenPlayerKey)
}

suspend fun Context.resetPlaybackLoginContext(): PlaybackAuthState {
    dataStore.edit { preferences ->
        preferences.clearPlaybackLoginContext()
    }
    val authState = dataStore.data.first().toPlaybackAuthState()
    YouTube.authState = authState
    YTPlayerUtils.clearPlaybackAuthCaches()
    return authState
}

suspend fun Context.refreshPlaybackLoginContext(forceRefresh: Boolean = false): PlaybackAuthState {
    val storedAuthState = dataStore.data.first().toPlaybackAuthState()
    if (!storedAuthState.hasLoginCookie) {
        YouTube.authState = storedAuthState
        return storedAuthState
    }

    YouTube.authState = storedAuthState
    var repairedAuthState = storedAuthState

    val refreshedDataSyncId =
        YouTube
            .accountDataSyncId()
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    if (
        !refreshedDataSyncId.isNullOrBlank() &&
        (forceRefresh || refreshedDataSyncId != storedAuthState.dataSyncId)
    ) {
        repairedAuthState = repairedAuthState.copy(dataSyncId = refreshedDataSyncId).normalized()
    }

    if (repairedAuthState.visitorData.isNullOrBlank()) {
        val refreshedVisitorData =
            YouTube
                .visitorData()
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        if (!refreshedVisitorData.isNullOrBlank()) {
            repairedAuthState = repairedAuthState.copy(visitorData = refreshedVisitorData).normalized()
        }
    }

    if (repairedAuthState.fingerprint != storedAuthState.fingerprint) {
        YouTube.authState = repairedAuthState
        YTPlayerUtils.clearPlaybackAuthCaches()
        dataStore.edit { preferences ->
            preferences[InnerTubeCookieKey] = repairedAuthState.cookie.orEmpty()
            repairedAuthState.visitorData
                ?.let { preferences[VisitorDataKey] = it }
                ?: preferences.remove(VisitorDataKey)
            if (repairedAuthState.dataSyncId.isNullOrBlank()) {
                preferences.remove(DataSyncIdKey)
            } else {
                preferences[DataSyncIdKey] = repairedAuthState.dataSyncId!!
            }
        }
    }

    return repairedAuthState
}

suspend fun <T> Context.retryWithoutPlaybackLoginContext(block: suspend () -> Result<T>): Result<T> {
    val initialAuthState = YouTube.currentPlaybackAuthState()
    val initialResult = block()
    if (initialResult.isSuccess) {
        persistPlaybackAuthRepair(
            initialAuthState = initialAuthState,
            repairedAuthState = YouTube.currentPlaybackAuthState(),
        )
        return initialResult
    }
    val failure = initialResult.exceptionOrNull()

    val currentAuthState = YouTube.currentPlaybackAuthState()
    if (!shouldRetryWithoutPlaybackLoginContext(initialAuthState, currentAuthState, failure)) {
        return initialResult
    }

    YouTube.authState = currentAuthState.withoutPlaybackLoginContext()
    YTPlayerUtils.clearPlaybackAuthCaches()
    dataStore.edit { preferences ->
        preferences.remove(DataSyncIdKey)
    }
    val retryResult = block()
    if (retryResult.isSuccess) {
        persistPlaybackAuthRepair(
            initialAuthState = currentAuthState,
            repairedAuthState = YouTube.currentPlaybackAuthState(),
        )
    }
    return retryResult
}

private suspend fun Context.persistPlaybackAuthRepair(
    initialAuthState: PlaybackAuthState,
    repairedAuthState: PlaybackAuthState,
) {
    if (initialAuthState.cookie != repairedAuthState.cookie) return
    if (initialAuthState.fingerprint == repairedAuthState.fingerprint) return

    dataStore.edit { preferences ->
        repairedAuthState.visitorData
            ?.takeIf { it.isNotBlank() && it != initialAuthState.visitorData }
            ?.let { preferences[VisitorDataKey] = it }
        repairedAuthState.dataSyncId
            ?.takeIf { it.isNotBlank() && it != initialAuthState.dataSyncId }
            ?.let { preferences[DataSyncIdKey] = it }
            ?: run {
                if (!initialAuthState.dataSyncId.isNullOrBlank() && repairedAuthState.dataSyncId.isNullOrBlank()) {
                    preferences.remove(DataSyncIdKey)
                }
            }
    }
}

internal fun shouldRetryWithoutPlaybackLoginContext(
    initialAuthState: PlaybackAuthState,
    currentAuthState: PlaybackAuthState,
    failure: Throwable?,
): Boolean {
    if (failure !is YTPlayerUtils.InvalidPlaybackLoginContextException) return false
    if (!initialAuthState.hasPlaybackLoginContext) return false
    if (!currentAuthState.hasPlaybackLoginContext) return false
    if (currentAuthState.cookie != initialAuthState.cookie) return false
    if (currentAuthState.dataSyncId != initialAuthState.dataSyncId) return false
    return true
}
