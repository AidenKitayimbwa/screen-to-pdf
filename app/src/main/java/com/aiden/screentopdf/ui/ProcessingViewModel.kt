package com.aiden.screentopdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.Pipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Runs the whole video-to-PDF pipeline and reports progress to [ProcessingScreen].
 * Progress is split into 3 parts: find pages 0 to 0.4, clean pages 0.4 to 0.7, build PDF 0.7 to 1.0.
 */
class ProcessingViewModel : ViewModel() {

    data class UiState(
        val progress: Float = 0f,
        val status: String = "Starting",
        val thumbnails: List<Bitmap> = emptyList(),
        val error: String? = null,
        val pdfPath: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var started = false
    private var delivered = false

    /** Starts the work. Calling it again (for example after a screen rotation) does nothing. */
    fun start(context: Context, uri: Uri) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        job = viewModelScope.launch(Dispatchers.Default) {
            runPipeline(appContext, uri)
        }
    }

    /** Clears the old result and runs the work again. */
    fun retry(context: Context, uri: Uri) {
        job?.cancel()
        job = null
        started = false
        delivered = false
        _state.value = UiState()
        start(context, uri)
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Returns true only the first time it is called after a success, so the screen moves on once. */
    fun markDelivered(): Boolean {
        if (delivered) return false
        delivered = true
        return true
    }

    private suspend fun runPipeline(context: Context, uri: Uri) {
        try {
            // Part 1: find the still pages in the video.
            setProgress(0f, "Finding pages in the video")
            val pages = Pipeline.frameSource.extractPages(uri) { fraction, status ->
                setProgress(0.4f * fraction.coerceIn(0f, 1f), status)
            }
            coroutineContext.ensureActive()
            if (pages.isEmpty()) {
                _state.update {
                    it.copy(error = "No still pages found. Scroll more slowly and pause on each page.")
                }
                return
            }

            // Part 2: clean each page.
            val total = pages.size
            val cleaned = ArrayList<CapturedPage>(total)
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                setProgress(0.4f + 0.3f * index / total, "Cleaning page ${index + 1} of $total")
                val clean = Pipeline.pageCleaner.clean(page)
                cleaned += clean
                val thumbnail = makeThumbnail(clean.bitmap)
                _state.update {
                    it.copy(
                        progress = 0.4f + 0.3f * (index + 1) / total,
                        thumbnails = if (thumbnail != null) it.thumbnails + thumbnail else it.thumbnails,
                    )
                }
            }

            // Part 3: build the PDF.
            setProgress(0.7f, "Building the PDF")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
            val outFile = File(context.cacheDir, "ScreenToPdf-$stamp.pdf")
            val pdf = Pipeline.pdfBuilder.build(cleaned, outFile, true) { fraction, status ->
                setProgress(0.7f + 0.3f * fraction.coerceIn(0f, 1f), status)
            }
            coroutineContext.ensureActive()
            _state.update { it.copy(progress = 1f, status = "Done", pdfPath = pdf.absolutePath) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Processing failed", e)
            val detail = e.message?.takeIf { it.isNotBlank() }
            _state.update {
                it.copy(
                    error = if (detail != null) {
                        "Could not make the PDF. $detail"
                    } else {
                        "Could not make the PDF. Please try again."
                    },
                )
            }
        }
    }

    private fun setProgress(progress: Float, status: String) {
        _state.update { it.copy(progress = progress, status = status) }
    }

    private fun makeThumbnail(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val width = THUMB_WIDTH
        val height = (source.height.toFloat() / source.width * width).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(source, width, height, true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not make a thumbnail", e)
            null
        }
    }

    private companion object {
        const val TAG = "ProcessingViewModel"
        const val THUMB_WIDTH = 160
    }
}
