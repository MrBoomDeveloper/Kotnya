package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mrboomdev.kotnya.IdeController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(controller: IdeController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = { controller.back() }) { Text("Back") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Android SDK", style = MaterialTheme.typography.titleMedium)
            Text(
                "Browse Android platform, build-tools and command-line tools versions with their device " +
                    "usage, and install the ones a project needs.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(onClick = { controller.openSdkDashboard() }) {
                Text("Open SDK dashboard")
            }

            HorizontalDivider()

            Text("Editor", style = MaterialTheme.typography.titleMedium)
            Text(
                "Font size: ${controller.editorFontSize.toInt()} sp",
                style = MaterialTheme.typography.bodyMedium,
            )

            Slider(
                value = controller.editorFontSize,
                onValueChange = { controller.editorFontSize = it },
                valueRange = 10f..24f,
                steps = 13,
            )
        }
    }
}
