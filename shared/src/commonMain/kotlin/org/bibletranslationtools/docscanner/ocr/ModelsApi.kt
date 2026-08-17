package org.bibletranslationtools.docscanner.ocr

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.bibletranslationtools.docscanner.data.repository.DirectoryProvider
import kotlin.time.Duration.Companion.milliseconds

/**
 * Provisions OCR models on the device.
 *
 * A device fetches the shared detector plus the one recognizer its project's script needs,
 * rather than the whole set: too large to ship inside the app, and most of it unused. Each set
 * is downloaded once into the internal app directory and reused from there.
 *
 * Downloads are resumable, so an interrupted one continues instead of starting over: partial
 * data waits in a `.part` file and the next attempt asks for the rest with a Range request.
 */
class ModelsApi(private val directoryProvider: DirectoryProvider) {

    private val logger = KotlinLogging.logger {}

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = null
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    /** Where a set's files live on the device (see [ModelSet.directoryName]). */
    fun dir(set: ModelSet): Path =
        Path(directoryProvider.internalAppDir, set.directoryName)

    fun isReady(set: ModelSet): Boolean = set.files.all { name ->
        (SystemFileSystem.metadataOrNull(Path(dir(set), name))?.size ?: 0L) > 0L
    }

    /** Drops model directories that no current set claims — old revisions, dropped scripts. */
    private fun deleteStaleSets() {
        val root = directoryProvider.internalAppDir
        if (!SystemFileSystem.exists(root)) return
        val current = RecognizerSet.ALL.values.map { it.directoryName }.toSet() +
            DetectorSet.directoryName
        SystemFileSystem.list(root)
            .filter { it.name.startsWith(DIR_PREFIX) && it.name !in current }
            .forEach { stale ->
                runCatching {
                    SystemFileSystem.list(stale).forEach {
                        SystemFileSystem.delete(it, mustExist = false)
                    }
                    SystemFileSystem.delete(stale, mustExist = false)
                    logger.info { "Removed stale models in ${stale.name}" }
                }
            }
    }

    /**
     * Downloads any missing file of [set]. [onProgress] receives the file name and its
     * completed fraction (0..1), or -1f when the total size is unknown.
     */
    suspend fun download(set: ModelSet, onProgress: suspend (String, Float) -> Unit) {
        deleteStaleSets()
        val dir = dir(set)
        SystemFileSystem.createDirectories(dir)

        for (name in set.files) {
            val target = Path(dir, name)
            if ((SystemFileSystem.metadataOrNull(target)?.size ?: 0L) > 0L) continue

            var lastError: Exception? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    downloadFile(set, name, target, onProgress)
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    logger.warn(e) { "Model download failed for $name (attempt $attempt)" }
                    if (attempt < MAX_ATTEMPTS) delay((1000L * attempt).milliseconds)
                }
            }
            lastError?.let { throw it }
        }
    }

    private suspend fun downloadFile(
        set: ModelSet,
        name: String,
        target: Path,
        onProgress: suspend (String, Float) -> Unit
    ) {
        val partial = Path(dir(set), "$name.part")
        val alreadyRead = SystemFileSystem.metadataOrNull(partial)?.size ?: 0L

        client.prepareGet("$BASE_URL${set.remotePath}/$name") {
            if (alreadyRead > 0) {
                headers { append(HttpHeaders.Range, "bytes=$alreadyRead-") }
            }
        }.execute { response ->
            val resumed = response.status == HttpStatusCode.PartialContent
            if (response.status != HttpStatusCode.OK && !resumed) {
                throw IllegalStateException("Download of $name failed: ${response.status}")
            }
            // The server ignored our Range request, so start over.
            val offset = if (resumed) alreadyRead else 0L
            val total = response.contentLength()?.let { it + offset } ?: -1L

            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_SIZE)
            var read = offset
            var lastReported = -1

            SystemFileSystem.sink(partial, append = resumed).buffered().use { sink ->
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue

                    sink.write(buffer, 0, count)
                    read += count

                    if (total > 0) {
                        val percent = (read * 100 / total).toInt()
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(name, read.toFloat() / total)
                        }
                    } else {
                        onProgress(name, -1f)
                    }
                }
            }

            if (total > 0 && read != total) {
                throw IllegalStateException("Download of $name truncated: $read of $total bytes")
            }
        }

        SystemFileSystem.atomicMove(partial, target)
        logger.info { "Downloaded model $name" }
    }

    fun delete(set: ModelSet) {
        val dir = dir(set)
        if (!SystemFileSystem.exists(dir)) return
        SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
    }

    companion object {
        /**
         * Where the models are hosted, pinned to a tag rather than a branch so that a build can
         * only receive the weights it was released with.
         *
         * To change a model: upload it, tag that commit, put the new tag here, and bump that
         * set's own `revision`. The local directory name carries the revision, so only the
         * changed set is downloaded again and the others stay on disk.
         */
        const val BASE_URL =
            "https://huggingface.co/wycliffeassociates/docscanner-ocr/resolve/models-v1/"

        private const val DIR_PREFIX = "ocr-"

        private const val MAX_ATTEMPTS = 3
        private const val BUFFER_SIZE = 256 * 1024
    }
}
