package com.aiden.screentopdf.processing

import android.content.Context

/**
 * Keeps the application context so the processing code can open videos.
 * [ProcessingModule.install] sets it once at start-up.
 */
object ContextHolder {
    lateinit var appContext: Context
}
