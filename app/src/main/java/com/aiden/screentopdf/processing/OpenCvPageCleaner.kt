package com.aiden.screentopdf.processing

import android.graphics.Bitmap
import com.aiden.screentopdf.core.CaptureRegion
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.Corners
import com.aiden.screentopdf.core.PageCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Makes a photo of a screen easy for a vision model to read, using OpenCV.
 *
 * It keeps the picture faithful: colours, photos and grey text stay as they are.
 * Steps:
 * 1. Find the screen or document and straighten it, as if filmed from straight in front.
 *    If the user placed the frame on the record screen ([CaptureRegion.corners]), those
 *    4 corners are used. Otherwise it looks for the biggest bright 4-sided shape.
 * 2. Soften the fine wavy pattern you get when filming a screen (moiré).
 * 3. Gently balance the colours so bright areas are neutral white.
 * 4. Lift local contrast a little and sharpen text slightly.
 *
 * Output is at most [MAX_OUTPUT] pixels on the long side.
 * If any step fails, it gives back the page unchanged.
 */
class OpenCvPageCleaner : PageCleaner {

    override suspend fun clean(page: CapturedPage): CapturedPage = withContext(Dispatchers.Default) {
        val mats = ArrayList<Mat>()
        fun <T : Mat> track(mat: T): T {
            mats.add(mat)
            return mat
        }
        try {
            val source = page.bitmap
            val input = if (source.config == Bitmap.Config.ARGB_8888) {
                source
            } else {
                source.copy(Bitmap.Config.ARGB_8888, false)
            }
            val rgba = track(Mat())
            Utils.bitmapToMat(input, rgba)
            if (input !== source) input.recycle()

            // Step 1: find the screen and fix the perspective.
            // If the user placed the frame on the record screen, use their corners.
            // Otherwise look for the screen automatically.
            val placed = CaptureRegion.corners
            val ordered = if (placed != null) {
                cornersFromRegion(placed, rgba)
            } else {
                findPageCorners(rgba) ?: wholeFrame(rgba)
            }
            val (outW, outH) = outputSize(ordered)
            val srcQuad = track(MatOfPoint2f(*ordered))
            val dstQuad = track(
                MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(outW - 1.0, 0.0),
                    Point(outW - 1.0, outH - 1.0),
                    Point(0.0, outH - 1.0),
                )
            )
            val transform = track(Imgproc.getPerspectiveTransform(srcQuad, dstQuad))
            val warped = track(Mat())
            Imgproc.warpPerspective(
                rgba, warped, transform,
                Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_CUBIC,
            )

            val rgb = track(Mat())
            Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)

            // Step 2: mild moiré reduction.
            val smooth = track(Mat())
            Imgproc.bilateralFilter(rgb, smooth, 5, 30.0, 30.0)

            // Step 3: gentle white balance.
            val balanced = whiteBalance(smooth) { track(it) }

            // Step 4: mild local contrast, then a light unsharp mask.
            val contrasted = localContrast(balanced) { track(it) }
            val blurred = track(Mat())
            Imgproc.GaussianBlur(contrasted, blurred, Size(0.0, 0.0), 1.5)
            val sharp = track(Mat())
            Core.addWeighted(contrasted, 1.3, blurred, -0.3, 0.0, sharp)

