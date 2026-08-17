package org.bibletranslationtools.docscanner.ui.screens.project

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import docscanner.composeapp.generated.resources.Res
import docscanner.composeapp.generated.resources.document_scanner
import docscanner.composeapp.generated.resources.login
import docscanner.composeapp.generated.resources.login_needed
import docscanner.composeapp.generated.resources.logout
import docscanner.composeapp.generated.resources.no_scan_found
import docscanner.composeapp.generated.resources.scan
import docscanner.composeapp.generated.resources.scanner_not_available
import kotlinx.coroutines.launch
import org.bibletranslationtools.docscanner.api.HtrUser
import org.bibletranslationtools.docscanner.data.models.Image
import org.bibletranslationtools.docscanner.data.models.Pdf
import org.bibletranslationtools.docscanner.data.models.Project
import org.bibletranslationtools.docscanner.data.models.getTitle
import org.bibletranslationtools.docscanner.data.repository.DirectoryProvider
import org.bibletranslationtools.docscanner.ocr.RecognizerSet
import org.bibletranslationtools.docscanner.ocr.isLocalTranscriptionAvailable
import org.bibletranslationtools.docscanner.ocr.script
import org.bibletranslationtools.docscanner.platform.isDocumentScannerAvailable
import org.bibletranslationtools.docscanner.platform.rememberDocumentScannerLauncher
import org.bibletranslationtools.docscanner.platform.rememberFileSharer
import org.bibletranslationtools.docscanner.ui.common.AlertDialog
import org.bibletranslationtools.docscanner.ui.common.ConfirmDialog
import org.bibletranslationtools.docscanner.ui.common.ErrorScreen
import org.bibletranslationtools.docscanner.ui.common.ExtraAction
import org.bibletranslationtools.docscanner.ui.common.PageType
import org.bibletranslationtools.docscanner.ui.common.ProgressDialog
import org.bibletranslationtools.docscanner.ui.common.TopNavigationBar
import org.bibletranslationtools.docscanner.ui.screens.login.LoginScreen
import org.bibletranslationtools.docscanner.ui.screens.project.components.PdfLayout
import org.bibletranslationtools.docscanner.ui.screens.project.components.PdfRenameDialog
import org.bibletranslationtools.docscanner.ui.screens.project.components.TranscriptionDialog
import org.bibletranslationtools.docscanner.ui.screens.project.components.UploadCompleteDialog
import org.bibletranslationtools.docscanner.ui.screens.project.components.UploadImagesDialog
import org.bibletranslationtools.docscanner.ui.viewmodel.ProjectEvent
import org.bibletranslationtools.docscanner.ui.viewmodel.ProjectViewModel
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

