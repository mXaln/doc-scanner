package org.bibletranslationtools.docscanner.ocr

import kotlinx.io.files.Path

/**
 * Not implemented on iOS: it needs `onnxruntime-objc` and a cinterop binding, and `iosApp` has
 * no package manager wired up. The UI hides on-device transcription while this returns false.
 */
actual fun isLocalTranscriptionAvailable(): Boolean = false

actual fun createLocalTranscriber(
    modelsDir: Path,
    detectorDir: Path,
    set: RecognizerSet
): LocalTranscriber =
    throw UnsupportedOperationException("On-device transcription is not available on iOS")
