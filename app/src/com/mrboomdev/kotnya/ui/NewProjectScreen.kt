package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.compiler.ProjectTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectScreen(controller: IdeController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New project") },
                navigationIcon = {
                    TextButton(onClick = { controller.back() }) { Text("Cancel") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = controller.projectName,
                onValueChange = { controller.projectName = it },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = controller.packageName,
                onValueChange = { controller.packageName = it },
                label = { Text("Package name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Template", style = MaterialTheme.typography.titleMedium)

            ProjectTemplate.entries.forEach { template ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller.template = template }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = controller.template == template,
                        onClick = { controller.template = template },
                    )

                    Column(Modifier.padding(start = 8.dp)) {
                        Text(template.title, style = MaterialTheme.typography.bodyLarge)
                        Text(template.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Button(
                onClick = { controller.createProject() },
                enabled = controller.projectName.isNotBlank(),
            ) {
                Text("Create project")
            }
        }
    }
}

private val ProjectTemplate.title: String
    get() = when (this) {
        ProjectTemplate.JvmConsole -> "Console app"
        ProjectTemplate.AndroidApp -> "Android app"
    }

private val ProjectTemplate.description: String
    get() = when (this) {
        ProjectTemplate.JvmConsole -> "Builds and runs on this device with the Kotlin Toolchain."
        ProjectTemplate.AndroidApp -> "Produces an APK. Needs an Android SDK installed in the Termux prefix."
    }
