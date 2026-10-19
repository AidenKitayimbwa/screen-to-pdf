package com.aiden.screentopdf.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.PdfBuilder
import com.aiden.screentopdf.core.ProgressListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the PDF with Android's own [PdfDocument].
 *
 * Each cleaned page image becomes one A4 page. The image is fitted into the page,
 * centred, with its proportions kept. When asked for a text layer, the builder runs
 * ML Kit text recognition on each image and draws the recognised lines as near-invisible
 * text over the picture. PDF viewers treat that as real text, so the file is searchable
 * and the words can be selected and copied.
 */
class AndroidPdfBuilder : PdfBuilder {

    override suspend fun build(
        pages: List<CapturedPage>,
        outFile: File,
        withTextLayer: Boolean,
        onProgress: ProgressListener,
    ): File = withContext(Dispatchers.Default) {
        val document = PdfDocument()
        val recognizer: TextRecognizer? =
            if (withTextLayer) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
        val total = pages.size
        // Each page has two steps when OCR is on (draw, then read text), one step otherwise.
        val stepsPerPage = if (withTextLayer) 2f else 1f
        val totalSteps = max(1f, total * stepsPerPage)

        try {
            pages.forEachIndexed { index, page ->
                val number = index + 1
                onProgress((index * stepsPerPage) / totalSteps, "Building page $number of $total")

                val bitmap = limitSize(page.bitmap)
                val landscape = bitmap.width > bitmap.height
                val pageWidth = if (landscape) A4_LONG else A4_SHORT
                val pageHeight = if (landscape) A4_SHORT else A4_LONG

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, number).create()
                val pdfPage = document.startPage(pageInfo)
                val canvas = pdfPage.canvas
                val placement = fit(bitmap, pageWidth, pageHeight)

                canvas.drawBitmap(bitmap, null, placement.rect, bitmapPaint)

                if (recognizer != null) {
                    onProgress(
                        (index * stepsPerPage + 1f) / totalSteps,
                        "Reading text on page $number of $total",
                    )
                    val text = recogniseText(recognizer, bitmap, number)
                    if (text != null) drawTextLayer(canvas, text, placement)
                }

                document.finishPage(pdfPage)
                if (bitmap !== page.bitmap) bitmap.recycle()
            }

            onProgress(1f, "Saving PDF")
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { stream -> document.writeTo(stream) }
        } finally {
            document.close()
            recognizer?.close()
        }
        outFile
    }

    /** Where the image sits on the page, and the scale from image pixels to PDF points. */
    private class Placement(val rect: RectF, val scale: Float)

    private fun fit(bitmap: Bitmap, pageWidth: Int, pageHeight: Int): Placement {
        val scale = min(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val left = (pageWidth - drawnWidth) / 2f
        val top = (pageHeight - drawnHeight) / 2f
        return Placement(RectF(left, top, left + drawnWidth, top + drawnHeight), scale)
    }

    /**
     * Keeps the image sharp but the file size sane.
     * Never upscales. Only shrinks when the long side is above [MAX_LONG_SIDE_PX].
     */
    private fun limitSize(source: Bitmap): Bitmap {
        val longSide = max(source.width, source.height)
        if (longSide <= MAX_LONG_SIDE_PX) return source
        val ratio = MAX_LONG_SIDE_PX.toFloat() / longSide
        val width = max(1, (source.width * ratio).toInt())
        val height = max(1, (source.height * ratio).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private suspend fun recogniseText(recognizer: TextRecognizer, bitmap: Bitmap, pageNumber: Int): Text? {
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (e: Exception) {
            Log.w(TAG, "Text recognition failed on page $pageNumber; page kept without text layer", e)
            null
        }
    }

    /**
     * Draws every recognised line over its place in the image, using text that is
     * as good as invisible. PdfDocument embeds it as real text, so the PDF becomes
     * searchable and selectable.
     */
    private fun drawTextLayer(canvas: Canvas, text: Text, placement: Placement) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            // Alpha 0 would be skipped by the PDF drawing engine as "nothing to draw",
            // so use the lowest non-zero alpha. It is invisible to the eye.
            alpha = 1
        }
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val lineText = line.text
                if (lineText.isBlank() || box.width() <= 0 || box.height() <= 0) continue

                val left = placement.rect.left + box.left * placement.scale
                val top = placement.rect.top + box.top * placement.scale
                val boxWidth = box.width() * placement.scale
                val boxHeight = box.height() * placement.scale

                paint.textScaleX = 1f
                paint.textSize = max(1f, boxHeight * 0.8f)
                val measured = paint.measureText(lineText)
                if (measured > 0f) {
                    paint.textScaleX = (boxWidth / measured).coerceIn(0.25f, 4f)
                }
                // Baseline sits roughly 80% down the box for Latin text.
                val baseline = top + boxHeight * 0.8f
                canvas.drawText(lineText, left, baseline, paint)
            }
        }
    }

    private companion object {
        const val TAG = "AndroidPdfBuilder"

        /** A4 in PDF points (1 point = 1/72 inch). */
        const val A4_SHORT = 595
        const val A4_LONG = 842

        /** Long side above this is shrunk to keep the file size reasonable. */
        const val MAX_LONG_SIDE_PX = 2500

        val bitmapPaint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
    }
}
