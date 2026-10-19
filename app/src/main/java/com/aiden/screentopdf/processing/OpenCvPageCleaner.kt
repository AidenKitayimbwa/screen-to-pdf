package com.aiden.screentopdf.processing

import android.graphics.Bitmap
import com.aiden.screentopdf.core.CapturedPage
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
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a photo of a screen into a flat, clean page, using the OpenCV library.
 *
 * It works in 3 steps:
 * 1. It finds the edges of the document or screen and straightens it, so the
 *    page looks as if you took the picture from straight in front.
 * 2. It removes uneven light and glare, and makes the background close to white.
 *    It keeps the colours of the page.
 * 3. It softens the fine wavy pattern you get when you film a screen (moiré),
 *    then sharpens the text a little.
 *
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

            // Step 1: find the page and fix the perspective.
            val found = findPageCorners(rgba)?.let { orderCorners(it) }
            val ordered = if (found != null && found.distinct().size == 4) found else wholeFrame(rgba)
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

            // Step 2: even out the light and whiten the background.
            val rgb = track(Mat())
            Imgproc.cvtColor(warped, rgb, Imgproc.COLOR_RGBA2RGB)
            val flattened = flattenLighting(rgb) { track(it) }

            // Step 3: reduce moiré, then sharpen.
            val smooth = track(Mat())
            Imgproc.bilateralFilter(flattened, smooth, 5, 40.0, 40.0)
            val blurred = track(Mat())
            Imgproc.GaussianBlur(smooth, blurred, Size(0.0, 0.0), 2.0)
            val sharp = track(Mat())
            Core.addWeighted(smooth, 1.5, blurred, -0.5, 0.0, sharp)

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
     * Looks for the biggest 4-sided shape that covers more than 20% of the picture.
     * Works on a smaller copy for speed, then scales the corners back up.
     * Returns null when no such shape is found.
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
            val edges = Mat().also { temp.add(it) }
            Imgproc.Canny(grey, edges, 50.0, 150.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also { temp.add(it) }
            Imgproc.dilate(edges, edges, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat().also { temp.add(it) }
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            temp.addAll(contours)

            val minArea = small.cols().toDouble() * small.rows() * MIN_AREA_SHARE
            var bestArea = 0.0
            var best: Array<Point>? = null
            for (contour in contours) {
                val curve = MatOfPoint2f(*contour.toArray()).also { temp.add(it) }
                val perimeter = Imgproc.arcLength(curve, true)
                val approx = MatOfPoint2f().also { temp.add(it) }
                Imgproc.approxPolyDP(curve, approx, 0.02 * perimeter, true)
                val points = approx.toArray()
                if (points.size != 4) continue
                val polygon = MatOfPoint(*points).also { temp.add(it) }
                if (!Imgproc.isContourConvex(polygon)) continue
                val area = Imgproc.contourArea(approx)
                if (area > minArea && area > bestArea) {
                    bestArea = area
                    best = points
                }
            }
            return best?.map { Point(it.x / scale, it.y / scale) }?.toTypedArray()
        } finally {
            for (mat in temp) mat.release()
        }
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

    /** Output width and height come from the longer of each pair of opposite edges. */
    private fun outputSize(c: Array<Point>): Pair<Int, Int> {
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val width = max(dist(c[0], c[1]), dist(c[3], c[2])).roundToInt()
        val height = max(dist(c[0], c[3]), dist(c[1], c[2])).roundToInt()
        return Pair(max(width, 16), max(height, 16))
    }

    /**
     * Removes uneven light on the lightness channel and stretches the contrast,
     * so the paper becomes near white while the colours stay the same.
     * Dark pages (light text on a dark background) skip the flattening and only
     * get the contrast stretch, because flattening would wash them out.
     */
    private fun flattenLighting(rgb: Mat, track: (Mat) -> Mat): Mat {
        val lab = track(Mat())
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        channels.forEach { track(it) }
        val lightness = channels[0]

        val median = percentile(lightness, 0.5)
        if (median >= DARK_PAGE_LIMIT) {
            // Estimate the background light on a small copy.
            val longSide = max(lightness.cols(), lightness.rows()).toDouble()
            val scale = if (longSide > BACKGROUND_SIZE) BACKGROUND_SIZE / longSide else 1.0
            val small = track(Mat())
            Imgproc.resize(
                lightness, small,
                Size(max(1.0, lightness.cols() * scale), max(1.0, lightness.rows() * scale)),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            // Dilate first so dark text disappears, then a big median removes what is left.
            val kernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
            Imgproc.dilate(small, small, kernel)
            Imgproc.medianBlur(small, small, 21)
            val background = track(Mat())
            Imgproc.resize(small, background, lightness.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.GaussianBlur(background, background, Size(0.0, 0.0), 5.0)
            Core.max(background, Scalar(1.0), background)
            Core.divide(lightness, background, lightness, 255.0)
        }

        // Stretch so the darkest 1% becomes black and the brightest 5% becomes white.
        val low = percentile(lightness, 0.01)
        val high = percentile(lightness, 0.95)
        if (high - low > 10) {
            val alpha = 255.0 / (high - low)
            lightness.convertTo(lightness, CvType.CV_8U, alpha, -low * alpha)
        }

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
        const val BACKGROUND_SIZE = 400.0
        const val MIN_AREA_SHARE = 0.2
        const val DARK_PAGE_LIMIT = 100
    }
}
