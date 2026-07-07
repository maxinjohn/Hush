/*
 * Hush (2026)
 * Player visualizer overlay — a self-contained composable that creates and
 * manages Android's [android.media.audiofx.Visualizer] based on the ExoPlayer's
 * audio session ID, then renders the chosen [VisualizerStyle].
 *
 * This composable follows Compose lifecycle: it attaches when entering
 * composition and releases when leaving, so there are no dangling visualizers.
 *
 * This is a new feature not present in the parent ArchiveTune project.
 */

package app.hush.music.ui.player

import android.Manifest
import android.media.audiofx.Visualizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.hush.music.playback.VisualizerBands
import app.hush.music.playback.VisualizerStyle
import app.hush.music.playback.VisualizerColorSource
import app.hush.music.playback.VisualizerWaveform
import app.hush.music.ui.component.AudioVisualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// Global tracker to prevent duplicate Visualizer instances on the same audio session.
// Android only allows one Visualizer per audio session; creating a second causes crashes.
// Reference-counted map (sessionId -> count) prevents race conditions during recomposition
// (e.g. player theme changes) where old finally blocks can race with new initializations.
private val activeVisualizerSessionRefs = mutableMapOf<Int, Int>()

/**
 * Renders a real-time audio visualizer over the player thumbnail / backdrop.
 *
 * @param audioSessionId  The ExoPlayer's [androidx.media3.exoplayer.ExoPlayer.audioSessionId].
 * @param style           Which visualizer style to display.
 * @param enabled         Whether the visualizer is active. When false nothing is drawn.
 * @param colorSource     Where the visualizer gets its colors from.
 * @param artworkUrl      Current album artwork URL (used when colorSource is ARTWORK).
 * @param dimmed          Lower opacity, suitable for background layering.
 * @param accentColor     Optional accent color; defaults to theme primary.
 * @param sensitivity     Amplification factor for FFT magnitudes (0.1f..3.0f). 1.0 = default.
 * @param opacityOverride Override the dimmed opacity. When null, uses 0.35f for dimmed/1.0f for normal.
 * @param modifier        Composable modifier.
 */
