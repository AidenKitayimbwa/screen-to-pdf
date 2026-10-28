package com.aiden.screentopdf.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.PageCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A basic page cleaner for phones where the OpenCV library does not load.
 *
 * It does not crop or straighten the page. It adds a touch of contrast so text
 * stands out, keeps the colours, and limits the size to [MAX_OUTPUT] pixels on
 * the long side. If anything goes wrong, it gives back the page unchanged.
 */
class SimplePageCleaner : PageCleaner {

    override suspend fun clean(page: CapturedPage): CapturedPage = withContext(Dispatchers.Default) {
        try {
            val source = page.bitmap
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
            if (source !== output && !source.isRecycled) source.recycle()
            CapturedPage(output, page.sourceTimestampMs)
        } catch (t: Throwable) {
            page
        }
    }

    private companion object {
        const val MAX_OUTPUT = 2400
    }
}
