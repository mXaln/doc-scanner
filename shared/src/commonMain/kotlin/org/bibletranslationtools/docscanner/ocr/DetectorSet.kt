package org.bibletranslationtools.docscanner.ocr

/**
 * The text-line detector: one model for every script, downloaded alongside a recognizer.
 *
 * Recognizers read one line at a time and degrade badly on a whole page, so every page goes
 * through this first. Being trained, it ignores printed rulings and grid squares that pixel
 * heuristics mistake for writing.
 *
 * PaddleOCR's mobile DB detector: 5 MB, 12-130 ms per page.
 */
object DetectorSet : ModelSet {

    override val remotePath: String = "detector"

    /** Bump when the file changes weights or format. */
    private const val REVISION = "ppocrv5-mobile-v1"

    override val directoryName: String = "ocr-$remotePath-$REVISION"

    override val files: List<String> = listOf(MODEL)

    const val MODEL = "det_model.onnx"
}
