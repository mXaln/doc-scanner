package org.bibletranslationtools.docscanner.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Splits a scanned page into single text lines from the pixels alone, with no model.
 *
 * 1. Downscale a copy for analysis.
 * 2. Adaptive (Bradley) binarization, so uneven lighting in photos doesn't wash out ink.
 * 3. Skew estimate by maximizing the contrast of the horizontal ink projection.
 * 4. Deskew the full-resolution page, then cut it at the valleys of that projection.
 *
 * Assumes a single column of roughly horizontal text.
 *
 * [TextDetector] is the primary line finder; [segment] serves the pages it declines.
 * [skewOf] and [rotate] are used by both paths.
 */
object LineSegmenter {

    private val logger = KotlinLogging.logger {}

    /** Analysis resolution: enough for the projection profile, cheap to scan repeatedly. */
    private const val ANALYSIS_WIDTH = 1200

    /** Bradley threshold: ink is this fraction darker than its local mean. */
    private const val INK_CONTRAST = 0.88

    private const val MAX_SKEW_DEGREES = 5f
    private const val SKEW_STEP_DEGREES = 0.25f

    /** Below this the page is level enough that rotating it would only cost sharpness. */
    const val MIN_SKEW_DEGREES = 0.3f

    /** Rows count as text while their ink is above this fraction of the busiest row. */
    private const val ROW_INK_THRESHOLD = 0.10

    /**
     * Percentile of the row profile taken as its baseline. Squared and ruled paper puts ink on
     * every row, so the profile never returns to zero between lines and the text threshold has
     * to be measured from this level rather than from zero.
     */
    private const val BASELINE_PERCENTILE = 0.30

    /**
     * Percentile of the raw band heights taken as the line height. Above the median, because
     * rulings and split diacritics contribute thin bands that would otherwise drag the
     * estimate down and, with it, the merge gap and the height floor below.
     */
    private const val LINE_HEIGHT_PERCENTILE = 0.60

    /** A column belongs to a line when this fraction of the band's rows has ink there. */
    private const val COLUMN_INK_THRESHOLD = 0.15

    /**
     * Absolute floor for that column test. On thin bands the proportional test alone admits
     * the one or two pixels a faint printed rule contributes, which stretches the line to the
     * full page width and leaves the writing occupying a fraction of the crop.
     */
    private const val MIN_COLUMN_INK = 3

    /** Column runs separated by more than this many line heights are treated separately. */
    private const val COLUMN_GAP = 3.0

    /** A separated run carrying less than this share of the line's ink is not text. */
    private const val MIN_GROUP_INK = 0.08

    /** A run with ink on more than this share of the band's rows is a rule, not writing. */
    private const val MAX_GROUP_DENSITY = 0.7

    /** Above this ink density a band is a solid rule (page edge, underline), not text. */
    private const val MAX_BAND_DENSITY = 0.5

    /** Upper bound on lines per page, to keep worst-case runtime bounded. */
    const val MAX_LINES = 80

    data class Result(
        val lines: List<Bitmap>,
        /** Lines dropped by the [MAX_LINES] cap. */
        val truncated: Int
    )

    fun segment(page: Bitmap): Result {
        val scale = if (page.width > ANALYSIS_WIDTH) {
            ANALYSIS_WIDTH.toFloat() / page.width
        } else 1f

        val analysis = downscale(page, scale)
        val mask = binarize(analysis)
        val angle = estimateSkew(mask)

        val deskewed: Bitmap
        val deskewedMask: InkMask
        if (abs(angle) >= MIN_SKEW_DEGREES) {
            deskewed = rotate(page, angle)
            val rotatedAnalysis = downscale(deskewed, scale)
            deskewedMask = binarize(rotatedAnalysis)
            if (rotatedAnalysis !== deskewed) rotatedAnalysis.recycle()
        } else {
            deskewed = page
            deskewedMask = mask
        }
        if (analysis !== page) analysis.recycle()

        val bands = findBands(deskewedMask)
        val effectiveScale = deskewedMask.width.toFloat() / deskewed.width

        val crops = bands.take(MAX_LINES).mapNotNull { band ->
            crop(deskewed, band, effectiveScale)
        }
        val truncated = max(0, bands.size - MAX_LINES)
        if (truncated > 0) {
            logger.warn { "Page has ${bands.size} lines; transcribing the first $MAX_LINES" }
        }

        // If nothing looked like a text line, fall back to the whole page.
        val lines = if (crops.isEmpty()) listOf(copyOf(deskewed)) else crops
        if (deskewed !== page) deskewed.recycle()

        return Result(lines, truncated)
    }

