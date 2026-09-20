/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context

fun Context.isLowRamDevice(): Boolean {
    val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return activityManager?.isLowRamDevice == true
}

/**
 * How much work a device can absorb before frames start dropping.
 *
 * [Context.isLowRamDevice] alone misses most cheap hardware. An Android 11 car head
 * unit or a low-end tablet commonly ships 1.5-2 GB of RAM *without* the low-RAM flag,
 * yet its per-process heap limit ([ActivityManager.memoryClass]) is 128-192 MB - and
 * that limit, not the flag, is what decides how long the app survives before the
 * system kills it.
 */
enum class DeviceTier {
    LOW_RAM,
    CONSTRAINED,
    STANDARD,
}

/** Per-process heap limits that describe hardware rather than a platform flag. */
internal const val LOW_RAM_HEAP_LIMIT_MB = 128
internal const val CONSTRAINED_HEAP_LIMIT_MB = 192

/**
 * Classifies a device from the two things the platform exposes. A [memoryClassMb] of
 * zero means no ActivityManager answered; treat that as unknown rather than as the
 * smallest device, so a lookup failure can never slow a capable phone down.
 */
internal fun deviceTierOf(
    isLowRam: Boolean,
    memoryClassMb: Int,
): DeviceTier =
    when {
        isLowRam -> DeviceTier.LOW_RAM
        memoryClassMb in 1..LOW_RAM_HEAP_LIMIT_MB -> DeviceTier.LOW_RAM
        memoryClassMb in 1..CONSTRAINED_HEAP_LIMIT_MB -> DeviceTier.CONSTRAINED
        else -> DeviceTier.STANDARD
    }

/**
 * How often values that only have to *look* continuous are refreshed.
 *
 * The standard cadence is exactly the timing the app has always used, so capable
 * devices behave byte-for-byte as before. The slower tiers only apply to hardware
 * that cannot present those frames anyway - the work was being thrown away as
 * dropped frames, on the same thread that has to service playback.
 */
data class MotionCadence(
    val wordLyricsTickMs: Long,
    val lineLyricsTickMs: Long,
    val playbackPositionTickMs: Long,
    val visualizerFrameMs: Long,
)

val STANDARD_CADENCE =
    MotionCadence(
        wordLyricsTickMs = 16L,
        lineLyricsTickMs = 50L,
        playbackPositionTickMs = 100L,
        visualizerFrameMs = 60L,
    )
val CONSTRAINED_CADENCE =
    MotionCadence(
        wordLyricsTickMs = 24L,
        lineLyricsTickMs = 66L,
        playbackPositionTickMs = 150L,
        visualizerFrameMs = 80L,
    )
val LOW_RAM_CADENCE =
    MotionCadence(
        wordLyricsTickMs = 33L,
        lineLyricsTickMs = 100L,
        playbackPositionTickMs = 250L,
        visualizerFrameMs = 100L,
    )

internal fun cadenceFor(tier: DeviceTier): MotionCadence =
    when (tier) {
        DeviceTier.LOW_RAM -> LOW_RAM_CADENCE
        DeviceTier.CONSTRAINED -> CONSTRAINED_CADENCE
        DeviceTier.STANDARD -> STANDARD_CADENCE
    }

/**
 * An artwork crossfade keeps two decoded bitmaps alive at once. That is a fine trade
 * on a phone with a large heap and a poor one on a device already close to its limit,
 * where the app's own settings screen also defaults animations off.
 */
internal fun artworkCrossfadeFor(tier: DeviceTier): Boolean = tier != DeviceTier.LOW_RAM

/**
 * What the app gives back at a given memory-pressure level.
 *
 * Pulled out of [android.app.Application.onTrimMemory] because it was wrong there: the
 * branch that released BotGuard matched `level >= TRIM_MEMORY_RUNNING_LOW`, which the
 * system delivers *while the app is on screen*. Under ordinary foreground pressure the
 * app therefore destroyed its warm WebView engine, and the next playback had to rebuild
 * it - on precisely the memory-tight devices that cannot afford that. The follow-up
 * branch was unreachable, since RUNNING_LOW (10) is below UI_HIDDEN (20).
 */
data class MemoryTrimAction(
    val dropImageCache: Boolean,
    val releaseBotGuardEngine: Boolean,
)

internal fun memoryTrimActionFor(level: Int): MemoryTrimAction =
    MemoryTrimAction(
        // Decoded artwork is the cheapest thing to give back, so it goes at any pressure.
        dropImageCache = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        // A WebView is expensive to rebuild, so it is only surrendered once the UI is
        // genuinely gone - background, moderate or complete.
        releaseBotGuardEngine = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
    )

/**
 * Process-wide memo of the device's classification. Reading it is a field lookup, but
 * it is read from composition, so it is cached once instead of on every frame.
 */
object DeviceProfile {
    @Volatile private var cachedTier: DeviceTier? = null
    @Volatile private var cachedCadence: MotionCadence? = null

    fun tier(context: Context): DeviceTier =
        cachedTier ?: synchronized(this) {
            cachedTier ?: readTier(context).also { cachedTier = it }
        }

    fun cadence(context: Context): MotionCadence =
        cachedCadence ?: synchronized(this) {
            cachedCadence ?: cadenceFor(tier(context)).also { cachedCadence = it }
        }

    fun artworkCrossfade(context: Context): Boolean = artworkCrossfadeFor(tier(context))

    private fun readTier(context: Context): DeviceTier {
        val activityManager =
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return deviceTierOf(
            isLowRam = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass ?: 0,
        )
    }

    /** Test seam: forget the memoized classification. */
    internal fun resetForTests() {
        cachedTier = null
        cachedCadence = null
    }
}
