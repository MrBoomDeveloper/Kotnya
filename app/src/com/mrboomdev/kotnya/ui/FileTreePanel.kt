package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.ProjectInfo
import java.io.File

private data class TreeRow(val node: FileNode, val depth: Int)

/** The collapsible project file tree with create, rename, delete and find actions. */
@Composable
fun FilesPanel(controller: IdeController, project: ProjectInfo, onFileClick: (File) -> Unit) {
    val tree = remember(project.directory, controller.filesRevision) {
        buildFileTree(project.directory)
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    var menuFile by remember { mutableStateOf<File?>(null) }
    var newFileDialog by remember { mutableStateOf(false) }
    var newFilePath by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var searchOpen by remember { mutableStateOf(false) }

    val rows = flattenTree(tree, expanded)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = {
                    newFilePath = ""
                    newFileDialog = true
                },
            ) { Text("New") }
            TextButton(onClick = { searchOpen = true }) { Text("Find") }
            TextButton(onClick = { controller.refreshFiles() }) { Text("Refresh") }
        }

        HorizontalDivider()

        if (rows.isEmpty()) {
            Text(
                "This project has no files yet.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.node.file.absolutePath }) { row ->
                    val file = row.node.file
                    val isDirectory = row.node.isDirectory

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isDirectory) {
                                    val key = file.absolutePath
                                    expanded[key] = expanded[key] != true
                                } else if (isEditableFile(file)) {
                                    onFileClick(file)
                                }
                            }
                            .padding(start = (row.depth * 14).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileTypeBadge(file, if (isDirectory) expanded[file.absolutePath] == true else null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )

                        Box {
                            TextButton(onClick = { menuFile = file }) { Text("⋮") }

                            DropdownMenu(
                                expanded = menuFile == file,
                                onDismissRequest = { menuFile = null },
                            ) {
                                if (isDirectory) {
                                    DropdownMenuItem(
                                        text = { Text("Create file") },
                                        onClick = {
                                            menuFile = null
                                            newFilePath = file
                                                .relativeTo(project.directory)
                                                .path
                                                .trimEnd('/') + "/"
                                            newFileDialog = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        menuFile = null
                                        renameText = file.name
                                        renameTarget = file
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        menuFile = null
                                        deleteTarget = file
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (searchOpen) {
        FileSearchDialog(
            controller = controller,
            project = project,
            onOpen = onFileClick,
            onDismiss = { searchOpen = false },
        )
    }

    if (newFileDialog) {
        AlertDialog(
            onDismissRequest = { newFileDialog = false },
            title = { Text("New file") },
            text = {
                OutlinedTextField(
                    value = newFilePath,
                    onValueChange = { newFilePath = it },
                    label = { Text("Path") },
                    placeholder = { Text("app/src/NewFile.kt") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (controller.createFile(project, newFilePath)) {
                            newFileDialog = false
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { newFileDialog = false }) { Text("Cancel") }
            },
        )
    }

    renameTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (controller.renameFile(file, renameText)) {
                            renameTarget = null
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (file.isDirectory) "Delete directory?" else "Delete file?") },
            text = {
                Text(
                    "Delete '${file.name}'" +
                        if (file.isDirectory) " and everything inside it? This cannot be undone."
                        else "? This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteFile(file)
                        deleteTarget = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private fun flattenTree(
    nodes: List<FileNode>,
    expanded: Map<String, Boolean>,
    depth: Int = 0,
): List<TreeRow> = buildList {
    nodes.forEach { node ->
        add(TreeRow(node, depth))

        if (node.isDirectory && expanded[node.file.absolutePath] == true) {
            addAll(flattenTree(node.children, expanded, depth + 1))
        }
    }
}

/** A compact badge that stands in for an icon, colored and labelled by file type. */
@Composable
private fun FileTypeBadge(file: File, expanded: Boolean?) {
    val (label, color) = when {
        file.isDirectory -> (if (expanded == true) "▾" else "▸") to MaterialTheme.colorScheme.primary
        else -> fileTypeStyle(file)
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun fileTypeStyle(file: File): Pair<String, Color> = when (file.extension.lowercase()) {
    "kt", "kts" -> "K" to Color(0xFF7F52FF)
    "java" -> "J" to Color(0xFFE76F00)
    "xml" -> "<>" to Color(0xFF2E9E5B)
    "yaml", "yml" -> "Y" to Color(0xFFB07AA1)
    "toml", "properties", "cfg", "ini" -> "⚙" to Color(0xFF8A8A8A)
    "md" -> "M" to Color(0xFF42A5F5)
    "json" -> "{}" to Color(0xFFC49A00)
    "sh", "bat" -> "$" to Color(0xFF4CAF50)
    "html", "css", "js", "ts" -> "<>" to Color(0xFF00A6A6)
    "gradle" -> "G" to Color(0xFF6D9E3F)
    "txt", "csv" -> "T" to Color(0xFF7986CB)
    else -> "·" to Color(0xFF9E9E9E)
}
