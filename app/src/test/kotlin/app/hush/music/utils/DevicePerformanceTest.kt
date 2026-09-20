/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cover for the device-performance classification.
 *
 * The contract that matters is asymmetric: a device must never be *under*-estimated into
 * slower motion than it can draw, and the standard tier must be exactly the timing the app
 * used before this existed, so a capable phone cannot regress by being classified.
 */
class DevicePerformanceTest {
    @Test
    fun `the low-ram flag alone marks a device low ram`() {
        assertEquals(DeviceTier.LOW_RAM, deviceTierOf(isLowRam = true, memoryClassMb = 512))
    }

    @Test
    fun `a tiny heap marks a device low ram even without the flag`() {
        // The case this exists for: a head unit that ships 1.5 GB of RAM but caps the
        // process at 128 MB, and never sets ro.config.low_ram.
        assertEquals(DeviceTier.LOW_RAM, deviceTierOf(isLowRam = false, memoryClassMb = 128))
    }

    @Test
    fun `a small heap marks a device constrained`() {
        assertEquals(DeviceTier.CONSTRAINED, deviceTierOf(isLowRam = false, memoryClassMb = 192))
        assertEquals(DeviceTier.CONSTRAINED, deviceTierOf(isLowRam = false, memoryClassMb = 129))
    }

    @Test
    fun `an unknown heap size is treated as capable, never as slow`() {
        // No ActivityManager answered. Guessing "slow" here would silently degrade a
        // fast device, so the classifier must fall back to the untouched cadence.
        assertEquals(DeviceTier.STANDARD, deviceTierOf(isLowRam = false, memoryClassMb = 0))
    }

    @Test
    fun `a roomy heap is standard`() {
        assertEquals(DeviceTier.STANDARD, deviceTierOf(isLowRam = false, memoryClassMb = 256))
        assertEquals(DeviceTier.STANDARD, deviceTierOf(isLowRam = false, memoryClassMb = 512))
    }

    @Test
    fun `the standard cadence is the timing the app always used`() {
        assertEquals(16L, STANDARD_CADENCE.wordLyricsTickMs)
        assertEquals(50L, STANDARD_CADENCE.lineLyricsTickMs)
        assertEquals(100L, STANDARD_CADENCE.playbackPositionTickMs)
        assertEquals(60L, STANDARD_CADENCE.visualizerFrameMs)
    }

    @Test
    fun `every slower tier is bounded by the standard one`() {
        // Weaker tiers may only ever ask for *less* work than standard; a tier that polled
        // faster than standard would defeat the point of classifying the device.
        for (tier in listOf(DeviceTier.CONSTRAINED, DeviceTier.LOW_RAM)) {
            val cadence = cadenceFor(tier)
            assertTrue("$tier word lyrics", cadence.wordLyricsTickMs >= STANDARD_CADENCE.wordLyricsTickMs)
            assertTrue("$tier line lyrics", cadence.lineLyricsTickMs >= STANDARD_CADENCE.lineLyricsTickMs)
            assertTrue("$tier position", cadence.playbackPositionTickMs >= STANDARD_CADENCE.playbackPositionTickMs)
            assertTrue("$tier visualizer", cadence.visualizerFrameMs >= STANDARD_CADENCE.visualizerFrameMs)
        }
    }

    @Test
    fun `a weaker tier never polls faster than the tier above it`() {
        assertTrue(LOW_RAM_CADENCE.wordLyricsTickMs >= CONSTRAINED_CADENCE.wordLyricsTickMs)
        assertTrue(LOW_RAM_CADENCE.playbackPositionTickMs >= CONSTRAINED_CADENCE.playbackPositionTickMs)
        assertTrue(LOW_RAM_CADENCE.visualizerFrameMs >= CONSTRAINED_CADENCE.visualizerFrameMs)
    }

    @Test
    fun `artwork crossfade is only dropped on the smallest devices`() {
        assertTrue(artworkCrossfadeFor(DeviceTier.STANDARD))
        assertTrue(artworkCrossfadeFor(DeviceTier.CONSTRAINED))
        assertFalse(artworkCrossfadeFor(DeviceTier.LOW_RAM))
    }

    @Test
    fun `ordinary foreground pressure never costs the warm botguard engine`() {
        // This is the regression: RUNNING_LOW arrives while the app is on screen, and
        // releasing the engine there made the very next playback rebuild a WebView.
        val foreground = memoryTrimActionFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue("foreground pressure should still free artwork", foreground.dropImageCache)
        assertFalse("foreground pressure must not release the engine", foreground.releaseBotGuardEngine)
    }

    @Test
    fun `a hidden ui releases the engine and still frees artwork`() {
        val hidden = memoryTrimActionFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertTrue(hidden.dropImageCache)
        assertTrue(hidden.releaseBotGuardEngine)
    }

    @Test
    fun `more severe background levels keep releasing the engine`() {
        for (level in
            listOf(
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                ComponentCallbacks2.TRIM_MEMORY_MODERATE,
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            )
        ) {
            assertTrue("level $level", memoryTrimActionFor(level).releaseBotGuardEngine)
            assertTrue("level $level", memoryTrimActionFor(level).dropImageCache)
        }
    }

    @Test
    fun `a level below running low frees nothing`() {
        // TRIM_MEMORY_RUNNING_MODERATE (5) is the mildest signal the platform sends and
        // does not ask the app to release anything.
        val none = memoryTrimActionFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertFalse(none.dropImageCache)
        assertFalse(none.releaseBotGuardEngine)
    }

    @Test
    fun `running critical frees artwork without costing the engine`() {
        val critical = memoryTrimActionFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertTrue(critical.dropImageCache)
        assertFalse(critical.releaseBotGuardEngine)
    }

    @Test
    fun `every tier has a cadence`() {
        assertEquals(STANDARD_CADENCE, cadenceFor(DeviceTier.STANDARD))
        assertEquals(CONSTRAINED_CADENCE, cadenceFor(DeviceTier.CONSTRAINED))
        assertEquals(LOW_RAM_CADENCE, cadenceFor(DeviceTier.LOW_RAM))
    }
}
