package app.hush.music.utils

import app.hush.music.constants.AutoBackupBeforeUpdateRetentionKey
import app.hush.music.constants.AutoBackupDailyRetentionKey
import app.hush.music.constants.AutoBackupWeeklyRetentionKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first

enum class AutoBackupType {
    DAILY,
    WEEKLY,
    BEFORE_UPDATE;

    fun retentionPreferenceKey(): Preferences.Key<Int> =
        when (this) {
            DAILY -> AutoBackupDailyRetentionKey
            WEEKLY -> AutoBackupWeeklyRetentionKey
            BEFORE_UPDATE -> AutoBackupBeforeUpdateRetentionKey
        }
}

object AutoBackupRetention {
    const val DEFAULT_LIMIT = 5
    val selectableLimits = listOf(1, 3, 5, 10, 20)

    suspend fun retentionLimit(
        dataStore: DataStore<Preferences>,
        type: AutoBackupType,
    ): Int = dataStore.data.first()[type.retentionPreferenceKey()] ?: DEFAULT_LIMIT
}