    /** The rotation in degrees that levels this page's text, in [rotate]'s direction. */
    fun skewOf(page: Bitmap): Float {
        val scale = if (page.width > ANALYSIS_WIDTH) {
            ANALYSIS_WIDTH.toFloat() / page.width
        } else 1f
        val analysis = downscale(page, scale)
        val angle = estimateSkew(binarize(analysis))
        if (analysis !== page) analysis.recycle()
        return angle
    }

    private class InkMask(val width: Int, val height: Int, val ink: BooleanArray) {
        /** Ink pixels as flat x/y arrays, for the skew search. */
        val xs: IntArray
        val ys: IntArray

        init {
            var count = 0
            for (i in ink.indices) if (ink[i]) count++
            xs = IntArray(count)
            ys = IntArray(count)
            var at = 0
            for (i in ink.indices) {
                if (ink[i]) {
                    xs[at] = i % width
                    ys[at] = i / width
                    at++
                }
            }
        }
    }

    private fun downscale(page: Bitmap, scale: Float): Bitmap {
        if (scale >= 1f) return page
        val width = max(1, (page.width * scale).roundToInt())
        val height = max(1, (page.height * scale).roundToInt())
        return page.scale(width, height)
    }

    private fun copyOf(bitmap: Bitmap): Bitmap =
        bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

