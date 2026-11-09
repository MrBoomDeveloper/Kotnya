package com.mrboomdev.kotnya

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.mrboomdev.kotnya.ui.AndroidSdkDashboardScreen
import com.mrboomdev.kotnya.ui.EditorScreen
import com.mrboomdev.kotnya.ui.NewProjectScreen
import com.mrboomdev.kotnya.ui.OnboardingScreen
import com.mrboomdev.kotnya.ui.ProjectsListScreen
import com.mrboomdev.kotnya.ui.SettingsScreen

@Composable
fun KotnyaApp(controller: IdeController) {
    val activity = LocalContext.current as? Activity

    when {
        // Until the on-device environment is checked, show nothing that could start a build.
        !controller.setupChecked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        // The IDE needs a Termux bootstrap, a Java runtime and a Kotlin Toolchain. The Android SDK is
        // optional and is browsed from its own dashboard, which the setup screens can open.
        !controller.environmentReady || !controller.onboardingDismissed -> {
            val showingSdk = controller.currentRoute is SdkDashboardRoute
            BackHandler(enabled = showingSdk) { controller.back() }

            if (showingSdk) AndroidSdkDashboardScreen(controller)
            else OnboardingScreen(controller)
        }

        else -> NavDisplay(
            backStack = controller.backStack,
            onBack = { if (!controller.back()) activity?.finish() },
            transitionSpec = {
                (slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut())
            },
            popTransitionSpec = {
                (slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut())
            },
            predictivePopTransitionSpec = {
                (slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn()) togetherWith
                    (slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut())
            },
            entryProvider = entryProvider<NavKey> {
                entry<ProjectsRoute> { ProjectsListScreen(controller) }

                entry<NewProjectRoute> { NewProjectScreen(controller) }

                entry<SettingsRoute> { SettingsScreen(controller) }

                entry<SdkDashboardRoute> { AndroidSdkDashboardScreen(controller) }

                entry<EditorRoute> { route ->
                    val project = controller.projectFor(route)
                    if (project != null) EditorScreen(controller, project)
                    else ProjectsListScreen(controller)
                }
            },
        )
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
