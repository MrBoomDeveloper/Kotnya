package com.mrboomdev.kotnya

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mrboomdev.kotnya.ui.AndroidSdkDashboardScreen
import com.mrboomdev.kotnya.ui.EditorScreen
import com.mrboomdev.kotnya.ui.NewProjectScreen
import com.mrboomdev.kotnya.ui.OnboardingScreen
import com.mrboomdev.kotnya.ui.ProjectsListScreen
import com.mrboomdev.kotnya.ui.SettingsScreen

@Composable
fun KotnyaApp(controller: IdeController) {
    when {
        // Until the on-device environment is checked, show nothing that could start a build.
        !controller.setupChecked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        // The IDE needs a Termux bootstrap, a Java runtime and a Kotlin Toolchain. The Android SDK is
        // optional and is browsed from its own dashboard, which the setup screens can open.
        !controller.environmentReady || !controller.onboardingDismissed -> {
            BackHandler(enabled = controller.screen == Screen.SdkDashboard) { controller.back() }

            if (controller.screen == Screen.SdkDashboard) AndroidSdkDashboardScreen(controller)
            else OnboardingScreen(controller)
        }

        else -> {
            BackHandler(enabled = controller.screen !is Screen.Projects) {
                controller.back()
            }

            when (val screen = controller.screen) {
                is Screen.Projects -> ProjectsListScreen(controller)
                is Screen.NewProject -> NewProjectScreen(controller)
                is Screen.Settings -> SettingsScreen(controller)
                is Screen.SdkDashboard -> AndroidSdkDashboardScreen(controller)
                is Screen.Editor -> EditorScreen(controller, screen.project)
            }
        }
    }

    controller.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.clearError() },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { controller.clearError() }) { Text("OK") }
            },
        )
    }
}
