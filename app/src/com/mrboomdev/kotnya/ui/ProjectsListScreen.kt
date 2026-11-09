package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.ProjectInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsListScreen(controller: IdeController) {
    var menuProject by remember { mutableStateOf<ProjectInfo?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProjectInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                actions = {
                    TextButton(onClick = { controller.refreshProjects() }) { Text("Refresh") }
                    TextButton(onClick = { controller.openSdkDashboard() }) { Text("SDK") }
                    TextButton(onClick = { controller.openSettings() }) { Text("Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { controller.startNewProject() }) {
                Text("New project")
            }
        },
    ) { padding ->
        if (controller.projects.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No projects yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Create a project to start writing Kotlin on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                items(controller.projects, key = { it.directory.absolutePath }) { project ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller.openProject(project) }
                            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    project.directory.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    formatLastOpened(project.lastOpenedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Box {
                                TextButton(onClick = { menuProject = project }) { Text("⋮") }

                                DropdownMenu(
                                    expanded = menuProject == project,
                                    onDismissRequest = { menuProject = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            menuProject = null
                                            renameText = project.name
                                            renameTarget = project
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = {
                                            menuProject = null
                                            deleteTarget = project
                                        },
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                }
            }
        }
    }

    renameTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Project name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (controller.renameProject(project, renameText)) {
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

    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "Delete '${project.name}' and every file in it? This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteProject(project)
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

private fun formatLastOpened(timestamp: Long?): String {
    if (timestamp == null) return "Never opened"

    val formatted = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
    return "Last opened $formatted"
}
