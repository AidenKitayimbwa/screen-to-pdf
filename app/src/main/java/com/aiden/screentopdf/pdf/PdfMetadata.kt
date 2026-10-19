package com.aiden.screentopdf.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Small helper for the result screen: how many pages, how big the file is.
 */
object PdfMetadata {

    private const val TAG = "PdfMetadata"

    /** Returns a short line such as "7 pages, 1.2 MB". */
    fun describe(file: File): String {
        val pages = pageCount(file)
        val pageText = when (pages) {
            null -> "unknown page count"
            1 -> "1 page"
            else -> "$pages pages"
        }
        return "$pageText, ${sizeText(file.length())}"
    }

    /** Counts the pages with Android's own PDF reader, or returns null if the file cannot be read. */
    fun pageCount(file: File): Int? {
        if (!file.exists()) return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not count pages in ${file.name}", e)
            null
        }
    }

    fun sizeText(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        return when {
            bytes >= mb -> String.format(Locale.UK, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.UK, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
