package com.aiden.screentopdf

import android.app.Application
import com.aiden.screentopdf.pdf.PdfModule
import com.aiden.screentopdf.processing.ProcessingModule

class ScreenToPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Each module sets its own fields on Pipeline (see core/Contracts.kt).
        ProcessingModule.install(this)
        PdfModule.install()
    }
}
