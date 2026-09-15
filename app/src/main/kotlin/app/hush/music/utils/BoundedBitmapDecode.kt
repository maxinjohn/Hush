/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Decodes artwork at a bounded resolution.
 *
 * Artwork served for playback is routinely 1080x1080 or larger and every display
 * site here (notification, widget, player) only ever needs a few hundred pixels.
 * Decoding it whole allocates `width * height * 4` bytes of ARGB_8888: ~8 MB for
 * 1440x1440, ~36 MB for a 3000x3000 upload, and more than that again because the
 * old call sites then scaled and copied the decoded bitmap. Two of those in flight
 * (notification + widget while playback starts) is enough to OOM a low-RAM device,
 * which is what the reported launch/search crashes were.
 *
 * Sampling is computed from a bounds-only first pass, so the full-size bitmap is
 * never allocated. The result is always a software ARGB_8888 bitmap: hardware
 * bitmaps cannot be drawn in a notification (the Android 15 crash) and cannot be
 * re-encoded to artwork bytes.
 */
internal fun decodeBoundedBitmap(data: ByteArray, maxSizePx: Int): Bitmap? {
    if (data.isEmpty()) return null
    val target = maxSizePx.coerceAtLeast(1)

    val bounds =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, target, target)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}

/** File-backed counterpart of [decodeBoundedBitmap]. */
internal fun decodeBoundedBitmap(file: File, maxSizePx: Int): Bitmap? {
    if (!file.exists() || file.length() == 0L) return null
    val target = maxSizePx.coerceAtLeast(1)

    val bounds =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, target, target)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    requestedWidth: Int,
    requestedHeight: Int,
): Int {
    var sampleSize = 1
    val width = options.outWidth
    val height = options.outHeight

    if (height > requestedHeight || width > requestedWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / sampleSize >= requestedHeight && halfWidth / sampleSize >= requestedWidth) {
            sampleSize *= 2
        }
    }

    return sampleSize.coerceAtLeast(1)
}
