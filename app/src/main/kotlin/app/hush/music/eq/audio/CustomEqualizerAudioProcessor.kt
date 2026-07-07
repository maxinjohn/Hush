package app.hush.music.eq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.hush.music.eq.data.FilterType
import app.hush.music.eq.data.ParametricEQ
import app.hush.music.eq.data.ParametricEQBand
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Custom audio processor for ExoPlayer that applies parametric EQ using biquad filters.
 * Supports 16-bit PCM stereo/mono audio.
 */
@UnstableApi
class CustomEqualizerAudioProcessor : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var isActive = false
    private var equalizerEnabled = false

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private var filters: List<BiquadFilter> = emptyList()
    private var preampGain: Double = 1.0
    private var pendingProfile: ParametricEQ? = null

    companion object {
        private const val TAG = "CustomEqualizerAP"
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }

    /**
     * Apply an EQ profile. Thread-safe via @Synchronized.
     */
    @Synchronized
    fun applyProfile(parametricEQ: ParametricEQ) {
        if (sampleRate == 0) {
            pendingProfile = parametricEQ
            Timber.tag(TAG).d("Stored pending profile with ${parametricEQ.bands.size} bands")
            return
        }

        preampGain = 10.0.pow(parametricEQ.preamp / 20.0)
        val activeBands = parametricEQ.bands.filter { it.enabled && it.frequency < sampleRate / 2.0 }

        if (equalizerEnabled && filters.size == activeBands.size) {
            var canUpdateInPlace = true
            for (i in filters.indices) {
                val filter = filters[i]
                val band = activeBands[i]
                if (filter.frequency != band.frequency || filter.filterType != band.filterType) {
                    canUpdateInPlace = false
                    break
                }
            }
            if (canUpdateInPlace) {
                for (i in filters.indices) {
                    filters[i].updateGain(activeBands[i].gain)
                }
                Timber.tag(TAG).d("Updated gains in-place (zipper-noise free)")
                return
            }
        }

        createFilters(activeBands)
        equalizerEnabled = true
        filters.forEach { it.reset() }
        Timber.tag(TAG).d("Applied profile with ${filters.size} bands, %.1f dB preamp", parametricEQ.preamp)
    }

    @Synchronized
    fun disable() {
        equalizerEnabled = false
        filters = emptyList()
        preampGain = 1.0
        pendingProfile = null
        Timber.tag(TAG).d("Equalizer disabled")
    }

    fun isEnabled(): Boolean = equalizerEnabled

    private fun createFilters(bands: List<ParametricEQBand>) {
        if (sampleRate == 0) return
        filters = bands.map { band ->
            BiquadFilter(
                sampleRate = sampleRate,
                frequency = band.frequency,
                gain = band.gain,
                q = band.q,
                filterType = band.filterType,
            )
        }
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        pendingProfile?.let { profile ->
            preampGain = 10.0.pow(profile.preamp / 20.0)
            createFilters(profile.bands.filter { it.enabled && it.frequency < sampleRate / 2.0 })
            equalizerEnabled = true
            pendingProfile = null
            Timber.tag(TAG).d("Applied pending profile with ${filters.size} bands")
        }

        if (encoding != C.ENCODING_PCM_16BIT || channelCount > 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        isActive = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!equalizerEnabled || filters.isEmpty()) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            ensureOutputCapacity(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        ensureOutputCapacity(inputSize)

        if (encoding == C.ENCODING_PCM_16BIT) {
            processAudioBuffer16Bit(inputBuffer, outputBuffer)
        } else {
            outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun ensureOutputCapacity(size: Int) {
        if (outputBuffer === EMPTY_BUFFER || outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
    }

    private fun processAudioBuffer16Bit(input: ByteBuffer, output: ByteBuffer) {
        repeat(input.remaining() / 2 / channelCount) {
            when (channelCount) {
                1 -> {
                    val sample = input.getShort().toDouble() / 32768.0
                    var processed = sample
                    for (filter in filters) processed = filter.processSample(processed)
                    processed *= preampGain
                    output.putShort((processed * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                }
                2 -> {
                    val leftSample = input.getShort().toDouble() / 32768.0
                    val rightSample = input.getShort().toDouble() / 32768.0

                    var left = leftSample
                    var right = rightSample
                    for (filter in filters) {
                        filter.processStereo(left, right)
                        left = filter.lastOutputLeft
                        right = filter.lastOutputRight
                    }
                    left *= preampGain
                    right *= preampGain

                    output.putShort((left * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                    output.putShort((right * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
                }
                else -> {
                    repeat(channelCount) { output.putShort(input.getShort()) }
                }
            }
        }
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer.remaining() == 0

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        filters.forEach { it.reset() }
    }

    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        isActive = false
        filters.forEach { it.reset() }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }
}
