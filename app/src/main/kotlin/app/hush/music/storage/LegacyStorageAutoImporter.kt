package app.hush.music.storage

import android.content.Context
import app.hush.music.constants.AutoImportLegacyStorageKey
import app.hush.music.constants.LegacyStorageImportedAppsKey
import app.hush.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object LegacyStorageAutoImporter {
    suspend fun runIfEnabled(
        context: Context,
        importLegacyStorage: ImportLegacyStorageUseCase,
    ) {
        val appContext = context.applicationContext
        val preferences = appContext.dataStore.data.first()
        if (preferences[AutoImportLegacyStorageKey] != true) return
        val importedApps = preferences[LegacyStorageImportedAppsKey] ?: emptySet()
        val candidate =
            withContext(Dispatchers.IO) {
                LegacyStorageCompatibility
                    .scan(appContext)
                    .importableCandidates
                    .filter { legacy -> legacy.app.id !in importedApps && legacy.canImport }
                    .maxByOrNull { legacy -> legacy.totalBytes }
            } ?: return

        importLegacyStorage(candidate) { }
    }
}
