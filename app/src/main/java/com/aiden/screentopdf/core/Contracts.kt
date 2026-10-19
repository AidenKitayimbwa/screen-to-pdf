package com.aiden.screentopdf.core

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/**
 * One page of the final document, as an image.
 * [sourceTimestampMs] is where in the video the page came from.
 */
data class CapturedPage(
    val bitmap: Bitmap,
    val sourceTimestampMs: Long,
)

/** Progress callback: value from 0.0 to 1.0, plus a short status line for the UI. */
typealias ProgressListener = (fraction: Float, status: String) -> Unit

/**
 * Turns a video into candidate page images.
 * Owned by the processing package (Agent B).
 * Must: pull frames, keep only the ones where the screen was still (not mid-scroll),
 * and drop near-duplicates so each page of the document appears once.
 */
interface FrameSource {
    suspend fun extractPages(videoUri: Uri, onProgress: ProgressListener): List<CapturedPage>
}

/**
 * Cleans one page image so it looks like the original document.
 * Owned by the processing package (Agent B).
 * Must: find the monitor/document rectangle, fix perspective and rotation,
 * crop to it, remove screen glare/moire where possible, and whiten the background.
 */
interface PageCleaner {
    suspend fun clean(page: CapturedPage): CapturedPage
}

/**
 * Builds the PDF from cleaned pages.
 * Owned by the pdf package (Agent C).
 * Must: one page image per PDF page at A4 proportions, and when [withTextLayer] is true,
 * run on-device OCR and add invisible text so the PDF is searchable and selectable.
 */
interface PdfBuilder {
    suspend fun build(
        pages: List<CapturedPage>,
        outFile: File,
        withTextLayer: Boolean,
        onProgress: ProgressListener,
    ): File
}

/**
 * Single place where the UI gets its implementations.
 * Agents B and C each fill in their own factory function in their own package;
 * the UI (Agent A) calls these three functions and nothing else.
 */
object Pipeline {
    lateinit var frameSource: FrameSource
    lateinit var pageCleaner: PageCleaner
    lateinit var pdfBuilder: PdfBuilder
}
