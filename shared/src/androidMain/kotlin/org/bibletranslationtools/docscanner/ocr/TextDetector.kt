package org.bibletranslationtools.docscanner.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import androidx.core.graphics.scale
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the text lines on a page with the DB text detector (see [DetectorSet]).
 *
 * The model emits a per-pixel probability of being text. That becomes line crops by
 * thresholding the map, labeling the blobs, and merging blobs that share rows: the detector
 * marks words and glyph clusters, while a recognizer reads a whole line at a time.
 */
internal class TextDetector(private val modelsDir: Path) {

    private val logger = KotlinLogging.logger {}

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    private val model: OrtSession
        get() = session ?: environment.createSession(
            Path(modelsDir, DetectorSet.MODEL).toString(),
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() - 1))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setMemoryPatternOptimization(true)
            }
        ).also { session = it }

    /**
     * Line crops for [page], or `null` when the detector found nothing it could use — the
     * caller then falls back to [LineSegmenter].
     */
    fun detect(page: Bitmap): List<Bitmap>? {
        val map = probabilityMap(page)
        val boxes = boxesOf(map)
        if (boxes.isEmpty()) {
            logger.info { "Detector found no text on the page" }
            return null
        }
        if (isVertical(boxes)) {
            // Columns cannot be merged into lines: every one of them shares every row, so the
            // page would collapse into a single band.
            logger.info { "Detector boxed columns rather than lines" }
            return null
        }

        val lines = toLines(boxes)
        if (lines.size > LineSegmenter.MAX_LINES) {
            logger.warn { "Page has ${lines.size} lines; transcribing the first ${LineSegmenter.MAX_LINES}" }
        }

        val scaleX = map.width.toDouble() / page.width
        val scaleY = map.height.toDouble() / page.height
        val crops = lines.take(LineSegmenter.MAX_LINES).mapNotNull { line ->
            crop(page, line, scaleX, scaleY)
        }
        return crops.ifEmpty { null }
    }

    /** The detector's own view of the page: the probability map and the size it ran at. */
    private class ProbabilityMap(val width: Int, val height: Int, val text: FloatArray) {
        /**
         * Mean probability over a rectangle, the score DB gives a candidate box. Accumulated as
         * a Double because a box covers thousands of pixels and Float drifts enough over that
         * many additions to move the mean across [BOX_THRESHOLD].
         */
        fun meanOver(box: Box): Double {
            var sum = 0.0
            for (y in box.top..box.bottom) {
                val row = y * width
                for (x in box.left..box.right) sum += text[row + x]
            }
            return sum / ((box.bottom - box.top + 1).toLong() * (box.right - box.left + 1))
        }
    }

    private fun probabilityMap(page: Bitmap): ProbabilityMap {
        // Long side to MAX_SIDE, then floored to a multiple of 32 for the downsampling stack.
        val scale = min(1f, MAX_SIDE.toFloat() / max(page.width, page.height))
        val width = max(32, (page.width * scale).toInt() / 32 * 32)
        val height = max(32, (page.height * scale).toInt() / 32 * 32)

        val resized = page.scale(width, height)
        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        if (resized !== page) resized.recycle()

        // The model was trained on BGR channels, normalized in that order.
        val plane = width * height
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            buffer.put(i, normalize(pixel and 0xFF, 0))
            buffer.put(plane + i, normalize((pixel shr 8) and 0xFF, 1))
            buffer.put(2 * plane + i, normalize((pixel shr 16) and 0xFF, 2))
        }

        val inputName = model.inputNames.first()
        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, 3, height.toLong(), width.toLong())
        ).use { input ->
            model.run(mapOf(inputName to input)).use { result ->
                val output = (result[0] as OnnxTensor).floatBuffer
                val text = FloatArray(plane)
                output.get(text, 0, plane)
                ProbabilityMap(width, height, text)
            }
        }
    }

    private fun normalize(channel: Int, index: Int): Float =
        (channel / 255f - MEAN[index]) / STD[index]

    private class Box(var top: Int, var bottom: Int, var left: Int, var right: Int) {
        val height: Int get() = bottom - top + 1
        val width: Int get() = right - left + 1
        val area: Long get() = height.toLong() * width

        fun absorb(other: Box) {
            top = min(top, other.top)
            bottom = max(bottom, other.bottom)
            left = min(left, other.left)
            right = max(right, other.right)
        }
    }

    /** Bounding boxes of the thresholded map's blobs, less the specks and the weakly scored. */
    private fun boxesOf(map: ProbabilityMap): List<Box> {
        val text = BooleanArray(map.text.size) { map.text[it] > THRESHOLD }
        val visited = BooleanArray(text.size)
        val queue = IntArray(text.size)
        val boxes = mutableListOf<Box>()

        for (start in text.indices) {
            if (!text[start] || visited[start]) continue

            // Iterative flood fill; recursion would overflow on a page-sized blob.
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            val box = Box(start / map.width, start / map.width, start % map.width, start % map.width)

            while (head < tail) {
                val at = queue[head++]
                val x = at % map.width
                val y = at / map.width
                if (y < box.top) box.top = y
                if (y > box.bottom) box.bottom = y
                if (x < box.left) box.left = x
                if (x > box.right) box.right = x

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= map.height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= map.width) continue
                        val next = ny * map.width + nx
                        if (text[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            if (box.width < MIN_SIDE || box.height < MIN_SIDE) continue
            if (map.meanOver(box) < BOX_THRESHOLD) continue
            boxes.add(box)
        }
        return boxes
    }

    /**
     * Whether the detector boxed columns instead of lines, as it can for CJK written in a grid.
     *
     * Weighted by area so that one tall box — a single word with a long ascender — cannot swing
     * a page of ordinary lines.
     */
    private fun isVertical(boxes: List<Box>): Boolean {
        var area = 0L
        var tall = 0L
        for (box in boxes) {
            area += box.area
            if (box.height > VERTICAL_ASPECT * box.width) tall += box.area
        }
        return area > 0 && tall.toDouble() / area >= VERTICAL_SHARE
    }

    /** Groups boxes that share rows into lines, then folds stray diacritics into them. */
    private fun toLines(boxes: List<Box>): List<Box> {
        val lines = mutableListOf<Box>()
        for (box in boxes.sortedBy { it.top }) {
            val line = lines.firstOrNull { line ->
                val shared = min(box.bottom, line.bottom) - max(box.top, line.top) + 1
                shared >= ROW_OVERLAP * min(box.height, line.height)
            }
            if (line == null) lines.add(box) else line.absorb(box)
        }
        lines.sortBy { it.top }
        if (lines.size < 2) return lines

        // A mark that sits clear of its baseline — a Devanagari matra, a Thai tone mark —
        // arrives as its own small blob. Recognizing it alone yields junk and dropping it loses
        // text, so it joins the nearest line.
        val median = lines.map { it.height }.sorted()[lines.size / 2]
        val kept = lines.filterTo(mutableListOf()) { it.height >= FRAGMENT_HEIGHT * median }
        if (kept.isEmpty()) return lines

        for (fragment in lines.filter { it.height < FRAGMENT_HEIGHT * median }) {
            val nearest = kept.minBy { gapBetween(it, fragment) }
            if (gapBetween(nearest, fragment) > median) {
                kept.add(fragment)      // too far from any line to belong to it
            } else {
                nearest.absorb(fragment)
            }
        }
        kept.sortBy { it.top }
        return kept
    }

    private fun gapBetween(line: Box, other: Box): Int =
        min(abs(line.top - other.bottom), abs(other.top - line.bottom))

    private fun crop(page: Bitmap, line: Box, scaleX: Double, scaleY: Double): Bitmap? {
        val top = (line.top / scaleY).roundToInt()
        val bottom = (line.bottom / scaleY).roundToInt()
        val left = (line.left / scaleX).roundToInt()
        val right = (line.right / scaleX).roundToInt()

        // Detector boxes hug the ink; the recognizers expect a margin around it.
        val padY = max(4, ((bottom - top + 1) * PAD_Y).roundToInt())
        val cropLeft = max(0, left - PAD_X)
        val cropTop = max(0, top - padY)
        val width = min(page.width, right + PAD_X) - cropLeft
        val height = min(page.height, bottom + padY) - cropTop
        if (width < 8 || height < 8) return null

        val cropped = Bitmap.createBitmap(page, cropLeft, cropTop, width, height)
        // createBitmap hands back the source itself when the rectangle covers all of it, and
        // the caller may recycle that page right after — which would take the crop with it.
        return if (cropped === page) {
            page.copy(page.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            cropped
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    private companion object {
        /** Long side the detector runs at, as in the model's own inference configuration. */
        const val MAX_SIDE = 960

        /** ImageNet channel statistics the model was normalized with, in BGR order. */
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * Where the probability map counts as text, and the mean a box must reach to be kept.
         * Both sit below the model's own defaults for printed text, which handwriting does not
         * score highly enough to meet.
         */
        const val THRESHOLD = 0.2f
        const val BOX_THRESHOLD = 0.45

        /** Blobs thinner than this, in detector pixels, are specks. */
        const val MIN_SIDE = 4

        /** Boxes join a line when their rows overlap by this share of the shorter one. */
        const val ROW_OVERLAP = 0.35

        /** A line shorter than this share of the median height is a stray mark. */
        const val FRAGMENT_HEIGHT = 0.5

        /** A box this much taller than wide is a column, and [isVertical] rejects the page
         * once that many boxes by area are columns. */
        const val VERTICAL_ASPECT = 1.5
        const val VERTICAL_SHARE = 0.5

        /** Crop padding: a share of the line's height, and a fixed margin at the sides. */
        const val PAD_Y = 0.25
        const val PAD_X = 8
    }
}
