package app.hush.music.eq

import app.hush.music.eq.audio.CustomEqualizerAudioProcessor
import app.hush.music.eq.data.ParametricEQ
import app.hush.music.eq.data.SavedEQProfile
import timber.log.Timber

/**
 * Service for managing custom EQ via ExoPlayer's AudioProcessor.
 * Holds references to all registered processor instances so profiles
 * can be applied to both the primary and secondary (crossfade) players.
 */
class HushEqualizerService {

    private val audioProcessors = mutableListOf<CustomEqualizerAudioProcessor>()
    var pendingProfile: SavedEQProfile? = null
        private set
    var shouldDisable: Boolean = false
        private set

    private var _enabled = false
    val isEnabled: Boolean get() = _enabled

    /**
     * Register an audio processor instance (called from ExoPlayer setup).
     */
    fun addAudioProcessor(processor: CustomEqualizerAudioProcessor) {
        audioProcessors.add(processor)
        Timber.tag(TAG).d("Processor added. Total: ${audioProcessors.size}")

        when {
            shouldDisable -> processor.disable()
            pendingProfile != null -> applyProfileToProcessor(processor, pendingProfile!!)
        }
    }

    /**
     * Unregister an audio processor instance.
     */
    fun removeAudioProcessor(processor: CustomEqualizerAudioProcessor) {
        audioProcessors.remove(processor)
    }

    /**
     * Apply an EQ profile to all registered processors.
     */
    fun applyProfile(profile: SavedEQProfile) {
        pendingProfile = profile
        shouldDisable = false
        _enabled = true

        if (audioProcessors.isEmpty()) {
            Timber.tag(TAG).w("No processors available. Profile stored as pending.")
            return
        }

        audioProcessors.forEach { processor ->
            try {
                applyProfileToProcessor(processor, profile)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to apply profile to processor")
            }
        }
    }

    private fun applyProfileToProcessor(processor: CustomEqualizerAudioProcessor, profile: SavedEQProfile) {
        val parametricEQ = ParametricEQ(
            preamp = profile.preamp,
            bands = profile.bands,
        )
        processor.applyProfile(parametricEQ)
    }

    /**
     * Disable the equalizer (flat response).
     */
    fun disable() {
        shouldDisable = true
        pendingProfile = null
        _enabled = false

        audioProcessors.forEach { processor ->
            try {
                processor.disable()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to disable processor")
            }
        }
        Timber.tag(TAG).d("Equalizer disabled")
    }

    fun release() {
        audioProcessors.clear()
    }

    companion object {
        private const val TAG = "HushEqualizerSvc"
    }
}
