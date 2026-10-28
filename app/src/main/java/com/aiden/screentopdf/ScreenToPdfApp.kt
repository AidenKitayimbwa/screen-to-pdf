package com.aiden.screentopdf

import android.app.Application
import com.aiden.screentopdf.ai.AiModule
import com.aiden.screentopdf.pdf.PdfModule
import com.aiden.screentopdf.processing.ProcessingModule
import com.aiden.screentopdf.render.RenderModule

class ScreenToPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Each module sets its own fields on Pipeline (see core/Contracts.kt).
        ProcessingModule.install(this)
        PdfModule.install()
        AiModule.install(this)
        RenderModule.install()
    }
}
