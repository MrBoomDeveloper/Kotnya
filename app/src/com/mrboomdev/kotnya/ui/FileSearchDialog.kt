package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.ProjectInfo
import java.io.File

private enum class SearchTab(val title: String) {
    Name("File name"),
    Content("File content"),
}

/** Finds files by name or by the text inside them, in two separate tabs. */
@Composable
fun FileSearchDialog(
    controller: IdeController,
    project: ProjectInfo,
    onOpen: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(SearchTab.Name) }
    var query by remember { mutableStateOf("") }

    val nameMatches = remember(tab, query, controller.filesRevision) {
        if (tab == SearchTab.Name) searchFileNames(project.directory, query) else emptyList()
    }
    val contentMatches = remember(tab, query, controller.filesRevision) {
        if (tab == SearchTab.Content) searchFileContents(project.directory, query) else emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find files") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab.ordinal) {
                    SearchTab.entries.forEach { searchTab ->
                        Tab(
                            selected = tab == searchTab,
                            onClick = { tab = searchTab },
                            text = { Text(searchTab.title) },
                        )
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(if (tab == SearchTab.Name) "File name" else "Text to find") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )

                HorizontalDivider(Modifier.padding(top = 8.dp))

                when {
                    query.isBlank() -> Text(
                        "Type to search the project.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )

                    tab == SearchTab.Name && nameMatches.isEmpty() -> Text(
                        "No files match '$query'.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )

                    tab == SearchTab.Content && contentMatches.isEmpty() -> Text(
                        "No file contains '$query'.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )

                    tab == SearchTab.Name -> LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(nameMatches, key = { it.absolutePath }) { file ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpen(file)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    file.relativeTo(project.directory).path,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }

                    else -> LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(contentMatches, key = { "${it.file.absolutePath}:${it.lineNumber}" }) { match ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpen(match.file)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "${match.file.relativeTo(project.directory).path}:${match.lineNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    match.line.ifBlank { "(blank line)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
