package com.aiden.screentopdf.processing

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.FrameSource
import com.aiden.screentopdf.core.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads a recorded video and picks out one picture for each document page.
 *
 * The user pauses for about 1 second on each page. This class looks at a small
 * picture every 250 milliseconds and finds the moments when the picture stays
 * still. For each still moment it takes the sharpest frame at full size.
 * It then removes pages that look the same as the page before, and frames that
 * are almost one flat colour. All of this works without OpenCV.
 */
class VideoFrameSource : FrameSource {

    /** What we remember about one small sample frame. */
    private class Sample(
        val timeUs: Long,
        val grey: IntArray,
        val sharpness: Double,
    )

    override suspend fun extractPages(videoUri: Uri, onProgress: ProgressListener): List<CapturedPage> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                if (videoUri.scheme == "file") {
                    retriever.setDataSource(videoUri.path)
                } else {
                    retriever.setDataSource(ContextHolder.appContext, videoUri)
                }
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durationMs <= 0L) {
                    onProgress(1f, "Found 0 pages")
                    return@withContext emptyList()
                }

                // Step 1: read small samples every 250 ms.
                val samples = ArrayList<Sample>()
                val totalSteps = (durationMs / SAMPLE_STEP_MS + 1).toInt()
                var lastPercent = -1
                var step = 0
                var timeMs = 0L
                while (timeMs <= durationMs) {
                    ensureActive()
                    val frame = readSmallFrame(retriever, timeMs * 1000L)
                    if (frame != null) {
                        try {
                            samples.add(
                                Sample(
                                    timeUs = timeMs * 1000L,
                                    grey = greyThumbnail(frame, DIFF_SIZE, DIFF_SIZE),
                                    sharpness = sharpness(frame),
                                )
                            )
                        } finally {
                            frame.recycle()
                        }
                    }
                    step++
                    val percent = (step * 100 / max(totalSteps, 1)).coerceAtMost(100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent / 100f * READ_SHARE, "Reading video: $percent%")
                    }
                    timeMs += SAMPLE_STEP_MS
                }

                if (samples.isEmpty()) {
                    onProgress(1f, "Found 0 pages")
                    return@withContext emptyList()
                }

                // Step 2: choose one sample for each page.
                var chosen: List<Sample> = emptyList()
                for (threshold in STILL_THRESHOLDS) {
                    val segments = findStillSegments(samples, threshold)
                    if (segments.isNotEmpty()) {
                        chosen = removeDuplicates(segments.map { seg -> seg.maxBy { it.sharpness } })
                        if (chosen.isNotEmpty()) break
                    }
                }
                if (chosen.isEmpty()) {
                    chosen = removeDuplicates(sharpestPerWindow(samples))
                }
                // Last resort: keep the single sharpest frame, even if it looks plain.
                if (chosen.isEmpty()) {
                    chosen = listOf(samples.maxBy { it.sharpness })
                }

                // Step 3: fetch each chosen frame at full size.
                val pages = ArrayList<CapturedPage>()
                chosen.forEachIndexed { index, sample ->
                    ensureActive()
                    val full = readFullFrame(retriever, sample.timeUs)
                    if (full != null) {
                        pages.add(CapturedPage(full, sample.timeUs / 1000L))
                    }
                    val fraction = READ_SHARE + (1f - READ_SHARE) * (index + 1) / chosen.size
                    onProgress(fraction.coerceAtMost(1f), "Reading video: 100%")
                }
                onProgress(1f, "Found ${pages.size} pages")
                pages
            } finally {
                try {
                    retriever.release()
                } catch (t: Throwable) {
                    // Nothing more we can do if release fails.
                }
            }
        }

    /** Gets a small frame (long side about 640 pixels) at the given time. */
    private fun readSmallFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                SMALL_SIZE,
                SMALL_SIZE,
            )
        } else {
            val big = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (big == null) {
                null
            } else {
                val scale = SMALL_SIZE.toFloat() / max(big.width, big.height)
                if (scale >= 1f) {
                    big
                } else {
                    val w = max(1, (big.width * scale).roundToInt())
                    val h = max(1, (big.height * scale).roundToInt())
                    val small = Bitmap.createScaledBitmap(big, w, h, true)
                    if (small !== big) big.recycle()
                    small
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** Gets a full-size ARGB_8888 frame at the given time. */
    private fun readFullFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? = try {
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        if (frame == null || frame.config == Bitmap.Config.ARGB_8888) {
            frame
        } else {
            val copy = frame.copy(Bitmap.Config.ARGB_8888, false)
            frame.recycle()
            copy
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Finds runs of samples where the picture hardly changes.
     * A sample is "still" when it differs little from the sample before it.
     * A run needs at least [MIN_STILL_RUN] still samples. The run also includes
     * the sample just before the first still one, as that one matches too.
     */
    private fun findStillSegments(samples: List<Sample>, threshold: Double): List<List<Sample>> {
        val segments = ArrayList<List<Sample>>()
        var runStart = -1
        var runLength = 0
        for (i in 1 until samples.size) {
            val still = meanAbsDiff(samples[i - 1].grey, samples[i].grey) < threshold
            if (still) {
                if (runLength == 0) runStart = i - 1
                runLength++
            }
            val runEnds = !still || i == samples.size - 1
            if (runEnds && runLength > 0) {
                if (runLength >= MIN_STILL_RUN) {
                    val endExclusive = if (still) i + 1 else i
                    segments.add(samples.subList(runStart, endExclusive))
                }
                runLength = 0
                runStart = -1
            }
        }
        return segments
    }

    /** Fallback when nothing is still: the sharpest sample in each 1.5-second window. */
    private fun sharpestPerWindow(samples: List<Sample>): List<Sample> {
        val windowUs = FALLBACK_WINDOW_MS * 1000L
        return samples
            .groupBy { it.timeUs / windowUs }
            .toSortedMap()
            .values
            .map { group -> group.maxBy { it.sharpness } }
    }

    /**
     * Drops plain frames and pages that look like the page kept just before.
     * When two in a row match, it keeps the sharper one.
     */
    private fun removeDuplicates(candidates: List<Sample>): List<Sample> {
        val kept = ArrayList<Sample>()
        for (candidate in candidates) {
            if (standardDeviation(candidate.grey) < MIN_STD_DEV) continue
            val last = kept.lastOrNull()
            if (last != null && meanAbsDiff(last.grey, candidate.grey) < DUPLICATE_THRESHOLD) {
                if (candidate.sharpness > last.sharpness) kept[kept.size - 1] = candidate
                continue
            }
            kept.add(candidate)
        }
        return kept
    }

    /** Shrinks the bitmap to [w] x [h] and returns grey values from 0 to 255. */
    private fun greyThumbnail(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            pixels[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return pixels
    }

    /** Average squared brightness change between neighbouring pixels. Higher means sharper. */
    private fun sharpness(bitmap: Bitmap): Double {
        val longSide = max(bitmap.width, bitmap.height)
        val scale = SHARP_SIZE.toFloat() / longSide
        val w = max(2, (bitmap.width * scale).roundToInt())
        val h = max(2, (bitmap.height * scale).roundToInt())
        val grey = greyThumbnail(bitmap, w, h)
        var sum = 0.0
        for (y in 0 until h - 1) {
            val row = y * w
            for (x in 0 until w - 1) {
                val p = grey[row + x]
                val dx = grey[row + x + 1] - p
                val dy = grey[row + w + x] - p
                sum += (dx * dx + dy * dy).toDouble()
            }
        }
        return sum / ((w - 1) * (h - 1))
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    private fun standardDeviation(values: IntArray): Double {
        var sum = 0.0
        var sumSq = 0.0
        for (v in values) {
            sum += v
            sumSq += v.toDouble() * v
        }
        val mean = sum / values.size
        return sqrt(max(0.0, sumSq / values.size - mean * mean))
    }

    private companion object {
        const val SAMPLE_STEP_MS = 250L
        const val SMALL_SIZE = 640
        const val DIFF_SIZE = 64
        const val SHARP_SIZE = 320
        const val MIN_STILL_RUN = 3
        const val DUPLICATE_THRESHOLD = 6.0
        const val MIN_STD_DEV = 8.0
        const val FALLBACK_WINDOW_MS = 1500L
        const val READ_SHARE = 0.85f

        /** Try a strict "still" limit first, then a looser one for shaky hands. */
        val STILL_THRESHOLDS = doubleArrayOf(4.0, 7.0)
    }
}
