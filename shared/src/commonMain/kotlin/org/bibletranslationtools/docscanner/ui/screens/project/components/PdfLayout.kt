package org.bibletranslationtools.docscanner.ui.screens.project.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import docscanner.composeapp.generated.resources.Res
import docscanner.composeapp.generated.resources.delete_pdf
import docscanner.composeapp.generated.resources.rename_pdf
import docscanner.composeapp.generated.resources.transcribe_locally
import docscanner.composeapp.generated.resources.upload_images
import org.bibletranslationtools.docscanner.data.models.Pdf
import org.bibletranslationtools.docscanner.utils.format
import org.bibletranslationtools.docscanner.utils.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

@Composable
fun PdfLayout(
    pdf: Pdf,
    menuShown: Boolean,
    transcribeLocallyShown: Boolean,
    onCardClick: () -> Unit,
    onMoreClick: () -> Unit,
    onRenameClick: () -> Unit,
    onUploadClick: () -> Unit,
    onTranscribeLocallyClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDismissRequest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(12.dp),
        onClick = onCardClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Icon(
                modifier = Modifier.size(64.dp),
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pdf.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(7.7.dp))

                Text(
                    text = "Date: ${pdf.modified.toLocalDateTime().format()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Size: ${pdf.size}", style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                IconButton(onClick = onMoreClick) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "more")
                }

                DropdownMenu(
                    expanded = menuShown,
                    onDismissRequest = onDismissRequest
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.rename_pdf)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DriveFileRenameOutline,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onDismissRequest()
                            onRenameClick()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.upload_images)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onDismissRequest()
                            onUploadClick()
                        }
                    )

                    if (transcribeLocallyShown) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.transcribe_locally)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.TextFields,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onDismissRequest()
                                onTranscribeLocallyClick()
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.delete_pdf)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color.Red
                            )
                        },
                        onClick = {
                            onDismissRequest()
                            onDeleteClick()
                        }
                    )
                }
            }
        }
    }
}
