package org.bibletranslationtools.docscanner.ui.viewmodel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import docscanner.composeapp.generated.resources.Res
import docscanner.composeapp.generated.resources.create_pdf_failed
import docscanner.composeapp.generated.resources.creating_pdf
import docscanner.composeapp.generated.resources.delete_pdf_confirm
import docscanner.composeapp.generated.resources.delete_pdf_failed
import docscanner.composeapp.generated.resources.deleting_pdf
import docscanner.composeapp.generated.resources.download_models_failed
import docscanner.composeapp.generated.resources.downloading_model
import docscanner.composeapp.generated.resources.downloading_models
import docscanner.composeapp.generated.resources.loading_pdfs
import docscanner.composeapp.generated.resources.logged_out
import docscanner.composeapp.generated.resources.logging_out
import docscanner.composeapp.generated.resources.preparing_images
import docscanner.composeapp.generated.resources.rename_pdf_failed
import docscanner.composeapp.generated.resources.renaming_pdf
import docscanner.composeapp.generated.resources.transcribe_failed
import docscanner.composeapp.generated.resources.transcribe_script_unsupported
import docscanner.composeapp.generated.resources.transcribe_wrong_script
import docscanner.composeapp.generated.resources.transcribing_page
import docscanner.composeapp.generated.resources.upload_images_failed
import docscanner.composeapp.generated.resources.upload_images_success
import docscanner.composeapp.generated.resources.uploading_images
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import org.bibletranslationtools.docscanner.api.HtrUser
import org.bibletranslationtools.docscanner.api.ImageRequest
import org.bibletranslationtools.docscanner.api.Model
import org.bibletranslationtools.docscanner.api.TranscriberApi
import org.bibletranslationtools.docscanner.data.Settings.KEY_PREF_DEFAULT_MODEL
import org.bibletranslationtools.docscanner.data.Settings.KEY_PREF_PROCESS_IMMEDIATELY
import org.bibletranslationtools.docscanner.data.models.Alert
import org.bibletranslationtools.docscanner.data.models.Image
import org.bibletranslationtools.docscanner.data.models.Pdf
import org.bibletranslationtools.docscanner.data.models.Progress
import org.bibletranslationtools.docscanner.data.models.Project
import org.bibletranslationtools.docscanner.data.models.getName
import org.bibletranslationtools.docscanner.data.repository.DirectoryProvider
import org.bibletranslationtools.docscanner.data.repository.PdfRepository
import org.bibletranslationtools.docscanner.data.repository.PreferenceRepository
import org.bibletranslationtools.docscanner.data.repository.getPref
import org.bibletranslationtools.docscanner.ocr.DetectorSet
import org.bibletranslationtools.docscanner.ocr.LocalTranscriber
import org.bibletranslationtools.docscanner.ocr.RecognizerModels
import org.bibletranslationtools.docscanner.ocr.RecognizerSet
import org.bibletranslationtools.docscanner.ocr.createLocalTranscriber
import org.bibletranslationtools.docscanner.ocr.script
import org.bibletranslationtools.docscanner.platform.renderPdfToImages
import org.bibletranslationtools.docscanner.ui.common.ConfirmAction
import org.bibletranslationtools.docscanner.ui.screens.project.components.TranscriptionResult
import org.bibletranslationtools.docscanner.ui.screens.project.components.UploadStatus
import org.bibletranslationtools.docscanner.utils.FileUtils
import org.jetbrains.compose.resources.getString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ProjectState(
    val user: HtrUser? = null,
    val pdfs: List<Pdf> = emptyList(),
    val confirmAction: ConfirmAction? = null,
    val alert: Alert? = null,
    val progress: Progress? = null,
    val uploadStatus: UploadStatus? = null,
    val transcription: TranscriptionResult? = null
)

