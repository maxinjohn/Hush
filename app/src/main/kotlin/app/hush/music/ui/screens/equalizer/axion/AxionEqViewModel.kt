package app.hush.music.ui.screens.equalizer.axion

import android.content.Context
import app.hush.music.eq.EQProfileRepository
import app.hush.music.eq.HushEqualizerService
import app.hush.music.eq.data.FilterType
import app.hush.music.eq.data.ParametricEQBand
import app.hush.music.eq.data.SavedEQProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AxionEqViewModel(
    private val context: Context,
    private val equalizerService: HushEqualizerService,
    private val eqProfileRepository: EQProfileRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("hush_eq_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val bandFrequencies = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    private val _bandGains = MutableStateFlow(
        FloatArray(10) { prefs.getFloat("band_$it", 0f) }
    )
    val bandGains: StateFlow<FloatArray> = _bandGains.asStateFlow()

    private val _mode = MutableStateFlow(prefs.getInt("mode", 0)) // 0: Simple, 1: Advanced
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    val customProfiles: StateFlow<List<SavedEQProfile>> =
        eqProfileRepository.profiles.map { profiles ->
            profiles.filter { it.isCustom && it.id != "hush_tuning" }
        }.stateIn(scope, SharingStarted.Lazily, emptyList())

    init {
        if (_enabled.value) {
            applyToService()
        }
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean("enabled", enabled).apply()
        if (enabled) {
            applyToService()
        } else {
            scope.launch { eqProfileRepository.setActiveProfile(null) }
            equalizerService.disable()
        }
    }

    fun setMode(mode: Int) {
        _mode.value = mode
        prefs.edit().putInt("mode", mode).apply()
        _isDirty.value = false
    }

    fun setBandGain(index: Int, gain: Float, applyOnly: Boolean = false) {
        val newGains = _bandGains.value.copyOf()
        newGains[index] = gain
        _bandGains.value = newGains
        _isDirty.value = true

        if (!applyOnly) {
            prefs.edit().putFloat("band_$index", gain).apply()
        }

        if (_enabled.value) {
            applyToService(saveToStorage = !applyOnly)
        }
    }

    fun commitCurrentGains() {
        val editor = prefs.edit()
        _bandGains.value.forEachIndexed { index, f -> editor.putFloat("band_$index", f) }
        editor.apply()
        if (_enabled.value) {
            applyToService(saveToStorage = true)
        }
    }

    fun setBandsGains(gains: FloatArray, fromUser: Boolean = false, applyOnly: Boolean = false) {
        _bandGains.value = gains
        if (!applyOnly) {
            val editor = prefs.edit()
            gains.forEachIndexed { index, f -> editor.putFloat("band_$index", f) }
            editor.apply()
        }
        _isDirty.value = fromUser
        if (_enabled.value) {
            applyToService(saveToStorage = !applyOnly)
        }
    }

    fun reset() {
        val flat = FloatArray(10) { 0f }
        setBandsGains(flat)
    }

    fun saveCustomProfile(name: String) {
        scope.launch {
            val bandVals = _bandGains.value
            val maxBoostDb = bandVals.maxOrNull()?.toDouble() ?: 0.0
            val calculatedPreamp = if (maxBoostDb > 0.0) -maxBoostDb / 50.0 else 0.0

            val bands = bandVals.mapIndexed { index, f ->
                ParametricEQBand(
                    frequency = bandFrequencies[index],
                    gain = f.toDouble() / 50.0,
                    q = 1.41,
                    filterType = FilterType.PK,
                    enabled = true,
                )
            }

            val id = "custom_${System.currentTimeMillis()}"
            val profile = SavedEQProfile(
                id = id,
                name = name,
                bands = bands,
                preamp = calculatedPreamp,
                isCustom = true,
                isActive = true,
            )

            eqProfileRepository.saveProfile(profile)
            eqProfileRepository.setActiveProfile(profile.id)
            _isDirty.value = false
        }
    }

    fun deleteProfiles(ids: List<String>) {
        scope.launch {
            ids.forEach { id -> eqProfileRepository.deleteProfile(id) }
        }
    }

    private fun applyToService(saveToStorage: Boolean = true) {
        scope.launch {
            val bandVals = _bandGains.value
            val maxBoostDb = bandVals.maxOrNull()?.toDouble() ?: 0.0
            val calculatedPreamp = if (maxBoostDb > 0.0) -maxBoostDb / 50.0 else 0.0

            val bands = bandVals.mapIndexed { index, f ->
                ParametricEQBand(
                    frequency = bandFrequencies[index],
                    gain = f.toDouble() / 50.0,
                    q = 1.41,
                    filterType = FilterType.PK,
                    enabled = true,
                )
            }

            val profile = SavedEQProfile(
                id = "hush_tuning",
                name = "Hush Tuning",
                bands = bands,
                preamp = calculatedPreamp,
                isCustom = false,
                isActive = true,
            )

            if (saveToStorage) {
                eqProfileRepository.saveProfile(profile)
                eqProfileRepository.setActiveProfile(profile.id)
            }

            equalizerService.applyProfile(profile)
        }
    }
}
