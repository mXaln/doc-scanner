package org.bibletranslationtools.docscanner.ocr

/**
 * One line recognizer: the files to fetch and what the inference code needs to drive it.
 *
 * Every entry is a PaddleOCR PP-OCR model — one forward pass per line at 20-70 ms, CTC-decoded
 * with no language model. An unreadable crop therefore comes back garbled or empty instead of
 * being rewritten into fluent, wrong text, which is the failure a translator can spot.
 *
 * Scripts absent from [ALL] have no recognizer accurate enough on handwriting, so their
 * projects transcribe on the server instead. See the model card in the download repository
 * (`RecognizerModels.BASE_URL`) for per-script accuracy.
 */
data class RecognizerSet(
    val script: Script,

    /** Subdirectory on the model host, and part of the local directory name. */
    override val remotePath: String,

    /** Bump when the files for this script change weights or format. */
    val revision: String,

    /** Rough download size, to show before asking the user to fetch it. */
    val approximateMegabytes: Int,

    /** Input height the model was trained at; width scales with the line's aspect ratio. */
    val imageHeight: Int = 48,

    /**
     * Whether the script reads right to left. CTC scans the image left to right, so for such a
     * script it emits the logically last character first and the line needs reversing.
     */
    val rightToLeft: Boolean = false
) : ModelSet {
    override val directoryName: String get() = "ocr-$remotePath-$revision"

    override val files: List<String> get() = listOf(CHARSET, MODEL)

    companion object {
        const val MODEL = "rec_model.onnx"

        /** JSON array of characters; index 0 of the model's output is the CTC blank. */
        const val CHARSET = "charset.json"

        /**
         * Scripts sharing a [remotePath] share one download. PP-OCRv6 covers Han and kana
         * alongside Latin in a single character table, so Latin and CJK projects fetch the same
         * file; it holds no Hangul, which is why Korean has its own model.
         */
        val ALL: Map<Script, RecognizerSet> = listOf(
            RecognizerSet(
                script = Script.LATIN,
                remotePath = "ppocrv6",
                revision = "v1",
                approximateMegabytes = 73
            ),
            RecognizerSet(
                script = Script.CJK,
                remotePath = "ppocrv6",
                revision = "v1",
                approximateMegabytes = 73
            ),
            RecognizerSet(
                script = Script.DEVANAGARI,
                remotePath = "devanagari",
                revision = "ppocrv5-v1",
                approximateMegabytes = 8
            ),
            RecognizerSet(
                script = Script.THAI,
                remotePath = "thai",
                revision = "ppocrv5-v1",
                approximateMegabytes = 8
            ),
            RecognizerSet(
                script = Script.ARABIC,
                remotePath = "arabic",
                revision = "ppocrv5-v1",
                approximateMegabytes = 8,
                rightToLeft = true
            ),
            RecognizerSet(
                script = Script.HANGUL,
                remotePath = "korean",
                revision = "ppocrv5-v1",
                approximateMegabytes = 13
            )
        ).associateBy { it.script }

        fun forScript(script: Script): RecognizerSet? = ALL[script]
    }
}
