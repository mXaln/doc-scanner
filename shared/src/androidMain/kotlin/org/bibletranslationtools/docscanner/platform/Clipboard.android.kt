package org.bibletranslationtools.docscanner.platform

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.toClipEntry

actual fun clipEntryOf(text: String): ClipEntry =
    ClipData.newPlainText(null, text).toClipEntry()
