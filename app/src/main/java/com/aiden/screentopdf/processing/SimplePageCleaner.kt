package com.aiden.screentopdf.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.aiden.screentopdf.core.CaptureRegion
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.Corners
import com.aiden.screentopdf.core.PageCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A basic page cleaner for phones where the OpenCV library does not load.
 *
 * It cannot straighten a slanted page. If the user placed the frame on the record
 * screen, it crops to the box around those corners. It adds a touch of contrast so text
 * stands out, keeps the colours, and limits the size to [MAX_OUTPUT] pixels on
 * the long side. If anything goes wrong, it gives back the page unchanged.
 */
class SimplePageCleaner : PageCleaner {

    override suspend fun clean(page: CapturedPage): CapturedPage = withContext(Dispatchers.Default) {
        try {
            val original = page.bitmap
            // If the user placed the frame on the record screen, keep only that part.
            val source = cropToRegion(original, CaptureRegion.corners)
            val longSide = max(source.width, source.height)
            val scale = if (longSide > MAX_OUTPUT) MAX_OUTPUT.toFloat() / longSide else 1f
            val outW = max(1, (source.width * scale).roundToInt())
            val outH = max(1, (source.height * scale).roundToInt())
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

            val contrast = 1.08f
            val offset = (1f - contrast) * 128f + 4f
            val matrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            val canvas = Canvas(output)
            if (scale < 1f) canvas.scale(scale, scale)
            canvas.drawBitmap(source, 0f, 0f, paint)
            if (source !== original && !source.isRecycled) source.recycle()
            if (original !== output && !original.isRecycled) original.recycle()
            CapturedPage(output, page.sourceTimestampMs)
        } catch (t: Throwable) {
            page
        }
    }

    /**
     * Crops to the box that holds all 4 user corners (fractions of the frame).
     * This cleaner cannot straighten a slanted page, so it uses a plain box.
     * Returns the same bitmap when there are no corners or the box is the whole picture.
     */
    private fun cropToRegion(source: Bitmap, region: Corners?): Bitmap {
        if (region == null) return source
        val xs = listOf(region.topLeftX, region.topRightX, region.bottomRightX, region.bottomLeftX)
        val ys = listOf(region.topLeftY, region.topRightY, region.bottomRightY, region.bottomLeftY)
        val left = (xs.min().coerceIn(0f, 1f) * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (ys.min().coerceIn(0f, 1f) * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = (xs.max().coerceIn(0f, 1f) * source.width).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (ys.max().coerceIn(0f, 1f) * source.height).roundToInt().coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        if (left == 0 && top == 0 && width == source.width && height == source.height) return source
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private companion object {
        /** Long side of the output in pixels. Kept small so pages fit in memory. */
        const val MAX_OUTPUT = 2000
    }
}
