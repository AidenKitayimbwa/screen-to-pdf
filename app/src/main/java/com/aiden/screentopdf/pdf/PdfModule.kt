package com.aiden.screentopdf.pdf

import com.aiden.screentopdf.core.Pipeline

/**
 * Wires the pdf package into the shared [Pipeline].
 * The UI calls [install] once at start-up.
 */
object PdfModule {
    fun install() {
        Pipeline.pdfBuilder = AndroidPdfBuilder()
    }
}
