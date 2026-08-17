package org.bibletranslationtools.docscanner.ocr

import kotlinx.io.files.Path

interface LocalTranscriber {
    /**
     * Transcribes the page image at [imagePath].
     * [onProgress] is called with the current line index (1-based) and the line count.
     */
    suspend fun transcribe(
        imagePath: Path,
        onProgress: suspend (Int, Int) -> Unit
    ): String

    /** Releases the inference sessions and their (large) native memory. */
    fun close()
}

/** Whether on-device transcription is implemented for this platform. */
expect fun isLocalTranscriptionAvailable(): Boolean

/**
 * Creates a transcriber for [set], loading its files from [modelsDir] and the shared
 * line detector from [detectorDir] (both from [ModelsApi.dir]).
 * Sessions are created lazily on first use; always [LocalTranscriber.close] it when done.
 */
expect fun createLocalTranscriber(
    modelsDir: Path,
    detectorDir: Path,
    set: RecognizerSet
): LocalTranscriber
