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

/**
 * A basic page cleaner for phones where the OpenCV library does not load.
 *
 * It does not crop or straighten the page. It only makes the page a little
 * brighter and adds a little contrast, so text stands out from the background.
 * If anything goes wrong, it gives back the page unchanged.
 */
class SimplePageCleaner : PageCleaner {

    override suspend fun clean(page: CapturedPage): CapturedPage = withContext(Dispatchers.Default) {
        try {
            val source = page.bitmap
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val contrast = 1.25f
            val offset = (1f - contrast) * 128f + 12f
            val matrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            Canvas(output).drawBitmap(source, 0f, 0f, paint)
            if (source !== output && !source.isRecycled) source.recycle()
            CapturedPage(output, page.sourceTimestampMs)
        } catch (t: Throwable) {
            page
        }
    }
}