            val outRgba = track(Mat())
            Imgproc.cvtColor(sharp, outRgba, Imgproc.COLOR_RGB2RGBA)
            val output = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, output)

            if (!source.isRecycled) source.recycle()
            CapturedPage(output, page.sourceTimestampMs)
        } catch (t: Throwable) {
            page
        } finally {
            for (mat in mats) {
                try {
                    mat.release()
                } catch (t: Throwable) {
                    // Ignore: the memory is freed later anyway.
                }
            }
        }
    }

    /**
     * Looks for the best 4-sided shape: convex, covering more than 15% of the
     * picture, with a plausible width-to-height ratio. It tries edge detection,
     * a global threshold and an adaptive threshold, and takes the biggest quad.
     * Works on a smaller copy for speed, then scales the corners back up.
     * Returns ordered corners, or null when no good shape is found.
     */
    private fun findPageCorners(rgba: Mat): Array<Point>? {
        val temp = ArrayList<Mat>()
        try {
            val longSide = max(rgba.cols(), rgba.rows()).toDouble()
            val scale = if (longSide > DETECT_SIZE) DETECT_SIZE / longSide else 1.0
            val small = Mat().also { temp.add(it) }
            if (scale < 1.0) {
                Imgproc.resize(rgba, small, Size(rgba.cols() * scale, rgba.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                rgba.copyTo(small)
            }
            val grey = Mat().also { temp.add(it) }
            Imgproc.cvtColor(small, grey, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(grey, grey, Size(5.0, 5.0), 0.0)

            val minArea = small.cols().toDouble() * small.rows() * MIN_AREA_SHARE
            val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also { temp.add(it) }
            val kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).also { temp.add(it) }

            // Pass A: edges.
            val edges = Mat().also { temp.add(it) }
            Imgproc.Canny(grey, edges, 50.0, 150.0)
            Imgproc.dilate(edges, edges, kernel3)

            // Pass B: bright screen against a darker room (global threshold).
            val otsu = Mat().also { temp.add(it) }
            Imgproc.threshold(grey, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_CLOSE, kernel5)

            // Pass C: adaptive threshold copes with uneven light.
            val adaptive = Mat().also { temp.add(it) }
            Imgproc.adaptiveThreshold(
                grey, adaptive, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                ADAPTIVE_BLOCK, ADAPTIVE_C,
            )
            Imgproc.morphologyEx(adaptive, adaptive, Imgproc.MORPH_CLOSE, kernel5)

            var bestArea = 0.0
            var best: Array<Point>? = null
            for (binary in listOf(edges, otsu, adaptive)) {
                val quad = bestQuad(binary, minArea, temp) ?: continue
                val area = quadArea(quad)
                if (area > bestArea) {
                    bestArea = area
                    best = quad
                }
            }
            return best?.map { Point(it.x / scale, it.y / scale) }?.toTypedArray()
        } catch (t: Throwable) {
            return null
        } finally {
            for (mat in temp) mat.release()
        }
    }

    /** Finds the biggest plausible quad in a black-and-white picture, with corners ordered. */
    private fun bestQuad(binary: Mat, minArea: Double, temp: MutableList<Mat>): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().also { temp.add(it) }
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        temp.addAll(contours)

        var bestArea = 0.0
        var best: Array<Point>? = null
        for (contour in contours) {
            if (Imgproc.contourArea(contour) < minArea) continue
            val curve = MatOfPoint2f(*contour.toArray()).also { temp.add(it) }
            val perimeter = Imgproc.arcLength(curve, true)
            for (epsilon in APPROX_EPSILONS) {
                val approx = MatOfPoint2f().also { temp.add(it) }
                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                val points = approx.toArray()
                if (points.size != 4) continue
                val polygon = MatOfPoint(*points).also { temp.add(it) }
                if (!Imgproc.isContourConvex(polygon)) break
                val area = Imgproc.contourArea(approx)
                if (area <= minArea || area <= bestArea) break
                val ordered = orderCorners(points)
                if (ordered.distinct().size != 4) break
                val (w, h) = outputSize(ordered)
                val ratio = w.toDouble() / h
                if (ratio < MIN_ASPECT || ratio > MAX_ASPECT) break
                bestArea = area
                best = ordered
                break
            }
        }
        return best
    }

    /** Area of a 4-corner shape (shoelace formula). */
    private fun quadArea(c: Array<Point>): Double {
        var sum = 0.0
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    /**
     * Turns the user's corners (fractions of the frame, order top-left, top-right,
     * bottom-right, bottom-left) into pixel points on this picture.
     */
    private fun cornersFromRegion(region: Corners, mat: Mat): Array<Point> {
        val w = mat.cols() - 1.0
        val h = mat.rows() - 1.0
        fun at(x: Float, y: Float) = Point(
            (x.toDouble() * w).coerceIn(0.0, w),
            (y.toDouble() * h).coerceIn(0.0, h),
        )
        return arrayOf(
            at(region.topLeftX, region.topLeftY),
            at(region.topRightX, region.topRightY),
            at(region.bottomRightX, region.bottomRightY),
            at(region.bottomLeftX, region.bottomLeftY),
        )
    }

    private fun wholeFrame(mat: Mat): Array<Point> {
        val w = mat.cols() - 1.0
        val h = mat.rows() - 1.0
        return arrayOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
    }

    /** Puts corners in this order: top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * Output width and height come from the longer of each pair of opposite edges,
     * scaled down so the long side is at most [MAX_OUTPUT].
     */
    private fun outputSize(c: Array<Point>): Pair<Int, Int> {
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        var width = max(dist(c[0], c[1]), dist(c[3], c[2]))
        var height = max(dist(c[0], c[3]), dist(c[1], c[2]))
        val longSide = max(width, height)
        if (longSide > MAX_OUTPUT) {
            val scale = MAX_OUTPUT / longSide
            width *= scale
            height *= scale
        }
        return Pair(max(width.roundToInt(), 16), max(height.roundToInt(), 16))
    }

    /**
     * Scales each colour channel so the brightest 2% of the picture becomes a
     * neutral white. Gains are kept small so colours are not distorted.
     */
    private fun whiteBalance(rgb: Mat, track: (Mat) -> Mat): Mat {
        val grey = track(Mat())
        Imgproc.cvtColor(rgb, grey, Imgproc.COLOR_RGB2GRAY)
        val bright = percentile(grey, 0.98)
        if (bright < MIN_BRIGHT) return rgb
        val mask = track(Mat())
        Imgproc.threshold(grey, mask, bright - 1.0, 255.0, Imgproc.THRESH_BINARY)
        if (Core.countNonZero(mask) < 16) return rgb

        val mean = Core.mean(rgb, mask)
        val target = max(mean.`val`[0], max(mean.`val`[1], mean.`val`[2]))
        if (target < 1.0) return rgb

        val channels = ArrayList<Mat>()
        Core.split(rgb, channels)
        channels.forEach { track(it) }
        for (i in 0 until 3) {
            val value = mean.`val`[i]
            if (value < 1.0) continue
            val gain = (target / value).coerceIn(MIN_GAIN, MAX_GAIN)
            channels[i].convertTo(channels[i], CvType.CV_8U, gain, 0.0)
        }
        val out = track(Mat())
        Core.merge(channels, out)
        return out
    }

    /** Mild CLAHE on the lightness channel only, so colours stay the same. */
    private fun localContrast(rgb: Mat, track: (Mat) -> Mat): Mat {
        val lab = track(Mat())
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        channels.forEach { track(it) }
        val clahe = Imgproc.createCLAHE(CLAHE_CLIP, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        val merged = track(Mat())
        Core.merge(channels, merged)
        val out = track(Mat())
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2RGB)
        return out
    }

    /** Returns the grey value below which the given share of pixels fall (8-bit, one channel). */
    private fun percentile(mat: Mat, share: Double): Int {
        val total = mat.rows() * mat.cols()
        if (total == 0) return 0
        val data = ByteArray(total)
        val continuous = if (mat.isContinuous) mat else mat.clone()
        continuous.get(0, 0, data)
        if (continuous !== mat) continuous.release()
        val histogram = IntArray(256)
        for (b in data) histogram[b.toInt() and 0xFF]++
        val target = (total * share).toLong()
        var count = 0L
        for (value in 0 until 256) {
            count += histogram[value]
            if (count > target) return value
        }
        return 255
    }

    private companion object {
        const val DETECT_SIZE = 800.0
        /** Long side of the output in pixels. Kept small so pages fit in memory. */
        const val MAX_OUTPUT = 2000.0
        const val MIN_AREA_SHARE = 0.15
        const val MIN_ASPECT = 0.4
        const val MAX_ASPECT = 2.6
        const val ADAPTIVE_BLOCK = 61
        const val ADAPTIVE_C = 10.0
        const val CLAHE_CLIP = 1.5
        /** Skip white balance on dark pictures with no white area. */
        const val MIN_BRIGHT = 120
        const val MIN_GAIN = 0.85
        const val MAX_GAIN = 1.25
        val APPROX_EPSILONS = doubleArrayOf(0.02, 0.04)
    }
}