sealed class ProjectEvent {
    data object Idle : ProjectEvent()
    data class CreatePdf(val pdfPath: Path) : ProjectEvent()
    data class RenamePdf(val pdf: Pdf, val newName: String) : ProjectEvent()
    data class DeletePdf(val pdf: Pdf) : ProjectEvent()
    data class OpenPdf(val pdf: Pdf) : ProjectEvent()
    data class PdfOpened(val path: Path) : ProjectEvent()
    data class UploadImages(val images: List<Image>) : ProjectEvent()
    data class ExtractImages(val pdf: Pdf): ProjectEvent()
    data class TranscribeLocally(val pdf: Pdf) : ProjectEvent()
    data class ImagesExtracted(val images: List<Image>): ProjectEvent()
    data object Logout : ProjectEvent()
    data object RefreshUser : ProjectEvent()
}

class ProjectViewModel(
    private val project: Project,
    private val directoryProvider: DirectoryProvider,
    private val pdfRepository: PdfRepository,
    private val transcriberApi: TranscriberApi,
    private val preferenceRepository: PreferenceRepository,
    private val recognizerModels: RecognizerModels
) : ScreenModel {

    private var _state = MutableStateFlow(ProjectState())
    val state: StateFlow<ProjectState> = _state
        .onStart { initialize() }
        .stateIn(
            scope = screenModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ProjectState()
        )

    private val _event: Channel<ProjectEvent> = Channel()
    val event = _event.receiveAsFlow()

    private val logger = KotlinLogging.logger {}

    private var transcribeJob: Job? = null

    fun onEvent(event: ProjectEvent) {
        when (event) {
            is ProjectEvent.CreatePdf -> createPdf(event.pdfPath)
            is ProjectEvent.RenamePdf -> renamePdf(event.pdf, event.newName)
            is ProjectEvent.DeletePdf -> deletePdf(event.pdf)
            is ProjectEvent.OpenPdf -> openPdf(event.pdf)
            is ProjectEvent.UploadImages -> uploadImages(event.images)
            is ProjectEvent.ExtractImages -> extractImages(event.pdf)
            is ProjectEvent.TranscribeLocally -> transcribeLocally(event.pdf)
            is ProjectEvent.Logout -> logout()
            is ProjectEvent.RefreshUser -> refreshUser()
            else -> resetChannel()
        }
    }

    private fun initialize() {
        screenModelScope.launch(Dispatchers.Default) {
            updateProgress(Progress(-1f, getString(Res.string.loading_pdfs)))

            updateUser(transcriberApi.getUser())

            loadPdfs()
            updateProgress(null)
        }
    }

    private fun loadPdfs() {
        updatePdfs(pdfRepository.getAll(project))
    }

    private fun createPdf(pdfPath: Path) {
        screenModelScope.launch(Dispatchers.Default) {
            updateProgress(Progress(-1f, getString(Res.string.creating_pdf)))

            val lastPdfId = pdfRepository.lastId()
            val newPdfId = lastPdfId + 1

            val date = Clock.System.now()
            val localDate = date.toLocalDateTime(TimeZone.currentSystemDefault())
            val pdfName = "${project.getName()}_$newPdfId"
            val pdfFileName = "$pdfName.pdf"
            val projectDir = Path(directoryProvider.projectsDir, project.getName())
            SystemFileSystem.createDirectories(projectDir)
            val pdfFile = Path(projectDir, pdfFileName)

            // Move the freshly scanned temp PDF into the project directory
            SystemFileSystem.source(pdfPath).buffered().use { source ->
                SystemFileSystem.sink(pdfFile).buffered().use { sink ->
                    source.transferTo(sink)
                }
            }
            SystemFileSystem.delete(pdfPath, mustExist = false)

            val pdf = Pdf(
                name = pdfFileName,
                size = FileUtils.getFileSize(pdfFile),
                created = localDate.toString(),
                modified = localDate.toString(),
                projectId = project.id
            )

            try {
                pdfRepository.insert(pdf)
            } catch (e: Exception) {
                val error = getString(Res.string.create_pdf_failed)
                logger.error(e) { error }

                updateAlert(
                    Alert(error) { updateAlert(null) }
                )
            }

            updateProgress(Progress(-1f, getString(Res.string.loading_pdfs)))
            loadPdfs()
            updateProgress(null)
        }
    }

    private fun openPdf(pdf: Pdf) {
        screenModelScope.launch {
            val path = Path(
                Path(directoryProvider.projectsDir, project.getName()),
                pdf.name
            )
            _event.send(ProjectEvent.PdfOpened(path))
        }
    }

    @OptIn(ExperimentalEncodingApi::class, ExperimentalUuidApi::class)
    private fun uploadImages(images: List<Image>) {
        screenModelScope.launch {
            if (images.isEmpty()) return@launch

            updateProgress(Progress(0f, getString(Res.string.uploading_images)))

            val total = images.size
            var uploaded = 0

            val model = preferenceRepository.getPref(
                KEY_PREF_DEFAULT_MODEL,
                Model.OPENAI.value
            )
            val processImmediately = preferenceRepository.getPref(
                KEY_PREF_PROCESS_IMMEDIATELY,
                true
            )

            try {
                images.forEachIndexed { index, image ->
                    val path = Path(image.path)
                    SystemFileSystem.source(path).buffered().use { source ->
                        val bytes = source.readByteArray()
                        val base64 = "data:image/jpeg;base64,${Base64.encode(bytes)}"
                        val imageId = Uuid.random().toString()
                        val timestampPattern = "\\d+".toRegex()
                        val created = timestampPattern
                            .find(path.name)?.value?.toLong() ?: Clock.System.now().epochSeconds

                        val imageRequest = ImageRequest(
                            image = base64,
                            imageId = imageId,
                            filename = path.name,
                            languageCode = project.language.slug,
                            bookCode = project.book.slug,
                            chapter = image.chapter,
                            model = model,
                            created = created,
                            processImmediately = processImmediately
                        )
                        val response = transcriberApi.uploadImage(imageRequest)

                        if (response?.success == true) {
                            uploaded++
                            logger.info { "Image uploaded: ${path.name}" }
                        } else {
                            logger.warn { "Image upload failed: ${path.name}" }
                        }
                    }

                    updateProgress(
                        Progress(
                            (index+1).toFloat() / total,
                            getString(Res.string.uploading_images)
                        )
                    )
                }
            } catch (e: Exception) {
                val error = getString(Res.string.upload_images_failed)
                logger.error(e) { error }

                updateAlert(
                    Alert(error) { updateAlert(null) }
                )
            }

            updateProgress(null)

            val message: String
            val url: String?
            if (uploaded == total) {
                message = getString(Res.string.upload_images_success)
                url = TranscriberApi.BASE_URL
            } else {
                message = getString(Res.string.upload_images_failed)
                url = null
            }

            updateUploadStatus(
                UploadStatus(
                    message = message,
                    url = url,
                    onDismiss = { updateUploadStatus(null) }
                )
            )
        }
    }

    private fun deletePdf(pdf: Pdf) {
        screenModelScope.launch {
            updateConfirmAction(
                ConfirmAction(
                    message = getString(Res.string.delete_pdf_confirm),
                    onConfirm = {
                        screenModelScope.launch {
                            updateProgress(Progress(-1f, getString(Res.string.deleting_pdf)))
                            doDeletePdf(pdf)

                            updateProgress(Progress(-1f, getString(Res.string.loading_pdfs)))
                            loadPdfs()

                            updateProgress(null)
                        }
                    },
                    onCancel = {
                        updateConfirmAction(null)
                    }
                )
            )
        }
    }

    private suspend fun doDeletePdf(pdf: Pdf) {
        try {
            val file = Path(directoryProvider.projectsDir, project.getName(), pdf.name)
            SystemFileSystem.delete(file)
            pdfRepository.delete(pdf)
        } catch (e: Exception) {
            val error = getString(Res.string.delete_pdf_failed)
            logger.error(e) { error }

            updateAlert(
                Alert(error) { updateAlert(null) }
            )
        }
    }

    private fun renamePdf(pdf: Pdf, newName: String) {
        screenModelScope.launch(Dispatchers.Default) {
            val newNameNormalized = if (!newName.endsWith(".pdf")) {
                "$newName.pdf"
            } else {
                newName
            }

            if (!pdf.name.equals(newNameNormalized, true)) {
                updateProgress(Progress(-1f, getString(Res.string.renaming_pdf)))

                try {
                    val projectDir = Path(directoryProvider.projectsDir, project.getName())

                    val oldFile = Path(projectDir, pdf.name)
                    val newFile = Path(projectDir, newNameNormalized)
                    FileUtils.renamePath(oldFile, newFile)

                    val now = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())

                    val newPdf = pdf.copy(
                        name = newNameNormalized,
                        modified = now.toString()
                    )
                    updatePdf(newPdf)
                } catch (e: Exception) {
                    val error = getString(Res.string.rename_pdf_failed)
                    logger.error(e) { error }

                    updateAlert(
                        Alert(error) { updateAlert(null) }
                    )
                }

                updateProgress(Progress(-1f, getString(Res.string.loading_pdfs)))
                loadPdfs()
                updateProgress(null)
            }
        }
    }

    private fun extractImages(pdf: Pdf) {
        screenModelScope.launch(Dispatchers.Default) {
            updateProgress(Progress(-1f, getString(Res.string.preparing_images)))

            val projectDir = Path(directoryProvider.projectsDir, project.getName())
            val pdfPath = Path(projectDir, pdf.name)

            val images = renderPdfToImages(pdfPath, directoryProvider)

            updateProgress(null)

            _event.send(ProjectEvent.ImagesExtracted(images))
        }
    }

    /**
     * Transcribes on the device: renders the PDF to page images, cuts each page into text
     * lines, recognizes them, and writes the joined text next to the PDF.
     */
    private fun transcribeLocally(pdf: Pdf) {
        transcribeJob?.cancel()
        transcribeJob = screenModelScope.launch(Dispatchers.Default) {
            val images = mutableListOf<Image>()
            var transcriber: LocalTranscriber? = null

            val script = project.language.script()
            val modelSet = RecognizerSet.forScript(script)
            if (modelSet == null) {
                // The menu entry is hidden for these scripts; this guards against it arriving.
                updateAlert(
                    Alert(getString(Res.string.transcribe_script_unsupported, project.language.name)) {
                        updateAlert(null)
                    }
                )
                return@launch
            }

            try {
                val needed = listOf(DetectorSet, modelSet).filterNot(recognizerModels::isReady)
                if (needed.isNotEmpty()) {
                    updateProgress(Progress(0f, getString(Res.string.downloading_models)))
                    needed.forEach { set ->
                        recognizerModels.download(set) { name, fraction ->
                            updateProgress(
                                Progress(
                                    fraction,
                                    getString(
                                        Res.string.downloading_model,
                                        name,
                                        (fraction.coerceAtLeast(0f) * 100).toInt()
                                    )
                                )
                            )
                        }
                    }
                }

                updateProgress(Progress(-1f, getString(Res.string.preparing_images)))

                val projectDir = Path(directoryProvider.projectsDir, project.getName())
                images.addAll(renderPdfToImages(Path(projectDir, pdf.name), directoryProvider))

                transcriber = createLocalTranscriber(
                    recognizerModels.dir(modelSet),
                    recognizerModels.dir(DetectorSet),
                    modelSet
                )
                val pages = images.mapIndexed { index, image ->
                    transcriber.transcribe(Path(image.path)) { line, lines ->
                        updateProgress(
                            Progress(
                                (index + line.toFloat() / lines) / images.size,
                                getString(
                                    Res.string.transcribing_page,
                                    index + 1,
                                    images.size,
                                    line,
                                    lines
                                )
                            )
                        )
                    }
                }

                // Digits and punctuation alone are not a reading, and would be reported as the
                // wrong script by the check below rather than as nothing legible.
                val text = pages.joinToString("\n\n").trim()
                    .let { if (it.any(Char::isLetter)) it else "" }
                updateProgress(null)

                // A recognizer given the wrong script transliterates instead of failing, so the
                // page is refused rather than handed over as a plausible wrong transcription.
                // The text goes to the log, where diagnosing such a page starts.
                if (text.isNotBlank() && !script.matches(text)) {
                    val percent = (script.shareOf(text) * 100).roundToInt()
                    logger.warn { "Refused transcription: only $percent% $script: $text" }
                    updateAlert(
                        Alert(
                            getString(Res.string.transcribe_wrong_script, script.label, percent)
                        ) { updateAlert(null) }
                    )
                    return@launch
                }

                val textFile = Path(projectDir, pdf.name.removeSuffix(".pdf") + ".txt")
                SystemFileSystem.sink(textFile).buffered().use { it.writeString(text) }

                updateTranscription(
                    TranscriptionResult(
                        title = pdf.name,
                        text = text,
                        savedTo = textFile.name,
                        onDismiss = { updateTranscription(null) }
                    )
                )
            } catch (e: CancellationException) {
                updateProgress(null)
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Local transcription failed" }
                updateProgress(null)

                if (!recognizerModels.isReady(modelSet)) {
                    // The models never made it down; offer another attempt.
                    updateConfirmAction(
                        ConfirmAction(
                            message = getString(Res.string.download_models_failed),
                            onConfirm = {
                                updateConfirmAction(null)
                                transcribeLocally(pdf)
                            },
                            onCancel = { updateConfirmAction(null) }
                        )
                    )
                } else {
                    val error = getString(Res.string.transcribe_failed)
                    updateAlert(Alert(error) { updateAlert(null) })
                }
            } finally {
                transcriber?.close()
                images.forEach {
                    SystemFileSystem.delete(Path(it.path), mustExist = false)
                }
            }
        }
    }

    private fun refreshUser() {
        screenModelScope.launch(Dispatchers.Default) {
            updateUser(transcriberApi.getUser())
        }
    }

    private fun logout() {
        screenModelScope.launch(Dispatchers.Default) {
            updateProgress(Progress(-1f, getString(Res.string.logging_out)))

            _state.value.user?.let {
                transcriberApi.logout()

                updateUser(null)

                updateAlert(
                    Alert(getString(Res.string.logged_out)) {
                        updateAlert(null)
                    }
                )
            }

            updateProgress(null)
        }
    }

    private fun updatePdf(pdf: Pdf) {
        screenModelScope.launch(Dispatchers.Default) {
            pdfRepository.update(pdf)
        }
    }

    private fun updatePdfs(pdfs: List<Pdf>) {
        _state.update {
            it.copy(pdfs = pdfs)
        }
    }

    private fun updateConfirmAction(confirmAction: ConfirmAction?) {
        _state.update {
            it.copy(confirmAction = confirmAction)
        }
    }

    private fun updateProgress(progress: Progress?) {
        _state.update {
            it.copy(progress = progress)
        }
    }

    private fun updateAlert(alert: Alert?) {
        _state.update {
            it.copy(alert = alert)
        }
    }

    private fun updateUser(user: HtrUser?) {
        _state.update {
            it.copy(user = user)
        }
    }

    private fun updateTranscription(transcription: TranscriptionResult?) {
        _state.update {
            it.copy(transcription = transcription)
        }
    }

    private fun updateUploadStatus(status: UploadStatus?) {
        _state.update {
            it.copy(uploadStatus = status)
        }
    }

    private fun resetChannel() {
        screenModelScope.launch {
            _event.send(ProjectEvent.Idle)
        }
    }
}