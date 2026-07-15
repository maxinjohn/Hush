package app.hush.music.ui.player.visualizer

import android.media.audiofx.Visualizer
import android.util.Log
import app.hush.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.log
import kotlin.math.exp
import java.lang.Math

data class EngineDebugInfo(
    val rawRms: Float = 0f,
    val normalizedRms: Float = 0f,
    val rollingPeak: Float = 0f,
    val noiseFloor: Float = 0f,
    val bassEnergy: Float = 0f,
    val midEnergy: Float = 0f,
    val trebleEnergy: Float = 0f,
    val beatStrength: Float = 0f,
    val fftAgeMs: Long = 0L,
    val hasValidFft: Boolean = false,
)

object PulseMatrixEngine {
    private const val TAG = "PulseMatrixEngine"
    private const val TARGET_BANDS = 16

    private fun dlog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg)
        }
    }
    private const val FRAME_MS = 17L
    private const val CAPTURE_RATE_MS = 60
    private const val FFT_TIMEOUT_MS = 250L

    private const val ATTACK_FACTOR = 0.55f
    private const val RELEASE_FACTOR = 0.28f

    private const val SPRING_STRENGTH = 0.18f
    private const val DAMPING = 0.80f
    private const val PAUSE_GRAVITY = 1.2f

    private const val NOISE_FLOOR = 0.008f
    private const val MIN_PEAK = 0.01f
    private const val MIN_RANGE = 0.01f
    private const val ROLLING_AVG_ALPHA = 0.12f
    private const val ROLLING_PEAK_ATTACK = 0.35f
    private const val ROLLING_PEAK_RELEASE = 0.008f
    private const val COMPRESSION_AMOUNT = 0.40f

    private const val SPECTRAL_FLUX_THRESHOLD = 1.8f
    private const val BEAT_TRANSIENT_BOOST = 0.25f
    private const val BEAT_DECAY_RATE = 0.88f
    private const val ABSOLUTE_REF_LEVEL = 300f
    private const val SOFT_CEIL = 200f
    private const val ENERGY_GATE_THRESHOLD = 0.06f

    private const val MIN_FREQ = 40.0
    private const val MAX_FREQ = 16000.0

    private var visualizer: Visualizer? = null
    @Volatile
    var currentSessionId: Int = 0
        private set
    @Volatile private var retrySessionId: Int = 0
    @Volatile private var consumerCount = 0
    private var processingScope: CoroutineScope? = null
    private var processingJob: Job? = null

    @Volatile private var lastMagnitudes: FloatArray? = null
    @Volatile private var lastSampleRate: Int = 44100
    @Volatile private var lastFftTimeMs: Long = 0L
    @Volatile private var visualizerStartTimeMs: Long = 0L
    @Volatile private var visualizerRetryCount: Int = 0
    private const val VISUALIZER_RETRY_MS = 2000L
    private const val MAX_VISUALIZER_RETRIES = 10

    @Volatile private var lastAcquireTimeMs: Long = 0L
    private var releaseGraceJob: Job? = null
    private const val RELEASE_GRACE_MS = 10000L

    private var rollingAvgs = FloatArray(TARGET_BANDS) { 0f }
    private var rollingPeaks = FloatArray(TARGET_BANDS) { MIN_PEAK }
    private var bassRollingAvg = 0f
    private var prevBassEnergy = 0f
    private var beatPulse = 0f
    private var prevFrameEnergies = FloatArray(TARGET_BANDS) { 0f }
    private var globalEnergyRef = ABSOLUTE_REF_LEVEL

    private var bandMapping: FloatArray? = null

    private class BarPhysics(var height: Float, var velocity: Float, var target: Float)
    private val barPhysics = Array(TARGET_BANDS) { BarPhysics(0f, 0f, 0f) }

    private val _barHeights = MutableStateFlow(FloatArray(TARGET_BANDS) { 0f })
    val barHeights: StateFlow<FloatArray> = _barHeights.asStateFlow()

    private val _debugInfo = MutableStateFlow(EngineDebugInfo())
    val debugInfo: StateFlow<EngineDebugInfo> = _debugInfo.asStateFlow()

    fun acquire(audioSessionId: Int): StateFlow<FloatArray> {
        lastAcquireTimeMs = System.currentTimeMillis()
        releaseGraceJob?.cancel()
        releaseGraceJob = null
        dlog("acquire() called with sessionId=$audioSessionId, consumerCount=$consumerCount, currentSession=$currentSessionId")
        if (audioSessionId <= 0) {
            dlog("acquire() rejected: sessionId <= 0")
            return barHeights
        }
        if (!registerConsumer(audioSessionId)) {
            dlog("acquire() rejected: registerConsumer returned false")
            return barHeights
        }
        visualizerRetryCount = 0
        if (processingScope == null) {
            processingScope = CoroutineScope(Dispatchers.Default)
        }
        if (processingJob == null || processingJob!!.isCompleted) {
            dlog( "acquire() starting processing loop")
            processingJob = processingScope!!.launch(Dispatchers.Default) {
                processingLoop()
            }
        }
        dlog( "acquire() success: consumerCount=$consumerCount, visualizer=${visualizer != null}")
        return barHeights
    }

    private fun registerConsumer(audioSessionId: Int): Boolean {
        if (audioSessionId <= 0) return false
        consumerCount++
        if (consumerCount == 1) {
            startVisualizer(audioSessionId)
        } else if (currentSessionId != audioSessionId) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Session changed while consumers active: $currentSessionId -> $audioSessionId")
            resetState()
            stopVisualizer()
            startVisualizer(audioSessionId)
        }
        return true
    }

    fun release() {
        dlog( "release() called: consumerCount=$consumerCount -> ${(consumerCount - 1).coerceAtLeast(0)}")
        consumerCount = (consumerCount - 1).coerceAtLeast(0)
        if (consumerCount == 0) {
            val sinceLastAcquire = System.currentTimeMillis() - lastAcquireTimeMs
            if (sinceLastAcquire < RELEASE_GRACE_MS) {
                dlog("release() grace period active (${sinceLastAcquire}ms) — scheduling delayed stop")
                if (releaseGraceJob == null) {
                    releaseGraceJob = processingScope?.launch {
                        delay(RELEASE_GRACE_MS)
                        if (consumerCount == 0) {
                            dlog("release() grace expired — stopping visualizer")
                            stopVisualizer()
                            resetState()
                            _barHeights.value = FloatArray(TARGET_BANDS) { 0f }
                        }
                    }
                }
            } else {
                dlog( "release() stopping visualizer (no consumers left)")
                stopVisualizer()
                resetState()
                _barHeights.value = FloatArray(TARGET_BANDS) { 0f }
            }
        }
    }

    fun changeSession(audioSessionId: Int) {
        if (consumerCount > 0 && currentSessionId != audioSessionId && audioSessionId > 0) {
            dlog( "Session change: $currentSessionId -> $audioSessionId")
            resetState()
            stopVisualizer()
            startVisualizer(audioSessionId)
        }
    }

    fun forceRelease() {
        releaseGraceJob?.cancel()
        releaseGraceJob = null
        consumerCount = 0
        resetState()
        stopVisualizer()
        processingJob?.cancel()
        processingJob = null
        processingScope = null
        _barHeights.value = FloatArray(TARGET_BANDS) { 0f }
    }

    private fun resetState() {
        for (i in 0 until TARGET_BANDS) {
            rollingAvgs[i] = 0f
            rollingPeaks[i] = MIN_PEAK
            prevFrameEnergies[i] = 0f
        }
        bassRollingAvg = 0f
        prevBassEnergy = 0f
        beatPulse = 0f
        globalEnergyRef = ABSOLUTE_REF_LEVEL
        bandMapping = null
        lastMagnitudes = null
        lastFftTimeMs = 0L
    }

    private fun startVisualizer(audioSessionId: Int) {
        dlog("startVisualizer() sessionId=$audioSessionId (using 0=output mix)")
        stopVisualizer()

        val viz = try {
            dlog("Creating Visualizer for output mix (session 0)...")
            val v = Visualizer(0)
            dlog("Visualizer created OK: enabled=${v.enabled}, captureSize=${v.captureSize}")
            v
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to create Visualizer for session $audioSessionId: ${e.message}", e)
            return
        }

        visualizer = viz
        currentSessionId = audioSessionId
        retrySessionId = audioSessionId
        viz.enabled = false

        val captureSizeRange = Visualizer.getCaptureSizeRange()
        val captureSize = viz.captureSize.coerceIn(256, captureSizeRange[1])
        viz.captureSize = captureSize
        val fftSize = captureSize / 2

        viz.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || fft.size < 4) return
                    val magnitudes = FloatArray(fftSize)
                    for (i in 0 until fftSize) {
                        val idx = i * 2
                        val real = if (idx < fft.size) fft[idx].toFloat() else 0f
                        val imag = if (idx + 1 < fft.size) fft[idx + 1].toFloat() else 0f
                        magnitudes[i] = sqrt(real * real + imag * imag)
                    }
                    val sampleRateHz = samplingRate / 1000f
                    val maxMag = magnitudes.maxOrNull() ?: 0f
                    val nonZeroMag = magnitudes.count { it > 0.001f }
                    if (lastMagnitudes == null && maxMag > 0.01f) {
                        dlog("onFftDataCapture: FIRST VALID DATA! fftSize=$fftSize, maxMag=$maxMag, nonZeroMag=$nonZeroMag")
                    }
                    lastSampleRate = sampleRateHz.toInt()
                    if (maxMag > 0.01f) {
                        lastMagnitudes = magnitudes
                        lastFftTimeMs = System.currentTimeMillis()
                    }
                }
            },
            Visualizer.getMaxCaptureRate() / 2,
            false,
            true,
        )

        viz.enabled = true
        visualizerStartTimeMs = System.currentTimeMillis()
        dlog("startVisualizer() complete: sessionId=$audioSessionId, captureSize=$captureSize, enabled=${viz.enabled}")
    }

    private fun stopVisualizer() {
        visualizer?.let { viz ->
            try {
                viz.enabled = false
                viz.release()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Error releasing visualizer: ${e.message}")
            }
        }
        visualizer = null
        currentSessionId = 0
    }

    private suspend fun processingLoop() {
        dlog("processingLoop() started — callback-based")
        var frameCount = 0
        try {
            while (true) {
                val now = System.currentTimeMillis()
                val fftAge = if (lastFftTimeMs > 0) now - lastFftTimeMs else Long.MAX_VALUE
                val hasRecentFft = fftAge < FFT_TIMEOUT_MS && lastMagnitudes != null

                if (hasRecentFft && lastMagnitudes != null) {
                    processFrame(lastMagnitudes!!, lastSampleRate)
                    applySmoothing()
                    if (frameCount < 10 || frameCount % 100 == 0) {
                        val maxTarget = barPhysics.maxOf { it.target }
                        val maxHeight = barPhysics.maxOf { it.height }
                        val nonZeroHeight = barPhysics.count { it.height > 0.001f }
                        dlog("processingLoop() FFT frame=$frameCount, fftAge=${fftAge}ms, maxTarget=$maxTarget, maxHeight=$maxHeight, nonZeroHeight=$nonZeroHeight")
                    }
                } else {
                    applySilence()

                    val timeSinceStart = now - visualizerStartTimeMs
                    if (visualizerStartTimeMs > 0 && timeSinceStart > VISUALIZER_RETRY_MS && lastMagnitudes == null) {
                        dlog("No FFT after ${timeSinceStart}ms — restarting Visualizer")
                        val sessionToRetry = retrySessionId
                        resetState()
                        stopVisualizer()
                        delay(500)
                        startVisualizer(sessionToRetry)
                        frameCount = 0
                        continue
                    }
                }

                frameCount++

                val heights = FloatArray(TARGET_BANDS) { barPhysics[it].height }
                _barHeights.value = heights
                if (frameCount < 10 || frameCount % 100 == 0) {
                    val maxH = heights.maxOrNull() ?: 0f
                    val nonZeroH = heights.count { it > 0.001f }
                    dlog("processingLoop() barHeights emitted: frame=$frameCount, maxH=$maxH, nonZeroH=$nonZeroH")
                }
                updateDebugInfo()

                delay(FRAME_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Processing loop error: ${e.message}")
        }
    }

    private fun applySilence() {
        for (i in barPhysics.indices) {
            barPhysics[i].target = 0f
        }
        applySpringDecay()
    }

    private fun processFrame(magnitudes: FloatArray, samplingRate: Int) {
        val nyquist = samplingRate / 2f
        val fftBinCount = magnitudes.size
        val hzPerBin = nyquist / fftBinCount

        if (bandMapping == null) {
            bandMapping = buildLogBandMapping(fftBinCount, hzPerBin)
        }

        val rawEnergies = FloatArray(TARGET_BANDS)
        val mapping = bandMapping!!

        for (band in 0 until TARGET_BANDS) {
            val startBin = mapping[band * 2].toInt().coerceIn(0, fftBinCount - 1)
            val endBin = mapping[band * 2 + 1].toInt().coerceIn(startBin, fftBinCount - 1)

            var sumSq = 0f
            var count = 0
            for (bin in startBin..endBin) {
                val mag = magnitudes[bin]
                sumSq += mag * mag
                count++
            }
            rawEnergies[band] = if (count > 0) sqrt(sumSq / count) else 0f
        }

        val intensityGain = PulseMatrixSettings.intensityGainMultiplier

        val bassEnergy = (rawEnergies[0] + rawEnergies[1] + rawEnergies[2] + rawEnergies[3]) / 4f

        val spectralFlux = if (prevBassEnergy > 0.001f) {
            ((bassEnergy - prevBassEnergy) / (prevBassEnergy + 0.001f)).coerceAtLeast(0f)
        } else 0f
        prevBassEnergy = bassEnergy

        if (spectralFlux > SPECTRAL_FLUX_THRESHOLD) {
            beatPulse = 1.0f
        } else {
            beatPulse *= BEAT_DECAY_RATE
        }

        val maxEnergy = rawEnergies.maxOrNull() ?: 0f
        if (maxEnergy > globalEnergyRef * 1.5f) {
            globalEnergyRef = globalEnergyRef * 0.7f + maxEnergy * 0.3f
        }

        for (band in 0 until TARGET_BANDS) {
            val energy = rawEnergies[band]

            rollingAvgs[band] = rollingAvgs[band] * (1f - ROLLING_AVG_ALPHA) + energy * ROLLING_AVG_ALPHA

            var normalized: Float
            if (energy < NOISE_FLOOR) {
                normalized = 0f
            } else {
                val linear = (energy - NOISE_FLOOR) / globalEnergyRef
                val softCapped = linear * SOFT_CEIL / (linear + SOFT_CEIL)
                normalized = softCapped.pow(COMPRESSION_AMOUNT)
            }

            if (band < 4 && beatPulse > 0.05f) {
                val beatBoost = beatPulse * BEAT_TRANSIENT_BOOST * (1f - band * 0.15f)
                normalized = (normalized + beatBoost).coerceAtMost(1f)
            } else if (band < 8 && beatPulse > 0.1f) {
                val midBoost = beatPulse * BEAT_TRANSIENT_BOOST * 0.3f
                normalized = (normalized + midBoost).coerceAtMost(1f)
            }

            normalized *= intensityGain

            barPhysics[band].target = normalized.coerceIn(0f, 1f)
        }

        prevFrameEnergies = rawEnergies.copyOf()
    }

    private fun applySmoothing() {
        for (i in barPhysics.indices) {
            val target = barPhysics[i].target
            val current = barPhysics[i].height

            val attack: Float
            val release: Float
            when {
                i < 4 -> {
                    attack = 0.65f
                    release = 0.38f
                }
                i < 8 -> {
                    attack = 0.55f
                    release = 0.25f
                }
                else -> {
                    attack = 0.50f
                    release = 0.22f
                }
            }

            val factor = if (target > current) attack else release
            barPhysics[i].height = current + (target - current) * factor
            barPhysics[i].height = barPhysics[i].height.coerceIn(0f, 1f)
        }
    }

    private fun applySpringDecay() {
        val anyAboveMin = barPhysics.any { it.height > 0.005f || abs(it.velocity) > 0.001f }
        if (!anyAboveMin) return

        for (bar in barPhysics) {
            bar.velocity += (bar.target - bar.height) * SPRING_STRENGTH * PAUSE_GRAVITY
            bar.velocity *= DAMPING
            bar.height += bar.velocity
            bar.height = bar.height.coerceIn(0f, 1f)

            if (bar.height < 0.002f && abs(bar.velocity) < 0.001f) {
                bar.height = 0f
                bar.velocity = 0f
                bar.target = 0f
            }
        }
    }

    private fun buildLogBandMapping(fftBinCount: Int, hzPerBin: Float): FloatArray {
        val mapping = FloatArray(TARGET_BANDS * 2)
        val logMin = Math.log(MIN_FREQ)
        val logMax = Math.log(MAX_FREQ)
        val logRange = logMax - logMin

        for (band in 0 until TARGET_BANDS) {
            val bandStartRatio = band.toFloat() / TARGET_BANDS
            val bandEndRatio = (band + 1).toFloat() / TARGET_BANDS

            val startFreq = Math.exp(logMin + bandStartRatio * logRange)
            val endFreq = Math.exp(logMin + bandEndRatio * logRange)

            var startBin = (startFreq / hzPerBin).toInt().coerceIn(0, fftBinCount - 1)
            var endBin = (endFreq / hzPerBin).toInt().coerceIn(0, fftBinCount - 1)

            if (endBin < startBin) endBin = startBin

            mapping[band * 2] = startBin.toFloat()
            mapping[band * 2 + 1] = endBin.toFloat()
        }

        return mapping
    }

    private fun updateDebugInfo() {
        val avgPeak = rollingPeaks.average().toFloat()
        val avgEnergy = rollingAvgs.average().toFloat()
        val normalizedRms = if (avgPeak > NOISE_FLOOR * 3f) {
            ((avgEnergy - NOISE_FLOOR) / (avgPeak - NOISE_FLOOR).coerceAtLeast(MIN_RANGE)).pow(COMPRESSION_AMOUNT)
        } else 0f

        val bassW = rollingAvgs.take(4).average().toFloat()
        val midW = if (rollingAvgs.size >= 8) rollingAvgs.drop(4).take(4).average().toFloat() else 0f
        val trebleW = if (rollingAvgs.size >= 12) rollingAvgs.drop(8).average().toFloat() else 0f

        _debugInfo.value = EngineDebugInfo(
            rawRms = avgEnergy,
            normalizedRms = normalizedRms,
            rollingPeak = avgPeak,
            noiseFloor = NOISE_FLOOR,
            bassEnergy = bassW,
            midEnergy = midW,
            trebleEnergy = trebleW,
            beatStrength = beatPulse,
            fftAgeMs = if (lastFftTimeMs > 0) System.currentTimeMillis() - lastFftTimeMs else Long.MAX_VALUE,
            hasValidFft = lastMagnitudes != null,
        )
    }
}
