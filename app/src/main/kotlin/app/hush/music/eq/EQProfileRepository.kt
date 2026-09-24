package app.hush.music.eq

import android.content.Context
import app.hush.music.eq.data.ParametricEQ
import app.hush.music.eq.data.ParametricEQBand
import app.hush.music.eq.data.SavedEQProfile
import app.hush.music.utils.dataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Repository for managing EQ profiles.
 * Saves/loads profiles as JSON files in the app's internal storage.
 *
 * Every file in here is touched on [Dispatchers.IO], never on the caller's thread. The caller is the
 * player menu: it builds this repository *while it is being composed* ([app.hush.music.ui.menu.PlayerMenu]),
 * so the reads and writes the equalizer screen performs used to happen on the main thread, in the
 * frame that opened the menu - a directory of profile files read and rewritten per open.
 */
class EQProfileRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _profiles = MutableStateFlow<List<SavedEQProfile>>(emptyList())
    val profiles: StateFlow<List<SavedEQProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<SavedEQProfile?>(null)
    val activeProfile: StateFlow<SavedEQProfile?> = _activeProfile.asStateFlow()

    private val profilesDir: File
        get() = File(context.filesDir, "eq_profiles").also { it.mkdirs() }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Completes once the stored profiles have been read.
     *
     * Reading them is what makes the load asynchronous, and every mutation waits for it: without
     * that, a profile saved while the read was still in flight would be written to disk and then
     * dropped from memory when the read published its (older) list.
     */
    private val loaded = CompletableDeferred<Unit>()

    companion object {
        private const val ACTIVE_PROFILE_FILE = "eq_active_profile_id"
    }

    init {
        ioScope.launch {
            try {
                loadProfiles()
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    private fun loadProfiles() {
        val files = profilesDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        val loaded = files.mapNotNull { file ->
            try {
                json.decodeFromString<SavedEQProfile>(file.readText())
            } catch (_: Exception) { null }
        }
        _profiles.value = loaded

        // Restore active profile
        val activeIdFile = File(context.filesDir, ACTIVE_PROFILE_FILE)
        if (activeIdFile.exists()) {
            val activeId = activeIdFile.readText().trim()
            _activeProfile.value = loaded.find { it.id == activeId }
        }
    }

    suspend fun saveProfile(profile: SavedEQProfile) =
        withContext(Dispatchers.IO) {
            loaded.await()
            val current = _profiles.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.id == profile.id }
            if (existingIndex >= 0) current[existingIndex] = profile
            else current.add(profile)

            val profileFile = File(profilesDir, "${sanitizeFileName(profile.id)}.json")
            profileFile.writeText(json.encodeToString(profile))

            _profiles.value = current
        }

    suspend fun deleteProfile(profileId: String) =
        withContext(Dispatchers.IO) {
            loaded.await()
            val current = _profiles.value.toMutableList()
            current.removeAll { it.id == profileId }

            val profileFile = File(profilesDir, "${sanitizeFileName(profileId)}.json")
            profileFile.delete()

            if (_activeProfile.value?.id == profileId) {
                _activeProfile.value = null
                File(context.filesDir, ACTIVE_PROFILE_FILE).delete()
            }

            _profiles.value = current
        }

    suspend fun setActiveProfile(profileId: String?) =
        withContext(Dispatchers.IO) {
            loaded.await()
            if (profileId == null) {
                _activeProfile.value = null
                File(context.filesDir, ACTIVE_PROFILE_FILE).delete()
            } else {
                val profile = _profiles.value.find { it.id == profileId }
                _activeProfile.value = profile
                File(context.filesDir, ACTIVE_PROFILE_FILE).writeText(profileId)
            }
        }

    suspend fun importCustomProfile(
        name: String,
        parametricEQ: ParametricEQ,
    ) {
        val id = "custom_${System.currentTimeMillis()}_${name.hashCode()}"
        val profile = SavedEQProfile(
            id = id,
            name = name,
            bands = parametricEQ.bands,
            preamp = parametricEQ.preamp,
            isCustom = true,
        )
        saveProfile(profile)
    }

    fun getSortedProfiles(): List<SavedEQProfile> =
        _profiles.value
            .filter { it.isCustom }
            .sortedByDescending { it.addedTimestamp }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
