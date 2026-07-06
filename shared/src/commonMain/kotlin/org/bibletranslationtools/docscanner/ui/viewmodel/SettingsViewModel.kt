package org.bibletranslationtools.docscanner.ui.viewmodel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import docscanner.composeapp.generated.resources.Res
import docscanner.composeapp.generated.resources.languages_update_failed
import docscanner.composeapp.generated.resources.languages_updated
import docscanner.composeapp.generated.resources.updating_languages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.docscanner.api.Model
import org.bibletranslationtools.docscanner.api.UpdateLanguages
import org.bibletranslationtools.docscanner.data.Settings.KEY_PREF_DEFAULT_MODEL
import org.bibletranslationtools.docscanner.data.Settings.KEY_PREF_PROCESS_IMMEDIATELY
import org.bibletranslationtools.docscanner.data.Settings.KEY_PREF_THEME
import org.bibletranslationtools.docscanner.data.models.Alert
import org.bibletranslationtools.docscanner.data.models.Progress
import org.bibletranslationtools.docscanner.data.repository.PreferenceRepository
import org.bibletranslationtools.docscanner.data.repository.getPref
import org.bibletranslationtools.docscanner.data.repository.setPref
import org.bibletranslationtools.docscanner.ui.theme.ThemeMode
import org.jetbrains.compose.resources.getString

data class SettingsState(
    val model: Model = Model.OPENAI,
    val processImmediately: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val progress: Progress? = null,
    val alert: Alert? = null
)

sealed class SettingsEvent {
    data class SelectModel(val model: Model) : SettingsEvent()
    data class SetProcessImmediately(val value: Boolean) : SettingsEvent()
    data class SelectTheme(val mode: ThemeMode) : SettingsEvent()
    data object DownloadLanguages : SettingsEvent()
    data class ImportLanguages(val file: PlatformFile) : SettingsEvent()
}

class SettingsViewModel(
    private val preferenceRepository: PreferenceRepository,
    private val updateLanguages: UpdateLanguages
) : ScreenModel {

    private val logger = KotlinLogging.logger {}

    private var _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state
        .onStart { initialize() }
        .stateIn(
            scope = screenModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsState()
        )

    private fun initialize() {
        val model = preferenceRepository.getPref(
            KEY_PREF_DEFAULT_MODEL,
            Model.OPENAI.value
        ).let { value -> Model.entries.find { it.value == value } ?: Model.OPENAI }

        val processImmediately = preferenceRepository.getPref(
            KEY_PREF_PROCESS_IMMEDIATELY,
            true
        )

        _state.update {
            it.copy(
                model = model,
                processImmediately = processImmediately,
                themeMode = ThemeMode.of(preferenceRepository.getPref<String>(KEY_PREF_THEME))
            )
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SelectModel -> selectModel(event.model)
            is SettingsEvent.SetProcessImmediately -> setProcessImmediately(event.value)
            is SettingsEvent.SelectTheme -> selectTheme(event.mode)
            is SettingsEvent.DownloadLanguages -> runUpdateLanguages { updateLanguages.fromUrl() }
            is SettingsEvent.ImportLanguages -> runUpdateLanguages { updateLanguages.fromFile(event.file) }
        }
    }

    private fun runUpdateLanguages(block: suspend () -> Int) {
        screenModelScope.launch(Dispatchers.Default) {
            updateProgress(Progress(-1f, getString(Res.string.updating_languages)))

            val message = try {
                val added = block()
                getString(Res.string.languages_updated, added)
            } catch (e: Exception) {
                val error = getString(Res.string.languages_update_failed)
                logger.error(e) { error }
                error
            }

            updateProgress(null)
            updateAlert(Alert(message) { updateAlert(null) })
        }
    }

    private fun updateProgress(progress: Progress?) {
        _state.update { it.copy(progress = progress) }
    }

    private fun updateAlert(alert: Alert?) {
        _state.update { it.copy(alert = alert) }
    }

    private fun selectModel(model: Model) {
        screenModelScope.launch(Dispatchers.Default) {
            preferenceRepository.setPref(KEY_PREF_DEFAULT_MODEL, model.value)
            _state.update { it.copy(model = model) }
        }
    }

    private fun selectTheme(mode: ThemeMode) {
        screenModelScope.launch(Dispatchers.Default) {
            preferenceRepository.setPref(KEY_PREF_THEME, mode.value)
            _state.update { it.copy(themeMode = mode) }
        }
    }

    private fun setProcessImmediately(value: Boolean) {
        screenModelScope.launch(Dispatchers.Default) {
            preferenceRepository.setPref(KEY_PREF_PROCESS_IMMEDIATELY, value)
            _state.update { it.copy(processImmediately = value) }
        }
    }
}