    private fun binarize(bitmap: Bitmap): InkMask {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // Summed-area table for constant-time window means.
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += gray[y * width + x]
                integral[(y + 1) * (width + 1) + (x + 1)] =
                    integral[y * (width + 1) + (x + 1)] + rowSum
            }
        }

        val radius = max(6, width / 32)
        val ink = BooleanArray(width * height)
        for (y in 0 until height) {
            val top = max(0, y - radius)
            val bottom = min(height - 1, y + radius)
            for (x in 0 until width) {
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                val area = (bottom - top + 1) * (right - left + 1)
                val sum = integral[(bottom + 1) * (width + 1) + (right + 1)] -
                    integral[top * (width + 1) + (right + 1)] -
                    integral[(bottom + 1) * (width + 1) + left] +
                    integral[top * (width + 1) + left]
                val value = gray[y * width + x]
                ink[y * width + x] = value * area < sum * INK_CONTRAST
            }
        }
        return InkMask(width, height, ink)
    }

    /**
     * Picks the rotation whose row projection has the highest contrast: when lines are
     * level, ink concentrates in few rows and the sum of squared row counts peaks.
     */
    private fun estimateSkew(mask: InkMask): Float {
        if (mask.xs.isEmpty()) return 0f

        val centerX = mask.width / 2
        var bestAngle = 0f
        var bestScore = -1.0

        var angle = -MAX_SKEW_DEGREES
        while (angle <= MAX_SKEW_DEGREES) {
            val slope = tan(angle * PI_OVER_180)
            val profile = IntArray(mask.height)
            for (i in mask.xs.indices) {
                val y = mask.ys[i] + ((mask.xs[i] - centerX) * slope).roundToInt()
                if (y in 0 until mask.height) profile[y]++
            }
            var score = 0.0
            for (count in profile) score += count.toDouble() * count
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += SKEW_STEP_DEGREES
        }
        return bestAngle
    }

    /** Rotates a page onto a white canvas large enough to hold it. */
    fun rotate(page: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { setRotate(degrees, page.width / 2f, page.height / 2f) }
        val bounds = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)

        val rotated = createBitmap(bounds.width().roundToInt(), bounds.height().roundToInt())
        Canvas(rotated).apply {
            // Paper-white, so the corners exposed by the rotation don't read as ink.
            drawColor(Color.WHITE)
            drawBitmap(page, matrix, null)
        }
        return rotated
    }

    private data class Band(val top: Int, val bottom: Int, val left: Int, val right: Int)

    private fun findBands(mask: InkMask): List<Band> {
        val profile = IntArray(mask.height)
        for (y in 0 until mask.height) {
            var count = 0
            val row = y * mask.width
            for (x in 0 until mask.width) if (mask.ink[row + x]) count++
            profile[y] = count
        }

        val peak = profile.max()
        if (peak == 0) return emptyList()

        // Nearest-rank percentile: the ink a row carries when it holds no writing.
        val ordered = profile.sortedArray()
        val baseline = ordered[((ordered.size - 1) * BASELINE_PERCENTILE).toInt()].toDouble()
        val threshold = baseline + max(1.0, (peak - baseline) * ROW_INK_THRESHOLD)

        val raw = mutableListOf<IntArray>()
        var start = -1
        for (y in profile.indices) {
            val isText = profile[y] >= threshold
            if (isText && start < 0) start = y
            if (!isText && start >= 0) {
                raw.add(intArrayOf(start, y - 1))
                start = -1
            }
        }
        if (start >= 0) raw.add(intArrayOf(start, mask.height - 1))
        if (raw.isEmpty()) return emptyList()

        val heights = raw.map { it[1] - it[0] + 1 }.sorted()
        val median = heights[minOf(heights.size - 1, (heights.size * LINE_HEIGHT_PERCENTILE).toInt())]

        // Merge bands split by the gap between an ascender row and the body of a line.
        val merged = mutableListOf<IntArray>()
        for (band in raw) {
            val previous = merged.lastOrNull()
            if (previous != null && band[0] - previous[1] <= max(2, median / 4)) {
                previous[1] = band[1]
            } else {
                merged.add(band.copyOf())
            }
        }

        // Bands are filtered by height only. A clipped sliver and a genuinely short line — a
        // signature, a single word — cannot be told apart by geometry or by recognizer
        // confidence, and a stray line is visible to whoever reviews the transcription while a
        // dropped one is not.
        val minHeight = max(6, (median * 0.35).roundToInt())
        return merged
            .filter { it[1] - it[0] + 1 >= minHeight }
            .mapNotNull { band -> horizontalExtent(mask, band[0], band[1]) }
    }

    /**
     * Narrows a band to the writing on it.
     *
     * Ink columns are grouped, and a group is discarded only when it cannot be text: saturated
     * top to bottom (a page border, a ruled line) or holding a negligible share of the band's
     * ink (specks). The crop spans every surviving group, since keeping only the densest would
     * cut off words separated by the wide gaps normal in cursive.
     */
    private fun horizontalExtent(mask: InkMask, top: Int, bottom: Int): Band? {
        val height = bottom - top + 1
        val counts = IntArray(mask.width)
        for (y in top..bottom) {
            val row = y * mask.width
            for (x in 0 until mask.width) if (mask.ink[row + x]) counts[x]++
        }

        val minCount = max(MIN_COLUMN_INK, (height * COLUMN_INK_THRESHOLD).roundToInt())
        val columns = (0 until mask.width).filter { counts[it] >= minCount }
        if (columns.isEmpty()) return null

        val maxGap = max(8, (height * COLUMN_GAP).roundToInt())
        val groups = mutableListOf<IntArray>()
        var left = columns.first()

        for ((index, column) in columns.withIndex()) {
            val next = columns.getOrNull(index + 1)
            if (next != null && next - column <= maxGap) continue
            groups.add(intArrayOf(left, column))
            if (next != null) left = next
        }

        val text = groups
            .map { it to inkBetween(counts, it[0], it[1]) }
            .filter { (group, ink) ->
                ink <= (group[1] - group[0] + 1).toLong() * height * MAX_GROUP_DENSITY
            }
        if (text.isEmpty()) return null

        val strongest = text.maxOf { (_, ink) -> ink }
        val kept = text.filter { (_, ink) -> ink >= strongest * MIN_GROUP_INK }
        val spanLeft = kept.first().first[0]
        val spanRight = kept.last().first[1]

        val area = (spanRight - spanLeft + 1).toLong() * height
        if (inkBetween(counts, spanLeft, spanRight) > area * MAX_BAND_DENSITY) return null

        return Band(top, bottom, spanLeft, spanRight)
    }

    private fun inkBetween(counts: IntArray, left: Int, right: Int): Long {
        var sum = 0L
        for (x in left..right) sum += counts[x]
        return sum
    }

    private fun crop(page: Bitmap, band: Band, scale: Float): Bitmap? {
        // The recognizers expect a margin around the ink rather than a flush cut.
        val padY = max(4, ((band.bottom - band.top + 1) * 0.25f / scale).roundToInt())
        val padX = max(4, (8 / scale).roundToInt())

        val top = max(0, (band.top / scale).roundToInt() - padY)
        val bottom = min(page.height - 1, (band.bottom / scale).roundToInt() + padY)
        val left = max(0, (band.left / scale).roundToInt() - padX)
        val right = min(page.width - 1, (band.right / scale).roundToInt() + padX)

        val width = right - left + 1
        val height = bottom - top + 1
        if (width < 8 || height < 8) return null

        return Bitmap.createBitmap(page, left, top, width, height)
    }

    private const val PI_OVER_180 = (kotlin.math.PI / 180.0).toFloat()
}
