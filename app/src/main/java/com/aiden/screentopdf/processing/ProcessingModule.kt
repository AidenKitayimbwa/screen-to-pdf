package com.aiden.screentopdf.processing

import android.content.Context
import com.aiden.screentopdf.core.Pipeline
import org.opencv.android.OpenCVLoader

/**
 * Sets up the processing part of the app when the app starts.
 *
 * It saves the application context, loads the OpenCV image library and
 * puts the frame reader and the page cleaner into [Pipeline].
 * If OpenCV does not load on a phone, the app still works with a simpler cleaner.
 */
object ProcessingModule {

    /** True when the OpenCV library loaded correctly on this phone. */
    var openCvReady = false

    /** Call once from the Application class with the application context. */
    fun install(context: Context) {
        ContextHolder.appContext = context.applicationContext
        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            false
        }
        Pipeline.frameSource = VideoFrameSource()
        Pipeline.pageCleaner = if (openCvReady) OpenCvPageCleaner() else SimplePageCleaner()
    }
}
