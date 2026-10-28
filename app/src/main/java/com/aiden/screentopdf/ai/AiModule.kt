package com.aiden.screentopdf.ai

import android.content.Context
import com.aiden.screentopdf.core.Pipeline

/**
 * Wires the ai package into the shared [Pipeline].
 * The app calls [install] once at start-up.
 */
object AiModule {
    fun install(context: Context) {
        ApiKeyStore.init(context)
        Pipeline.documentReader = ClaudeDocumentReader()
    }
}
