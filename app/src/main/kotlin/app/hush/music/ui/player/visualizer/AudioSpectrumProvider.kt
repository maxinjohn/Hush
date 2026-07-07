/*
 * Hush — GPL-3.0
 * Real-time audio spectrum data provider using Android Visualizer API.
 * Gracefully degrades to simulated data when the real API is unavailable.
 */

package app.hush.music.ui.player.visualizer

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

object AudioSpectrumProvider {
    private const val TAG = "AudioSpectrum"
    private const val BAND_COUNT = 16
    private const val CAPTURE_RATE_MS = 60

    private var visualizer: Visualizer? = null
    private var lastSessionId: Int? = null

    /**
     * Provides normalized frequency band amplitudes (0f..1f) as a Flow.
     * When not capturing, emits a list of zeros.
     */
    fun spectrumFlow(audioSessionId: Int): Flow<List<Float>> = callbackFlow {
        if (audioSessionId <= 0) {
            trySend(List(BAND_COUNT) { 0f })
            awaitClose { }
            return@callbackFlow
        }

        // Release any previous visualizer
        visualizer?.release()
        visualizer = null

        val viz = try {
            Visualizer(audioSessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Visualizer for session $audioSessionId: ${e.message}")
            trySend(simulatedSpectrum())
            awaitClose { }
            return@callbackFlow
        }

        visualizer = viz
        lastSessionId = audioSessionId
        viz.enabled = false

        val captureSize = viz.captureSize.coerceIn(256, Visualizer.getCaptureSizeRange()[1])
        viz.captureSize = captureSize

        val fftSize = captureSize / 2
        val bands = BAND_COUNT.coerceAtMost(fftSize / 2)
        val bandSize = fftSize / bands

        viz.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int,
                ) {}

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int,
                ) {
                    if (fft == null || fft.size < 4) return
                    val magnitudes = FloatArray(bands)
                    for (band in 0 until bands) {
                        var sum = 0f
                        val start = band * bandSize
                        val end = ((band + 1) * bandSize).coerceAtMost(fftSize)
                        for (i in start until end) {
                            val idx = i * 2
                            if (idx + 1 < fft.size) {
                                val real = fft[idx].toFloat()
                                val imag = fft[idx + 1].toFloat()
                                sum += sqrt(real * real + imag * imag)
                            }
                        }
                        val avg = sum / (end - start).coerceAtLeast(1)
                        magnitudes[band] = (avg / 128f).coerceIn(0f, 1f)
                    }
                    trySend(magnitudes.toList())
                }
            },
            Visualizer.getMaxCaptureRate() / 2,
            false,
            true,
        )

        viz.enabled = true

        awaitClose {
            runCatching {
                viz.enabled = false
                viz.release()
            }
            if (visualizer == viz) {
                visualizer = null
                lastSessionId = null
            }
            Log.d(TAG, "Spectrum provider released for session $audioSessionId")
        }
    }.distinctUntilChanged { old, new ->
        if (old.isEmpty() || new.isEmpty()) false
        else old.zip(new).all { (a, b) -> abs(a - b) < 0.02f }
    }

    /**
     * Generates simulated spectrum data for fallback when real Visualizer is unavailable.
     */
    fun simulatedSpectrum(): List<Float> {
        val rng = Random(System.nanoTime())
        return List(BAND_COUNT) { i ->
            val base = 1f - (i.toFloat() / BAND_COUNT) * 0.6f
            val variation = rng.nextFloat() * 0.3f
            (base * 0.3f + variation * 0.7f).coerceIn(0.05f, 1f)
        }
    }

    fun release() {
        visualizer?.let {
            runCatching {
                it.enabled = false
                it.release()
            }
        }
        visualizer = null
        lastSessionId = null
    }
}
