package org.bibletranslationtools.docscanner.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.files.Path
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

actual fun isLocalTranscriptionAvailable(): Boolean = true

actual fun createLocalTranscriber(
    modelsDir: Path,
    detectorDir: Path,
    set: RecognizerSet
): LocalTranscriber = PageTranscriber(modelsDir, detectorDir, set)

/**
 * Transcribes a page by cutting it into lines and reading each one.
 *
 * A scan can arrive in any of four orientations: upside down, or sideways when the camera was
 * turned, and CJK is sometimes written in columns. Upside down is the awkward case, because the
 * lines are still horizontal — detection succeeds and only the glyphs are inverted. The
 * recognizer decides which orientation was right, since geometry cannot: rotating horizontal
 * text produces plausible-looking lines whichever way it is turned.
 *
 * A page is only re-read when the first attempt would be refused, so a correctly oriented page
 * costs nothing extra.
 */
private class PageTranscriber(
    private val modelsDir: Path,
    private val detectorDir: Path,
    private val set: RecognizerSet
) : LocalTranscriber {

    private val recognizer: LineRecognizer by lazy { LineRecognizer(modelsDir, set) }
    private val detector: TextDetector by lazy { TextDetector(detectorDir) }

    override suspend fun transcribe(
        imagePath: Path,
        onProgress: suspend (Int, Int) -> Unit
    ): String {
        val page = BitmapFactory.decodeFile(imagePath.toString())
            ?: throw IllegalArgumentException("Could not decode image $imagePath")

        try {
            var best = readPage(page, onProgress)
            if (set.script.matches(best)) return best

            for (degrees in RETRY_ROTATIONS) {
                logger.info { "Page did not read as ${set.script}; trying it rotated $degrees°" }
                val rotated = LineSegmenter.rotate(page, degrees.toFloat())
                val text = try {
                    readPage(rotated, onProgress)
                } finally {
                    rotated.recycle()
                }
                if (scriptScore(text) > scriptScore(best)) best = text
                if (set.script.matches(best)) break
            }
            return best
        } finally {
            page.recycle()
        }
    }

    private suspend fun readPage(
        page: Bitmap,
        onProgress: suspend (Int, Int) -> Unit
    ): String {
        val crops = lineCrops(page)
        logger.info { "Page ${page.width}x${page.height} cut into ${crops.size} lines" }
        val lines = mutableListOf<String>()
        try {
            crops.forEachIndexed { index, line ->
                currentCoroutineContext().ensureActive()
                onProgress(index + 1, crops.size)
                val text = recognizer.recognize(line).trim()
                if (text.isNotEmpty()) lines.add(text)
            }
        } finally {
            crops.forEach { it.recycle() }
        }
        return lines.joinToString("\n")
    }

    /**
     * Cuts the page into line images.
     *
     * The detector needs a level page, since its boxes are axis-aligned and on a tilted page a
     * single box spans two lines, merging them. Rotating resamples the page though, which can
     * cost the detector the reading it would have got from the original, so a page it declines
     * straightened is tried as it came before falling back to [LineSegmenter].
     */
    private fun lineCrops(page: Bitmap): List<Bitmap> {
        val angle = LineSegmenter.skewOf(page)
        if (abs(angle) >= LineSegmenter.MIN_SKEW_DEGREES) {
            val straight = LineSegmenter.rotate(page, angle)
            val crops = try {
                detector.detect(straight)
            } finally {
                straight.recycle()
            }
            if (crops != null) return crops
        }

        detector.detect(page)?.let { return it }

        logger.info { "Detector declined the page; falling back to the line segmenter" }
        return LineSegmenter.segment(page).lines
    }

    /** How many characters came out in the script we expect — the tie-breaker between reads. */
    private fun scriptScore(text: String): Int =
        text.count { it.isLetter() && scriptOf(it.toString()) == set.script }

    override fun close() {
        runCatching { recognizer.close() }
            .onFailure { logger.warn(it) { "Failed to release the ${set.script} recognizer" } }
        runCatching { detector.close() }
            .onFailure { logger.warn(it) { "Failed to release the text detector" } }
    }

    private companion object {
        /**
         * Orientations to try, in order, when a page does not read as its script. Upside down
         * first: a page photographed the wrong way up is far more common than a sideways one.
         * 270° turns columns into rows in reading order, the rightmost becoming the top line.
         */
        val RETRY_ROTATIONS = intArrayOf(180, 270, 90)
    }
}
