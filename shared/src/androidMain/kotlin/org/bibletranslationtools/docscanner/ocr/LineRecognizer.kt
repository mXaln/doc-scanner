package org.bibletranslationtools.docscanner.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads the text off a single cropped line: one forward pass, then CTC decoding.
 */
internal class LineRecognizer(
    private val modelsDir: Path,
    private val set: RecognizerSet
) {
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    private val model: OrtSession
        get() = session ?: environment.createSession(
            Path(modelsDir, RecognizerSet.MODEL).toString(),
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() - 1))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setMemoryPatternOptimization(true)
            }
        ).also { session = it }

    /**
     * Output index 0 is the CTC blank, then the character table, then a space — the layout
     * PaddleOCR's `CTCLabelDecode` expects.
     */
    private val labels: List<String> by lazy {
        val json = SystemFileSystem.source(Path(modelsDir, RecognizerSet.CHARSET))
            .buffered()
            .use { it.readString() }
        val chars = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
        listOf(BLANK) + chars + listOf(" ")
    }

    fun recognize(line: Bitmap): String {
        val height = set.imageHeight
        val width = max(1, (line.width * height.toFloat() / line.height).roundToInt())
        // Width is dynamic in the graph: clamping it would simply cut the line off.
        val padded = ((width + WIDTH_MULTIPLE - 1) / WIDTH_MULTIPLE) * WIDTH_MULTIPLE

        val resized = line.scale(width, height)
        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        if (resized !== line) resized.recycle()

        // Normalize first and leave the padding at zero, which is the mid-gray the model was
        // trained to pad with. Padding in pixel space would fill it with black instead.
        val plane = height * padded
        val buffer = FloatBuffer.allocate(3 * plane)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val at = y * padded + x
                buffer.put(at, normalize((pixel shr 16) and 0xFF))
                buffer.put(plane + at, normalize((pixel shr 8) and 0xFF))
                buffer.put(2 * plane + at, normalize(pixel and 0xFF))
            }
        }

        val inputName = model.inputNames.first()
        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, 3, height.toLong(), padded.toLong())
        ).use { input ->
            model.run(mapOf(inputName to input)).use { result ->
                val logits = result[0] as OnnxTensor
                val shape = logits.info.shape
                val classes = shape[2].toInt()
                // A charset belonging to a different model decodes every index to the wrong
                // character, which reads as confident text in the wrong script rather than as a
                // failure. The pair matches only when the classes are the blank, the character
                // table and the trailing space.
                check(classes == labels.size) {
                    "The ${set.script} charset does not match its model: ${labels.size} labels " +
                        "against $classes output classes. Delete ${set.directoryName} and " +
                        "download it again."
                }
                val text = decode(logits.floatBuffer, steps = shape[1].toInt(), classes = classes)
                if (set.rightToLeft) toLogicalOrder(text) else text
            }
        }
    }

    private fun normalize(channel: Int): Float = (channel / 255f - MEAN) / STD

    /** Greedy CTC: keep the best class per step, collapsing repeats and dropping blanks. */
    private fun decode(logits: FloatBuffer, steps: Int, classes: Int): String {
        val text = StringBuilder()
        var previous = -1
        for (step in 0 until steps) {
            val offset = step * classes
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (id in 0 until classes) {
                val value = logits.get(offset + id)
                if (value > bestValue) {
                    bestValue = value
                    best = id
                }
            }
            if (best != previous && best != BLANK_ID && best in labels.indices) {
                text.append(labels[best])
            }
            previous = best
        }
        return text.toString().trim()
    }

    /**
     * Turns a right-to-left line from the visual order the model emits into reading order.
     *
     * Reversing characters one by one would tear combining marks off the letters they belong to,
     * so each base character keeps the marks that follow it and only the clusters are reversed.
     */
    private fun toLogicalOrder(text: String): String {
        val clusters = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val start = index
            index++
            while (index < text.length && text[index].isCombiningMark()) index++
            clusters.add(text.substring(start, index))
        }
        return clusters.asReversed().joinToString("")
    }

    private fun Char.isCombiningMark(): Boolean = category == CharCategory.NON_SPACING_MARK ||
        category == CharCategory.ENCLOSING_MARK ||
        category == CharCategory.COMBINING_SPACING_MARK

    fun close() {
        session?.close()
        session = null
    }

    private companion object {
        const val MEAN = 0.5f
        const val STD = 0.5f
        const val BLANK = "<blank>"
        const val BLANK_ID = 0

        /** Pad the width so shapes stay friendly to the graph's downsampling. */
        const val WIDTH_MULTIPLE = 32
    }
}