@Composable
fun PlayerVisualizerOverlay(
    audioSessionId: Int,
    style: VisualizerStyle,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    colorSource: VisualizerColorSource = VisualizerColorSource.THEME,
    artworkUrl: String? = null,
    dimmed: Boolean = false,
    accentColor: Color? = null,
    sensitivity: Float = 1.0f,
    opacityOverride: Float? = null,
) {
    val context = LocalContext.current
    
    // Capture state so the LaunchedEffect picks up changes
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSessionId by rememberUpdatedState(audioSessionId)
    val currentSensitivity by rememberUpdatedState(sensitivity)

    // Peak tracking for bar-style visualizer (EMA decay)
    val peakHold = remember { FloatArray(16) }
    val smoothedBands = remember { FloatArray(16) }

    // Band and waveform state shared between the capture loop and UI
    var bands by remember { mutableStateOf(VisualizerBands(FloatArray(16), FloatArray(16))) }
    var waveform by remember { mutableStateOf(VisualizerWaveform(FloatArray(64))) }

    // Capture loop — manages Visualizer lifecycle entirely inside the coroutine
    LaunchedEffect(audioSessionId, enabled) {
        if (!currentEnabled || currentSessionId <= 0) return@LaunchedEffect
        
        // Capture session ID now — rememberUpdatedState may change value by the time finally runs
        val sessionId = currentSessionId
        
        // Check runtime RECORD_AUDIO permission — required for Visualizer API
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return@LaunchedEffect

        // Prevent duplicate Visualizer on same session — Android does not allow it
        // Use reference counting so recomposition (theme change, etc.) works correctly
        synchronized(activeVisualizerSessionRefs) {
            val count = activeVisualizerSessionRefs.getOrDefault(sessionId, 0)
            if (count > 0) return@LaunchedEffect
            activeVisualizerSessionRefs[sessionId] = 1
        }

        val vis =
            withContext(Dispatchers.Main) {
                createVisualizer(sessionId)
            }
        if (vis == null) {
            synchronized(activeVisualizerSessionRefs) { activeVisualizerSessionRefs.remove(sessionId) }
            return@LaunchedEffect
        }

        try {
            // Must disable first: setCaptureSize() requires STATE_INITIALIZED (1),
            // not STATE_ENABLED (2). Some devices auto-enable Visualizer on creation.
            vis.enabled = false
            val captureSize = vis.setCaptureSize(256)
            if (captureSize != Visualizer.SUCCESS) {
                vis.release()
                synchronized(activeVisualizerSessionRefs) { activeVisualizerSessionRefs.remove(sessionId) }
                return@LaunchedEffect
            }
            vis.enabled = true

            val fftBuffer = ByteArray(256)
            val waveBuffer = ByteArray(256)

            while (isActive && currentEnabled) {
                try {
                    // Read FFT
                    val fftResult = vis.getFft(fftBuffer)
                    if (fftResult == Visualizer.SUCCESS) {
                        val processed = processFftToBands(fftBuffer, 16, smoothedBands, peakHold, currentSensitivity)
                        bands = processed
                    }

                    // Read waveform
                    val waveResult = vis.getWaveForm(waveBuffer)
                    if (waveResult == Visualizer.SUCCESS) {
                        waveform = processWaveformToSamples(waveBuffer, 64)
                    }
                } catch (_: Exception) {
                    break
                }
                kotlinx.coroutines.delay(60L)
            }
        } finally {
            runCatching {
                vis.enabled = false
                vis.release()
            }
            synchronized(activeVisualizerSessionRefs) { activeVisualizerSessionRefs.remove(sessionId) }
        }
    }

    // Extract palette colors from artwork when ARTWORK source is active
    val artworkPalette =
        if (enabled && colorSource == VisualizerColorSource.ARTWORK) {
            rememberArtworkPaletteColors(artworkUrl)
        } else null

    // Render
    if (enabled && audioSessionId > 0) {
        AudioVisualizer(
            bands = bands,
            waveform = waveform,
            style = style,
            modifier = modifier,
            paletteColors = artworkPalette,
            accentColor = if (colorSource == VisualizerColorSource.ACCENT) accentColor else null,
            dimmed = dimmed,
            opacityOverride = opacityOverride,
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun createVisualizer(sessionId: Int): Visualizer? =
    try {
        Visualizer(sessionId)
    } catch (_: Exception) {
        null
    }

/**
 * Convert raw FFT bytes into logarithmic-spaced frequency bands (0..1)
 * with exponential moving average smoothing and peak hold decay.
 */
private fun processFftToBands(
    fft: ByteArray,
    bandCount: Int,
    smoothedBands: FloatArray,
    peakHold: FloatArray,
    sensitivity: Float = 1.0f,
): VisualizerBands {
    val numBins = (fft.size / 2) - 1
    if (numBins < 2) return VisualizerBands(FloatArray(bandCount), FloatArray(bandCount))

    // Magnitudes per bin
    val magnitudes = FloatArray(numBins)
    var maxMag = 0f
    for (i in 0 until numBins) {
        val idx = 2 + i * 2
        if (idx + 1 >= fft.size) break
        val real = fft[idx].toInt().toFloat()
        val imag = fft[idx + 1].toInt().toFloat()
        val mag = sqrt(real * real + imag * imag)
        magnitudes[i] = mag
        if (mag > maxMag) maxMag = mag
    }

    // Prevent divide-by-zero
    val normFactor = (if (maxMag > 0.001f) 1f / maxMag else 1f) * sensitivity

    // Map to logarithmic bands
    val rawBands = FloatArray(bandCount)
    val logMax = kotlin.math.log10(numBins.coerceAtLeast(1).toFloat())

    for (band in 0 until bandCount) {
        val logLow = logMax * (band.toFloat() / bandCount)
        val logHigh = logMax * ((band + 1).toFloat() / bandCount)
        val binLow = (10f.pow(logLow)).toInt().coerceIn(0, numBins - 1)
        val binHigh = (10f.pow(logHigh)).toInt().coerceIn(binLow + 1, numBins)

        var sum = 0f
        var count = 0
        for (bin in binLow until binHigh) {
            if (bin < magnitudes.size) {
                sum += magnitudes[bin]
                count++
            }
        }
        val avg = if (count > 0) sum / count else 0f
        rawBands[band] = (avg * normFactor).coerceIn(0f, 1f).pow(0.65f)
    }

    // Apply EMA smoothing
    val smoothFactor = 0.40f
    val normalized = FloatArray(bandCount)
    for (i in 0 until bandCount) {
        smoothedBands[i] = smoothedBands[i] * (1f - smoothFactor) + rawBands[i] * smoothFactor
        normalized[i] = smoothedBands[i]
    }

    // Update peak hold with decay
    val peakDecay = 0.90f
    for (i in 0 until bandCount) {
        peakHold[i] = maxOf(normalized[i], peakHold[i] * peakDecay)
    }

    return VisualizerBands(bands = normalized, peakBands = peakHold.copyOf())
}

/**
 * Downsample raw waveform bytes into normalized (0..1) float samples.
 */
private fun processWaveformToSamples(
    waveBytes: ByteArray,
    sampleCount: Int,
): VisualizerWaveform {
    val actualSize = min(waveBytes.size, waveBytes.size)
    val samples = FloatArray(sampleCount)
    val step = maxOf(actualSize / sampleCount, 1)

    for (i in 0 until sampleCount) {
        val idx = (i * step).coerceAtMost(actualSize - 1)
        // Normalize [-128, 127] to [0, 1]
        samples[i] = ((waveBytes[idx].toInt() + 128).toFloat() / 255f).coerceIn(0f, 1f)
    }

    return VisualizerWaveform(samples)
}
