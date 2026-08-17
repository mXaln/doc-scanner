package org.bibletranslationtools.docscanner.ocr

/**
 * A set of model files that [ModelsApi] can provision: one remote directory, fetched
 * into one local directory. Implemented by [RecognizerSet] (per script) and [DetectorSet]
 * (one for all of them).
 */
interface ModelSet {

    /** Subdirectory on the model host, and part of [directoryName]. */
    val remotePath: String

    /**
     * Local directory name. It carries the remote path and a revision, because the file names
     * are the same in every set and a device would otherwise reuse one set's model for another.
     */
    val directoryName: String

    val files: List<String>
}
