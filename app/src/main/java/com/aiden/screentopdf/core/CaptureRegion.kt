package com.aiden.screentopdf.core

/**
 * The 4 corners of the document, as the user placed them on the record screen.
 *
 * Each corner is a fraction (0.0 to 1.0) of the video frame's width and height, in the frame's
 * upright orientation (the same orientation MediaMetadataRetriever returns frames in).
 * Order: top-left, top-right, bottom-right, bottom-left.
 *
 * The capture screen sets [corners] for the video it records. The cleaner uses them instead of
 * automatic detection. For a video picked from the gallery the UI sets [corners] to null,
 * and the cleaner falls back to automatic detection.
 */
data class Corners(
    val topLeftX: Float, val topLeftY: Float,
    val topRightX: Float, val topRightY: Float,
    val bottomRightX: Float, val bottomRightY: Float,
    val bottomLeftX: Float, val bottomLeftY: Float,
)

object CaptureRegion {
    @Volatile
    var corners: Corners? = null
}
