package org.bibletranslationtools.docscanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import kotlinx.coroutines.flow.map
import org.bibletranslationtools.docscanner.data.Settings.KEY_PREF_THEME
import org.bibletranslationtools.docscanner.data.repository.PreferenceRepository
import org.bibletranslationtools.docscanner.data.repository.getPref
import org.bibletranslationtools.docscanner.ui.screens.splash.SplashScreen
import org.bibletranslationtools.docscanner.ui.theme.DocScannerTheme
import org.bibletranslationtools.docscanner.ui.theme.ThemeMode
import org.koin.compose.koinInject

@Composable
fun App() {
    val preferenceRepository = koinInject<PreferenceRepository>()
    val themeMode by remember {
        preferenceRepository
            .getStringFlow(KEY_PREF_THEME, ThemeMode.SYSTEM.value)
            .map(ThemeMode::of)
    }.collectAsStateWithLifecycle(
        ThemeMode.of(preferenceRepository.getPref<String>(KEY_PREF_THEME))
    )

    DocScannerTheme(themeMode = themeMode) {
        Navigator(SplashScreen()) { navigator ->
            SlideTransition(navigator)
        }
    }
}