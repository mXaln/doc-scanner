package org.bibletranslationtools.docscanner.platform

import androidx.compose.ui.platform.ClipEntry

/**
 * A clipboard entry holding [text].
 *
 * [ClipEntry] has no common constructor — on Android it wraps a `ClipData` — so building one
 * has to happen per platform.
 */
expect fun clipEntryOf(text: String): ClipEntry
