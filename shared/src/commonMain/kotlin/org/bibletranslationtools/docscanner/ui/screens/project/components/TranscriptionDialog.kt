package org.bibletranslationtools.docscanner.ui.screens.project.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import docscanner.composeapp.generated.resources.Res
import docscanner.composeapp.generated.resources.copy
import docscanner.composeapp.generated.resources.ok
import docscanner.composeapp.generated.resources.transcription_empty
import docscanner.composeapp.generated.resources.transcription_saved
import kotlinx.coroutines.launch
import org.bibletranslationtools.docscanner.platform.clipEntryOf
import org.jetbrains.compose.resources.stringResource

data class TranscriptionResult(
    val title: String,
    val text: String,
    /** Where the text was written, shown so the user can find the file. */
    val savedTo: String?,
    val onDismiss: () -> Unit
)

@Composable
fun TranscriptionDialog(result: TranscriptionResult) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = result.onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = result.text.ifBlank { stringResource(Res.string.transcription_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )

                result.savedTo?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(Res.string.transcription_saved, it),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (result.text.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(clipEntryOf(result.text))
                                }
                            }
                        ) {
                            Text(stringResource(Res.string.copy))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(onClick = result.onDismiss) {
                        Text(stringResource(Res.string.ok))
                    }
                }
            }
        }
    }
}
