package com.example.writersblock.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a cropped screenshot lives between being captured and being committed to a note.
 *
 * Captures land here first because the drawer may not have a destination yet — scratch mode has no
 * note, and the user may never save. Committing moves the file into the note's `.assets` directory
 * via [FileRepository.moveIntoAssets]; anything left behind is swept by [pruneStaging].
 *
 * Kept separate from [FileRepository] so that note-format logic stays free of `android.graphics` and
 * remains unit-testable without an Android runtime.
 */
object CaptureStore {

    private const val STAGING_DIR = "capture_staging"

    /** Long-edge cap, so a note full of captures cannot grow without bound. */
    const val MAX_DIMENSION = 1600

    private const val DEFAULT_RETENTION_DAYS = 7

    fun stagingDir(context: Context): File {
        val dir = File(context.cacheDir, STAGING_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Shrinks [bitmap] so its long edge is at most [maxDimension].
     *
     * Returns the original instance when it is already small enough, so callers must not assume they
     * received a fresh bitmap to recycle.
     */
    fun downscale(bitmap: Bitmap, maxDimension: Int = MAX_DIMENSION): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / longEdge
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, /* filter = */ true)
    }

    /**
     * Writes [bitmap] to staging as a PNG and returns the file, or null on failure.
     *
     * PNG rather than JPEG because screen crops are flat-coloured and text-heavy, where PNG is both
     * smaller and lossless.
     */
    fun stage(context: Context, bitmap: Bitmap): File? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val file = File(stagingDir(context), "capture_$stamp.png")
        return try {
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    file.delete()
                    return null
                }
            }
            if (file.length() > 0) file else null
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** Deletes staged captures that were never committed, once they are older than [days]. */
    fun pruneStaging(context: Context, days: Int = DEFAULT_RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        stagingDir(context).listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    /**
     * Whether [bitmap] is uniformly near-black, which in practice means the screenshot failed.
     *
     * Android returns a black frame for any window with `FLAG_SECURE` — banking apps, some chat apps,
     * DRM video. The capture "succeeds", OCR finds nothing and the user is left with a black rectangle
     * and no explanation, which reads as a bug in this app rather than as the other app refusing.
     * Sampling on a grid is enough: a genuinely black screenshot is black everywhere, and a real screen
     * of any content has something in it.
     */
    fun looksBlank(bitmap: Bitmap, threshold: Int = 12): Boolean {
        if (bitmap.width == 0 || bitmap.height == 0) return true

        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var samples = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > threshold || g > threshold || b > threshold) return false
                samples++
                x += stepX
            }
            y += stepY
        }
        return samples > 0
    }
}
