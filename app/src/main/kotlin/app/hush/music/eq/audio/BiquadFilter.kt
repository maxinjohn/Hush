package app.hush.music.eq.audio

import app.hush.music.eq.data.FilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Biquad filter implementation for parametric EQ.
 * Supports peaking (PK), low-shelf (LSC), and high-shelf (HSC) filters.
 * Based on Robert Bristow-Johnson's Audio EQ Cookbook.
 */
class BiquadFilter(
    val sampleRate: Int,
    val frequency: Double,
    var gain: Double,
    val q: Double = 1.41,
    val filterType: FilterType = FilterType.PK,
) {
    var lastOutputLeft = 0.0
        private set
    var lastOutputRight = 0.0
        private set

    // Normalized filter coefficients
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // State variables for filtering (per channel)
    private var x1L = 0.0; private var x2L = 0.0
    private var y1L = 0.0; private var y2L = 0.0
    private var x1R = 0.0; private var x2R = 0.0
    private var y1R = 0.0; private var y2R = 0.0

    init {
        calculateCoefficients()
    }

    private fun calculateCoefficients() {
        when (filterType) {
            FilterType.PK -> calculatePeakingCoefficients()
            FilterType.LSC -> calculateLowShelfCoefficients()
            FilterType.HSC -> calculateHighShelfCoefficients()
        }
    }

    private fun calculatePeakingCoefficients() {
        val A = 10.0.pow(gain / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        val a0 = 1.0 + alpha / A
        b0 = (1.0 + alpha * A) / a0
        b1 = (-2.0 * cosOmega) / a0
        b2 = (1.0 - alpha * A) / a0
        a1 = (-2.0 * cosOmega) / a0
        a2 = (1.0 - alpha / A) / a0
    }

    private fun calculateLowShelfCoefficients() {
        val A = sqrt(10.0.pow(gain / 20.0))
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / 2.0 * sqrt((A + 1.0 / A) * (1.0 / 1.0 - 1.0) + 2.0)
        val sqrtA = sqrt(A)
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        val a0 = A + 1.0 + (A - 1.0) * cosOmega + twoSqrtAAlpha
        b0 = (A * (A + 1.0 - (A - 1.0) * cosOmega + twoSqrtAAlpha)) / a0
        b1 = (2.0 * A * (A - 1.0 - (A + 1.0) * cosOmega)) / a0
        b2 = (A * (A + 1.0 - (A - 1.0) * cosOmega - twoSqrtAAlpha)) / a0
        a1 = (-2.0 * (A - 1.0 + (A + 1.0) * cosOmega)) / a0
        a2 = (A + 1.0 + (A - 1.0) * cosOmega - twoSqrtAAlpha) / a0
    }

    private fun calculateHighShelfCoefficients() {
        val A = sqrt(10.0.pow(gain / 20.0))
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / 2.0 * sqrt((A + 1.0 / A) * (1.0 / 1.0 - 1.0) + 2.0)
        val sqrtA = sqrt(A)
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        val a0 = A + 1.0 - (A - 1.0) * cosOmega + twoSqrtAAlpha
        b0 = (A * (A + 1.0 + (A - 1.0) * cosOmega + twoSqrtAAlpha)) / a0
        b1 = (-2.0 * A * (A - 1.0 + (A + 1.0) * cosOmega)) / a0
        b2 = (A * (A + 1.0 + (A - 1.0) * cosOmega - twoSqrtAAlpha)) / a0
        a1 = (2.0 * (A - 1.0 - (A + 1.0) * cosOmega)) / a0
        a2 = (A + 1.0 - (A - 1.0) * cosOmega - twoSqrtAAlpha) / a0
    }

    /**
     * Process a single (mono) sample.
     */
    fun processSample(input: Double): Double {
        val output = b0 * input + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L; x1L = input
        y2L = y1L; y1L = output
        return output
    }

    /**
     * Process stereo samples in-place.
     */
    fun processStereo(inputLeft: Double, inputRight: Double) {
        // Left channel
        val outputLeft = b0 * inputLeft + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        x2L = x1L; x1L = inputLeft
        y2L = y1L; y1L = outputLeft
        lastOutputLeft = outputLeft

        // Right channel
        val outputRight = b0 * inputRight + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        x2R = x1R; x1R = inputRight
        y2R = y1R; y1R = outputRight
        lastOutputRight = outputRight
    }

    /**
     * Update gain and recalculate coefficients without resetting state history.
     * Enables zipper-noise-free transitions.
     */
    fun updateGain(newGain: Double) {
        if (gain == newGain) return
        gain = newGain
        calculateCoefficients()
    }

    /**
     * Reset filter state (clears history).
     */
    fun reset() {
        x1L = 0.0; x2L = 0.0; y1L = 0.0; y2L = 0.0
        x1R = 0.0; x2R = 0.0; y1R = 0.0; y2R = 0.0
    }
}
