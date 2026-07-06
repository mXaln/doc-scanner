package org.bibletranslationtools.docscanner.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import docscanner.composeapp.generated.resources.Res
import docscanner.composeapp.generated.resources.ai_settings
import docscanner.composeapp.generated.resources.appearance_section
import docscanner.composeapp.generated.resources.default_model
import docscanner.composeapp.generated.resources.default_model_subtitle
import docscanner.composeapp.generated.resources.download_languages
import docscanner.composeapp.generated.resources.download_languages_subtitle
import docscanner.composeapp.generated.resources.import_languages
import docscanner.composeapp.generated.resources.import_languages_subtitle
import docscanner.composeapp.generated.resources.languages_section
import docscanner.composeapp.generated.resources.process_immediately
import docscanner.composeapp.generated.resources.process_immediately_subtitle
import docscanner.composeapp.generated.resources.settings
import docscanner.composeapp.generated.resources.theme
import docscanner.composeapp.generated.resources.theme_dark
import docscanner.composeapp.generated.resources.theme_light
import docscanner.composeapp.generated.resources.theme_subtitle
import docscanner.composeapp.generated.resources.theme_system
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.launch
import org.bibletranslationtools.docscanner.api.HtrUser
import org.bibletranslationtools.docscanner.api.Model
import org.bibletranslationtools.docscanner.ui.common.AlertDialog
import org.bibletranslationtools.docscanner.ui.common.OptionsDropdown
import org.bibletranslationtools.docscanner.ui.common.PageType
import org.bibletranslationtools.docscanner.ui.common.ProgressDialog
import org.bibletranslationtools.docscanner.ui.common.SettingsClickableItem
import org.bibletranslationtools.docscanner.ui.common.SettingsSection
import org.bibletranslationtools.docscanner.ui.common.SettingsToggleItem
import org.bibletranslationtools.docscanner.ui.common.TopNavigationBar
import org.bibletranslationtools.docscanner.ui.theme.ThemeMode
import org.bibletranslationtools.docscanner.ui.viewmodel.SettingsEvent
import org.bibletranslationtools.docscanner.ui.viewmodel.SettingsViewModel
import org.jetbrains.compose.resources.stringResource

data class SettingsScreen(private val user: HtrUser?) : Screen {
    @Composable
    override fun Content() {
        val viewModel: SettingsViewModel = koinScreenModel()
        val state by viewModel.state.collectAsStateWithLifecycle()

        var showModelPicker by remember { mutableStateOf(false) }
        var showThemePicker by remember { mutableStateOf(false) }

        val uiScope = rememberCoroutineScope()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopNavigationBar(
                    title = stringResource(Res.string.settings),
                    user = user,
                    page = PageType.SETTINGS
                )
            }
        ) { paddingValue ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValue),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SettingsSection(
                    title = stringResource(Res.string.appearance_section),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    val themeLabels = mapOf(
                        ThemeMode.SYSTEM to stringResource(Res.string.theme_system),
                        ThemeMode.LIGHT to stringResource(Res.string.theme_light),
                        ThemeMode.DARK to stringResource(Res.string.theme_dark)
                    )
                    Box {
                        SettingsClickableItem(
                            icon = Icons.Default.Palette,
                            title = stringResource(Res.string.theme),
                            subtitle = stringResource(Res.string.theme_subtitle),
                            actionText = themeLabels.getValue(state.themeMode),
                            onClick = { showThemePicker = true }
                        )
                        OptionsDropdown(
                            expanded = showThemePicker,
                            options = ThemeMode.entries,
                            selected = state.themeMode,
                            onSelect = { viewModel.onEvent(SettingsEvent.SelectTheme(it)) },
                            onDismissRequest = { showThemePicker = false },
                            optionLabel = { themeLabels.getValue(it) }
                        )
                    }
                }

                HorizontalDivider()

                SettingsSection(title = stringResource(Res.string.ai_settings)) {
                    Box {
                        SettingsClickableItem(
                            icon = Icons.Default.SmartToy,
                            title = stringResource(Res.string.default_model),
                            subtitle = stringResource(Res.string.default_model_subtitle),
                            actionText = state.model.value,
                            onClick = { showModelPicker = true }
                        )
                        OptionsDropdown(
                            expanded = showModelPicker,
                            options = Model.entries,
                            selected = state.model,
                            onSelect = { viewModel.onEvent(SettingsEvent.SelectModel(it)) },
                            onDismissRequest = { showModelPicker = false },
                            optionLabel = { it.value }
                        )
                    }
                    SettingsToggleItem(
                        icon = Icons.Default.Bolt,
                        title = stringResource(Res.string.process_immediately),
                        subtitle = stringResource(Res.string.process_immediately_subtitle),
                        checked = state.processImmediately,
                        onCheckedChange = {
                            viewModel.onEvent(SettingsEvent.SetProcessImmediately(it))
                        }
                    )
                }

                HorizontalDivider()

                SettingsSection(title = stringResource(Res.string.languages_section)) {
                    SettingsClickableItem(
                        icon = Icons.Default.Download,
                        title = stringResource(Res.string.download_languages),
                        subtitle = stringResource(Res.string.download_languages_subtitle),
                        onClick = { viewModel.onEvent(SettingsEvent.DownloadLanguages) }
                    )
                    SettingsClickableItem(
                        icon = Icons.Default.UploadFile,
                        title = stringResource(Res.string.import_languages),
                        subtitle = stringResource(Res.string.import_languages_subtitle),
                        onClick = {
                            uiScope.launch {
                                FileKit.openFilePicker(type = FileKitType.File("json"))?.let { file ->
                                    viewModel.onEvent(SettingsEvent.ImportLanguages(file))
                                }
                            }
                        }
                    )
                }
            }

            state.progress?.let {
                ProgressDialog(it)
            }

            state.alert?.let {
                AlertDialog(it.message, it.onClosed)
            }
        }
    }
}
