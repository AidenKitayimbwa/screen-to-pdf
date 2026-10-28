package com.aiden.screentopdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.Pipeline
import com.aiden.screentopdf.core.RebuiltDocument
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
 *
 * Progress parts:
 *   find pages 0 to 0.3, clean frames 0.3 to 0.45,
 *   Claude reads 0.45 to 0.9, build PDF 0.9 to 1.0.
 *
 * With no API key, or if Claude cannot read the document, the app falls back to the
 * old image PDF and tells the user why on the result screen.
 */
class ProcessingViewModel : ViewModel() {

    /** The big steps shown as a title on the processing screen. */
    enum class Stage(val label: String) {
        FINDING("Finding pages"),
        CLEANING("Cleaning frames"),
        READING("Claude is reading the document"),
        BUILDING("Building the PDF"),
    }

    data class UiState(
        val progress: Float = 0f,
        val stage: Stage = Stage.FINDING,
        val status: String = "Starting",
        val thumbnails: List<Bitmap> = emptyList(),
        val error: String? = null,
        /** Why the result is a photo copy rather than a rebuilt document, or null. */
        val notice: String? = null,
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
            setProgress(0f, Stage.FINDING, "Finding pages in the video")
            val pages = Pipeline.frameSource.extractPages(uri) { fraction, status ->
                setProgress(FIND_END * fraction.coerceIn(0f, 1f), Stage.FINDING, status)
            }
            coroutineContext.ensureActive()
            if (pages.isEmpty()) {
                _state.update {
                    it.copy(error = "No still pages found. Scroll more slowly and pause on each page.")
                }
                return
            }

            // Part 2: clean each frame. The cleaner may recycle the input bitmap,
            // so from here on only the cleaned list is used.
            val total = pages.size
            val cleaned = ArrayList<CapturedPage>(total)
            val cleanSpan = CLEAN_END - FIND_END
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                setProgress(
                    FIND_END + cleanSpan * index / total,
                    Stage.CLEANING,
                    "Cleaning frame ${index + 1} of $total",
                )
                val clean = Pipeline.pageCleaner.clean(page)
                cleaned += clean
                val thumbnail = makeThumbnail(clean.bitmap)
                _state.update {
                    it.copy(
                        progress = FIND_END + cleanSpan * (index + 1) / total,
                        thumbnails = if (thumbnail != null) it.thumbnails + thumbnail else it.thumbnails,
                    )
                }
            }
            coroutineContext.ensureActive()

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
            val outFile = File(context.cacheDir, "ScreenToPdf-$stamp.pdf")

            // Part 3: ask Claude to rebuild the document, or fall back to a photo copy.
            val notes = ArrayList<String>()
            val rebuilt: File? = if (Pipeline.documentReader.isReady()) {
                rebuildDocument(cleaned, outFile, notes)
            } else {
                notes += "No API key saved, so this is a photo copy. " +
                    "Add a key in Settings for a rebuilt document."
                null
            }
            val pdf: File = rebuilt ?: buildPhotoCopy(cleaned, outFile)
            coroutineContext.ensureActive()

            ResultNotes.lines = notes
            _state.update {
                it.copy(
                    progress = 1f,
                    stage = Stage.BUILDING,
                    status = "Done",
                    notice = notes.firstOrNull(),
                    pdfPath = pdf.absolutePath,
                )
            }
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

    /**
     * Reads the frames with Claude and typesets the result.
     * Returns null, and adds a note, when the rebuilt route fails for a reason other than cancel.
     */
    private suspend fun rebuildDocument(
        cleaned: List<CapturedPage>,
        outFile: File,
        notes: MutableList<String>,
    ): File? {
        val doc: RebuiltDocument = try {
            setProgress(CLEAN_END, Stage.READING, "Sending the pages to Claude")
            Pipeline.documentReader.read(cleaned) { fraction, status ->
                setProgress(
                    CLEAN_END + (READ_END - CLEAN_END) * fraction.coerceIn(0f, 1f),
                    Stage.READING,
                    status,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Claude could not read the document", e)
            notes += "Claude could not read the document: ${shortMessage(e)}. " +
                "This is a photo copy instead."
            return null
        }
        coroutineContext.ensureActive()
        notes += doc.warnings

        return try {
            setProgress(READ_END, Stage.BUILDING, "Typesetting the document")
            val pdf = Pipeline.documentRenderer.render(doc, outFile) { fraction, status ->
                setProgress(READ_END + (1f - READ_END) * fraction.coerceIn(0f, 1f), Stage.BUILDING, status)
            }
            pdf
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Could not typeset the document", e)
            notes += "Could not build the rebuilt document: ${shortMessage(e)}. " +
                "This is a photo copy instead."
            null
        }
    }

    /** The version 1 route: one image per page, with an invisible text layer. */
    private suspend fun buildPhotoCopy(cleaned: List<CapturedPage>, outFile: File): File {
        setProgress(READ_END, Stage.BUILDING, "Building the PDF")
        if (outFile.exists()) outFile.delete()
        return Pipeline.pdfBuilder.build(cleaned, outFile, true) { fraction, status ->
            setProgress(READ_END + (1f - READ_END) * fraction.coerceIn(0f, 1f), Stage.BUILDING, status)
        }
    }

    private fun setProgress(progress: Float, stage: Stage, status: String) {
        _state.update { it.copy(progress = progress, stage = stage, status = status) }
    }

    private fun shortMessage(e: Throwable): String {
        val text = e.message?.trim()?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName
        return if (text.length > 160) text.take(157) + "..." else text
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
        const val FIND_END = 0.3f
        const val CLEAN_END = 0.45f
        const val READ_END = 0.9f
    }
}
