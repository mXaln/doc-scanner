package org.bibletranslationtools.docscanner.ui.theme

enum class ThemeMode(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun of(value: String?) = entries.find { it.value == value } ?: SYSTEM
    }
}
