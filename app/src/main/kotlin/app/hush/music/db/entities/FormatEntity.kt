/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.math.roundToInt

@Entity(tableName = "format")
data class FormatEntity(
    @PrimaryKey val id: String,
    val itag: Int,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val contentLength: Long,
    val loudnessDb: Double?,
    val perceptualLoudnessDb: Double? = null,
    val playbackUrl: String?,
)

fun FormatEntity.containerLabel(): String = mimeType.substringAfter("/").substringBefore(";").uppercase()

fun FormatEntity.codecLabel(): String {
    val rawCodec = codecs.ifBlank { mimeType.substringAfter("/") }.uppercase()
    val rawMime = mimeType.substringAfter("/").substringBefore(";").uppercase()

    return when {
        rawCodec.contains("FLAC") || rawCodec.contains("ALAC") -> "Lossless"
        rawCodec.contains("OPUS") -> "OPUS"
        rawCodec.contains("AAC") || rawCodec.contains("MP4A") -> "AAC"
        rawCodec.contains("VORBIS") -> "VORBIS"
        rawMime.contains("OPUS") -> "OPUS"
        rawMime.contains("AAC") || rawMime.contains("MP4A") -> "AAC"
        rawMime.contains("VORBIS") -> "VORBIS"
        rawMime.isNotBlank() -> rawMime
        else -> rawCodec
    }
}

/**
 * The rate of this format in kbps, or `Unknown` when the row cannot carry one.
 *
 * A rate below 1 kbps cannot survive this rendering. One cached file was stored as `705` - a
 * `sampleRate * bitDepth * 2` figure that reached the field in kbps while every reader divides by
 * 1000 - and drew `0 kbps`, which reads as a player that does not know what it is playing. No audio
 * bitrate is that small, so a value below [MIN_RENDERABLE_BITRATE_BPS] is a unit mistake rather than
 * a rate, and the row says nothing instead: the player omits an `Unknown` rate rather than printing
 * it.
 */
fun FormatEntity.formattedBitrate(): String =
    if (bitrate >= MIN_RENDERABLE_BITRATE_BPS) "${bitrate / 1000} kbps" else "Unknown"

/**
 * The lowest rate [formattedBitrate] renders as a whole number of kbps.
 *
 * Read off the field's own unit, not chosen: below 1 kbps the rendering rounds every value to `0`.
 */
private const val MIN_RENDERABLE_BITRATE_BPS = 1000

fun FormatEntity.formattedSampleRate(): String? =
    sampleRate?.takeIf { it > 0 }?.let {
        "${(it / 100.0).roundToInt() / 10.0} kHz"
    }

fun FormatEntity.formattedFileSize(): String = if (contentLength > 0) "${(contentLength / 1024.0 / 1024.0).roundToInt()} MB" else ""

enum class RatePriority {
    BITRATE_FIRST,
    SAMPLE_RATE_FIRST,
}

fun FormatEntity.autoRateDisplay(priority: RatePriority = RatePriority.BITRATE_FIRST): String {
    // The same condition [formattedBitrate] renders on, so a rate it would refuse cannot be picked
    // here either - and the sample rate is shown in its place when there is one.
    val hasBitrate = bitrate >= MIN_RENDERABLE_BITRATE_BPS
    val hasSampleRate = sampleRate != null && sampleRate > 0

    return when (priority) {
        RatePriority.BITRATE_FIRST -> {
            when {
                hasBitrate -> formattedBitrate()
                hasSampleRate -> formattedSampleRate() ?: "Unknown"
                else -> "Unknown"
            }
        }

        RatePriority.SAMPLE_RATE_FIRST -> {
            when {
                hasSampleRate -> formattedSampleRate() ?: "Unknown"
                hasBitrate -> formattedBitrate()
                else -> "Unknown"
            }
        }
    }
}
