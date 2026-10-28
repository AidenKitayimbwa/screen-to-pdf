package com.aiden.screentopdf.ui

/**
 * Short notes for the result screen, such as "this is a photo copy" or reader warnings.
 * ProcessingViewModel fills it just before it navigates; ResultScreen reads it.
 * Kept in memory only, so it is empty again after the app restarts.
 */
object ResultNotes {
    @Volatile
    var lines: List<String> = emptyList()
}
