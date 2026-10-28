package com.aiden.screentopdf.render

import com.aiden.screentopdf.core.Pipeline

/**
 * Wires the render package into the shared [Pipeline].
 * The UI calls [install] once at start-up.
 */
object RenderModule {
    fun install() {
        Pipeline.documentRenderer = PdfTypesetter()
    }
}
