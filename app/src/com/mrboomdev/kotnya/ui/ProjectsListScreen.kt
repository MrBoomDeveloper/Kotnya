package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrboomdev.kotnya.IdeController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsListScreen(controller: IdeController) {
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
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            project.directory.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    HorizontalDivider()
                }
            }
        }
    }
}
