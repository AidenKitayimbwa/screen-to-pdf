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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reads a recorded video and picks the frames a vision model needs to read the
 * whole document.
 *
 * It looks at a small picture every 250 milliseconds. It keeps:
 * 1. The sharpest frame of each "still" moment (the user paused on a page).
 * 2. Extra frames while the screen scrolls, so that every kept frame overlaps
 *    the one before it. This covers web pages that scroll without a pause.
 *
 * It then drops frames that look the same as the one before, caps the total at
 * [MAX_FRAMES], and fetches the kept frames at full size. No OpenCV needed.
 */
class VideoFrameSource : FrameSource {

    /** What we remember about one small sample frame. */
    private class Sample(
        val timeUs: Long,
        /** Grey thumbnail, [thumbW] wide, used to compare frames. */
        val grey: IntArray,
        /** Average grey of each thumbnail row, used to measure vertical scroll. */
        val rowProfile: DoubleArray,
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
                val thumbW = THUMB_WIDTH
                var thumbH = THUMB_WIDTH
                while (timeMs <= durationMs) {
                    ensureActive()
                    val frame = readSmallFrame(retriever, timeMs * 1000L)
                    if (frame != null) {
                        try {
                            if (samples.isEmpty()) {
                                // Fix the thumbnail shape from the first frame.
                                thumbH = (THUMB_WIDTH.toLong() * frame.height / max(1, frame.width))
                                    .toInt()
                                    .coerceIn(THUMB_MIN_HEIGHT, THUMB_MAX_HEIGHT)
                            }
                            val grey = greyThumbnail(frame, thumbW, thumbH)
                            samples.add(
                                Sample(
                                    timeUs = timeMs * 1000L,
                                    grey = grey,
                                    rowProfile = rowProfile(grey, thumbW, thumbH),
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

                // Step 2: the sharpest frame of each still moment.
                var stillFrames: List<Sample> = emptyList()
                for (threshold in STILL_THRESHOLDS) {
                    val segments = findStillSegments(samples, threshold)
                    if (segments.isNotEmpty()) {
                        stillFrames = segments.map { seg -> seg.maxBy { it.sharpness } }
                        break
                    }
                }
                val stillTimes = stillFrames.map { it.timeUs }.toHashSet()

                // Step 3: extra frames during scrolling so nothing is missed.
                val movingFrames = coverageFrames(samples, stillTimes)

                var chosen = removeDuplicates(
                    (stillFrames + movingFrames).distinctBy { it.timeUs }.sortedBy { it.timeUs }
                )
                if (chosen.isEmpty()) {
                    chosen = removeDuplicates(sharpestPerWindow(samples))
                }
                // Last resort: keep the single sharpest frame, even if it looks plain.
                if (chosen.isEmpty()) {
                    chosen = listOf(samples.maxBy { it.sharpness })
                }
                chosen = capFrames(chosen, stillTimes)

                // Step 4: fetch each chosen frame at full size.
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

    /**
     * Keeps frames while the screen scrolls, so that each kept frame overlaps
     * the one before it by about half a frame.
     *
     * It adds up the vertical scroll between samples. When the total since the
     * last kept frame passes [MAX_SCROLL] of the frame height, it keeps the
     * sharpest sample in that window, skipping samples that are much blurrier
     * than recent ones. Still frames count as kept frames and reset the total.
     */
    private fun coverageFrames(samples: List<Sample>, stillTimes: Set<Long>): List<Sample> {
        val kept = ArrayList<Sample>()
        if (samples.isEmpty()) return kept

        // Start with the sharpest of the first second.
        val startEnd = min(samples.size, START_WINDOW)
        var lastKept = 0
        for (i in 1 until startEnd) {
            if (samples[i].sharpness > samples[lastKept].sharpness) lastKept = i
        }
        if (samples[lastKept].timeUs !in stillTimes) kept.add(samples[lastKept])

        val shifts = DoubleArray(samples.size)
        var accumulated = 0.0
        for (i in lastKept + 1 until samples.size) {
            shifts[i] = scrollBetween(samples[i - 1], samples[i])
            accumulated += shifts[i]

            if (samples[i].timeUs in stillTimes) {
                lastKept = i
                accumulated = 0.0
                continue
            }

            while (accumulated > MAX_SCROLL && lastKept < i) {
                val pick = pickSharp(samples, lastKept + 1, i)
                if (samples[pick].timeUs !in stillTimes) kept.add(samples[pick])
                lastKept = pick
                accumulated = 0.0
                for (j in pick + 1..i) accumulated += shifts[j]
            }
        }

        // The tail of the video may hold content not yet kept.
        if (accumulated > TAIL_SCROLL && lastKept < samples.size - 1) {
            val pick = pickSharp(samples, lastKept + 1, samples.size - 1)
            if (samples[pick].timeUs !in stillTimes) kept.add(samples[pick])
        }
        return kept
    }

    /**
     * Picks the sharpest sample in [from]..[to], ignoring samples whose sharpness
     * is well below the median of the recent samples (motion blur).
     */
    private fun pickSharp(samples: List<Sample>, from: Int, to: Int): Int {
        val recentStart = max(0, to - RECENT_COUNT + 1)
        val recent = (recentStart..to).map { samples[it].sharpness }.sorted()
        val median = recent[recent.size / 2]
        val floor = median * BLUR_SHARE
        var best = -1
        var bestAny = from
        for (i in from..to) {
            val s = samples[i].sharpness
            if (s > samples[bestAny].sharpness) bestAny = i
            if (s >= floor && (best < 0 || s > samples[best].sharpness)) best = i
        }
        return if (best >= 0) best else bestAny
    }

    /**
     * Estimates how far the picture scrolled vertically between two samples,
     * as a fraction of the frame height. It slides the row profiles past each
     * other and takes the shift where they match best.
     * When no shift matches well, it treats a changed picture as a part scroll.
     */
    private fun scrollBetween(a: Sample, b: Sample): Double {
        val pa = a.rowProfile
        val pb = b.rowProfile
        val n = min(pa.size, pb.size)
        if (n < 8) return 0.0
        val maxShift = n / 2
        val minOverlap = max(4, n / 4)
        var bestCorr = -2.0
        var bestShift = 0
        for (s in -maxShift..maxShift) {
            // Row y of b lines up with row y + s of a.
            val start = max(0, -s)
            val end = min(n, n - s)
            val overlap = end - start
            if (overlap < minOverlap) continue
            var sumA = 0.0
            var sumB = 0.0
            for (y in start until end) {
                sumA += pa[y + s]
                sumB += pb[y]
            }
            val meanA = sumA / overlap
            val meanB = sumB / overlap
            var num = 0.0
            var varA = 0.0
            var varB = 0.0
            for (y in start until end) {
                val da = pa[y + s] - meanA
                val db = pb[y] - meanB
                num += da * db
                varA += da * da
                varB += db * db
            }
            val corr = if (varA < 1e-6 || varB < 1e-6) 0.0 else num / sqrt(varA * varB)
            val better = corr > bestCorr + 1e-9 ||
                (abs(corr - bestCorr) <= 1e-9 && abs(s) < abs(bestShift))
            if (better) {
                bestCorr = corr
                bestShift = s
            }
        }
        if (bestCorr >= MIN_CORRELATION) {
            return abs(bestShift).toDouble() / n
        }
        // No clear match. If the picture changed, assume part of a frame moved.
        return if (meanAbsDiff(a.grey, b.grey) >= STILL_THRESHOLDS[0]) UNKNOWN_SCROLL else 0.0
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

    /**
     * Limits the list to [MAX_FRAMES]. Still frames are kept first; the rest of
     * the budget goes to evenly spaced scrolling frames.
     */
    private fun capFrames(frames: List<Sample>, stillTimes: Set<Long>): List<Sample> {
        if (frames.size <= MAX_FRAMES) return frames
        val still = frames.filter { it.timeUs in stillTimes }
        val moving = frames.filter { it.timeUs !in stillTimes }
        if (still.size >= MAX_FRAMES) return evenlySpaced(still, MAX_FRAMES)
        return (still + evenlySpaced(moving, MAX_FRAMES - still.size)).sortedBy { it.timeUs }
    }

    private fun evenlySpaced(list: List<Sample>, count: Int): List<Sample> {
        if (count <= 0) return emptyList()
        if (list.size <= count) return list
        if (count == 1) return listOf(list[list.size / 2])
        val out = ArrayList<Sample>(count)
        for (i in 0 until count) {
            val index = (i.toLong() * (list.size - 1) / (count - 1)).toInt()
            val sample = list[index]
            if (out.lastOrNull() !== sample) out.add(sample)
        }
        return out
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

    /** Average grey of each row of a thumbnail. */
    private fun rowProfile(grey: IntArray, w: Int, h: Int): DoubleArray {
        val profile = DoubleArray(h)
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in 0 until w) sum += grey[row + x]
            profile[y] = sum.toDouble() / w
        }
        return profile
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
        val n = min(a.size, b.size)
        if (n == 0) return 0.0
        var sum = 0L
        for (i in 0 until n) sum += abs(a[i] - b[i])
        return sum.toDouble() / n
    }

    private fun standardDeviation(values: IntArray): Double {
        if (values.isEmpty()) return 0.0
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
        const val THUMB_WIDTH = 64
        const val THUMB_MIN_HEIGHT = 16
        const val THUMB_MAX_HEIGHT = 160
        const val SHARP_SIZE = 320
        const val MIN_STILL_RUN = 3
        const val DUPLICATE_THRESHOLD = 6.0
        const val MIN_STD_DEV = 8.0
        const val FALLBACK_WINDOW_MS = 1500L
        const val READ_SHARE = 0.85f
        const val MAX_FRAMES = 60

        /** Keep a scrolling frame once the screen has moved this share of its height. */
        const val MAX_SCROLL = 0.5
        /** Keep a last frame when the tail of the video moved at least this much. */
        const val TAIL_SCROLL = 0.2
        /** Scroll assumed per step when the picture changed but no shift matched. */
        const val UNKNOWN_SCROLL = 0.25
        /** Lowest correlation we trust as a real scroll match. */
        const val MIN_CORRELATION = 0.5
        /** Samples (1 second) searched for the first kept frame. */
        const val START_WINDOW = 4
        /** Samples (3 seconds) used for the recent sharpness median. */
        const val RECENT_COUNT = 12
        /** Samples sharper than this share of the median are not motion-blurred. */
        const val BLUR_SHARE = 0.6

        /** Try a strict "still" limit first, then a looser one for shaky hands. */
        val STILL_THRESHOLDS = doubleArrayOf(4.0, 7.0)
    }
}