data class ProjectScreen(
    private val project: Project,
    private val user: HtrUser?
) : Screen {
    @Composable
    override fun Content() {
        val viewModel = koinScreenModel<ProjectViewModel> {
            parametersOf(project)
        }
        val navigator = LocalNavigator.currentOrThrow

        var renamePdf by remember { mutableStateOf<Pdf?>(null) }
        var extractedImages by remember { mutableStateOf<List<Image>>(emptyList()) }

        LaunchedEffect(Unit) {
            viewModel.onEvent(ProjectEvent.RefreshUser)
        }

        val uiScope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val directoryProvider = koinInject<DirectoryProvider>()
        val fileSharer = rememberFileSharer()

        val state by viewModel.state.collectAsStateWithLifecycle()
        val event by viewModel.event.collectAsStateWithLifecycle(ProjectEvent.Idle)
        var expandedItemId by remember { mutableStateOf<Long?>(null) }

        LaunchedEffect(event) {
            when (event) {
                is ProjectEvent.PdfOpened -> {
                    viewModel.onEvent(ProjectEvent.Idle)
                    fileSharer.open((event as ProjectEvent.PdfOpened).path)
                }
                is ProjectEvent.ImagesExtracted -> {
                    extractedImages = (event as ProjectEvent.ImagesExtracted).images
                }
                else -> Unit
            }
        }

        // Recognition models are per writing system, so the action only appears when this
        // project's language has one.
        val localTranscriptionSupported = remember(project.language.slug) {
            isLocalTranscriptionAvailable() &&
                RecognizerSet.forScript(project.language.script()) != null
        }

        val scannerLauncher = rememberDocumentScannerLauncher(directoryProvider) { pdfPath ->
            pdfPath?.let { viewModel.onEvent(ProjectEvent.CreatePdf(it)) }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                val extraActions = mutableListOf<ExtraAction>()
                extraActions.add(
                    if (state.user != null) {
                        ExtraAction(
                            title = stringResource(Res.string.logout),
                            icon = Icons.AutoMirrored.Filled.Logout,
                            onClick = { viewModel.onEvent(ProjectEvent.Logout) }
                        )
                    } else {
                        ExtraAction(
                            title = stringResource(Res.string.login),
                            icon = Icons.AutoMirrored.Filled.Login,
                            onClick = { navigator.push(LoginScreen()) }
                        )
                    }
                )

                TopNavigationBar(
                    title = project.getTitle(),
                    user = state.user,
                    page = PageType.PROJECT,
                    extraAction = extraActions.toTypedArray()
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    modifier = Modifier.offset(0.dp, 0.dp),
                    onClick = {
                        if (isDocumentScannerAvailable()) {
                            scannerLauncher.launch()
                        } else {
                            uiScope.launch {
                                snackbarHostState.showSnackbar(
                                    getString(Res.string.scanner_not_available)
                                )
                            }
                        }
                    },
                    text = {
                        Text(
                            text = stringResource(Res.string.scan),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.document_scanner),
                            contentDescription = "Scan"
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                )
            }
        ) { paddingValue ->

            if (state.pdfs.isEmpty()) {
                ErrorScreen(message = stringResource(Res.string.no_scan_found))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValue)
                ) {
                        items(items = state.pdfs, key = { it.id }) { pdf ->
                            PdfLayout(
                                pdf = pdf,
                                menuShown = expandedItemId == pdf.id,
                                transcribeLocallyShown = localTranscriptionSupported,
                                onCardClick = {
                                    viewModel.onEvent(ProjectEvent.OpenPdf(pdf))
                                },
                                onMoreClick = {
                                    expandedItemId = if (expandedItemId != pdf.id) {
                                        pdf.id
                                    } else null
                                },
                                onRenameClick = {
                                    renamePdf = pdf
                                },
                                onUploadClick = {
                                    if (state.user != null) {
                                        viewModel.onEvent(ProjectEvent.ExtractImages(pdf))
                                    } else {
                                        uiScope.launch {
                                            snackbarHostState.showSnackbar(
                                                getString(Res.string.login_needed)
                                            )
                                        }
                                    }
                                },
                                onTranscribeLocallyClick = {
                                    viewModel.onEvent(ProjectEvent.TranscribeLocally(pdf))
                                },
                                onDeleteClick = {
                                    viewModel.onEvent(ProjectEvent.DeletePdf(pdf))
                                },
                                onDismissRequest = { expandedItemId = null }
                            )
                        }
                    }
                }

            state.progress?.let {
                ProgressDialog(it)
            }

            state.alert?.let {
                AlertDialog(it.message, it.onClosed)
            }

            state.confirmAction?.let {
                ConfirmDialog(
                    message = it.message,
                    onConfirm = it.onConfirm,
                    onCancel = it.onCancel,
                    onDismiss = it.onCancel
                )
            }

            renamePdf?.let { pdf ->
                PdfRenameDialog(
                    name = pdf.name,
                    onRename = { viewModel.onEvent(ProjectEvent.RenamePdf(pdf, it)) },
                    onDismissRequest = { renamePdf = null }
                )
            }

            if (extractedImages.isNotEmpty()) {
                UploadImagesDialog(
                    images = extractedImages,
                    onUpload = { viewModel.onEvent(ProjectEvent.UploadImages(it)) },
                    onDismissRequest = { extractedImages = emptyList() }
                )
            }

            state.uploadStatus?.let {
                UploadCompleteDialog(it)
            }

            state.transcription?.let {
                TranscriptionDialog(it)
            }
        }
    }
}
