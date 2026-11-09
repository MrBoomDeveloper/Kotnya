package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.OnboardingStep

/**
 * The first-run setup, split into one screen per component.
 *
 * A Termux bootstrap, a Java runtime and a Kotlin Toolchain are required; the Android SDK is
 * optional and is reached through its own dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(controller: IdeController) {
    val step = controller.currentOnboardingStep

    LaunchedEffect(Unit) { controller.loadCatalog() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(step.title) },
                actions = {
                    if (controller.environmentReady) {
                        TextButton(onClick = { controller.dismissOnboarding() }) { Text("Skip") }
                    }
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
            StepIndicator(step)

            controller.busyMessage?.let { message ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message, style = MaterialTheme.typography.bodySmall)
            }

            when (step) {
                OnboardingStep.Welcome -> WelcomeStep()
                OnboardingStep.Termux -> TermuxStep(controller)
                OnboardingStep.Java -> JavaStep(controller)
                OnboardingStep.Toolchain -> ToolchainStep(controller)
                OnboardingStep.Ready -> ReadyStep(controller)
            }

            Spacer(Modifier.height(8.dp))
            NavigationRow(controller, step)
        }
    }
}

@Composable
private fun StepIndicator(step: OnboardingStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OnboardingStep.entries.forEach { entry ->
            val reached = entry.ordinal <= step.ordinal

            Box(
                Modifier
                    .size(if (entry == step) 10.dp else 8.dp)
                    .background(
                        color = if (reached) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    )
            )
        }

        Text(
            "Step ${step.ordinal + 1} of ${OnboardingStep.entries.size}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun WelcomeStep() {
    Text("Kotnya", style = MaterialTheme.typography.headlineSmall)
    Text(
        "A Kotlin IDE that runs entirely on this device. Before the first project, Kotnya installs a " +
            "small Linux user-land and the tools that compile your code:",
        style = MaterialTheme.typography.bodyMedium,
    )

    Requirement("Termux bootstrap", "The Linux user-land with bash, tar and the pkg package manager.")
    Requirement("Java runtime", "Runs the Kotlin Toolchain and sdkmanager.")
    Requirement("Kotlin Toolchain", "Compiles and runs your projects.")

    Text(
        "The Android SDK is optional. You can browse Android versions, build-tools and their device " +
            "usage from the SDK dashboard whenever an Android project needs them.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Requirement(title: String, description: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TermuxStep(controller: IdeController) {
    Text(
        "The bootstrap unpacks a Termux user-land into the app's private storage. Every shell command, " +
            "package and build runs inside it.",
        style = MaterialTheme.typography.bodyMedium,
    )

    if (controller.bootstrapInstalled) {
        Text(
            "Installed: ${controller.installedBootstrapVersion ?: "unknown version"}",
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }

    Text(
        "Device ABI: ${controller.deviceAbi}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )

    when {
        controller.catalogLoading && controller.bootstrapReleases.isEmpty() -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Fetching the available bootstrap versions…",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        controller.bootstrapReleases.isEmpty() -> {
            Text(
                controller.catalogError ?: "Could not reach the Termux download servers.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(onClick = { controller.loadCatalog(force = true) }) {
                Text("Retry")
            }
        }

        else -> {
            val recommended = controller.recommendedBootstrap

            Text(
                buildString {
                    append("Recommended for this device: ")
                    append(recommended?.version ?: "unknown")
                    recommended?.let { append(" (${it.abi})") }
                },
                style = MaterialTheme.typography.bodyLarge,
            )

            Button(
                onClick = { controller.installBootstrap() },
                enabled = recommended != null && controller.busyMessage == null,
            ) {
                Text("Install Termux bootstrap")
            }

            TextButton(onClick = { controller.openSdkDashboard() }) {
                Text("Browse all bootstrap versions")
            }
        }
    }
}

@Composable
private fun JavaStep(controller: IdeController) {
    Text(
        "The Kotlin Toolchain and sdkmanager both run on a Java runtime. Kotnya installs it as the " +
            "openjdk-25 Termux package.",
        style = MaterialTheme.typography.bodyMedium,
    )

    if (controller.javaRuntimeAvailable) {
        Text("Installed", style = MaterialTheme.typography.bodyLarge)
        return
    }

    if (!controller.bootstrapInstalled) {
        Text(
            "Install the Termux bootstrap first.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Button(
        onClick = { controller.installJavaRuntime() },
        enabled = controller.bootstrapInstalled && controller.busyMessage == null,
    ) {
        Text("Install Java runtime")
    }
}

@Composable
private fun ToolchainStep(controller: IdeController) {
    Text(
        "The Kotlin Toolchain builds and runs your projects. It is downloaded once and shared by every " +
            "project on this device.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = controller.toolchainVersion,
        onValueChange = { controller.toolchainVersion = it },
        label = { Text("Version") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = { controller.installToolchain() },
        enabled = controller.javaRuntimeAvailable && controller.busyMessage == null,
    ) {
        Text("Install Kotlin Toolchain ${controller.toolchainVersion}")
    }

    if (controller.installedToolchains.isNotEmpty()) {
        Text(
            "Installed: " + controller.installedToolchains.joinToString { it.version },
            style = MaterialTheme.typography.bodySmall,
        )

        controller.installedToolchains.forEach { toolchain ->
            TextButton(onClick = { controller.toolchainVersion = toolchain.version }) {
                Text("Use ${toolchain.version}")
            }
        }
    }
}

@Composable
private fun ReadyStep(controller: IdeController) {
    Text("Everything required is installed", style = MaterialTheme.typography.headlineSmall)
    Text(
        "You can create projects and build them on this device.",
        style = MaterialTheme.typography.bodyMedium,
    )

    StatusLine("Termux bootstrap", controller.bootstrapInstalled)
    StatusLine("Java runtime", controller.javaRuntimeAvailable)
    StatusLine("Kotlin Toolchain", controller.installedToolchains.isNotEmpty())

    HorizontalDivider()

    StatusLine("Android SDK (optional)", controller.androidSdk?.isInstalled == true)

    Button(
        onClick = { controller.dismissOnboarding() },
        enabled = controller.environmentReady,
    ) {
        Text("Start coding")
    }

    TextButton(onClick = { controller.openSdkDashboard() }) {
        Text("Browse the Android SDK dashboard")
    }
}

@Composable
private fun StatusLine(title: String, done: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (done) "Done" else "Missing",
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun NavigationRow(controller: IdeController, step: OnboardingStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step != OnboardingStep.Welcome) {
            TextButton(onClick = { controller.previousOnboardingStep() }) { Text("Back") }
        }

        Spacer(Modifier.weight(1f))

        if (step != OnboardingStep.Ready) {
            Button(
                onClick = { controller.nextOnboardingStep() },
                enabled = step.isSatisfied(controller) && controller.busyMessage == null,
            ) {
                Text("Continue")
            }
        }
    }
}

/** A step may only be left once its component is installed, because the IDE needs all three. */
private fun OnboardingStep.isSatisfied(controller: IdeController): Boolean = when (this) {
    OnboardingStep.Welcome -> true
    OnboardingStep.Termux -> controller.bootstrapInstalled
    OnboardingStep.Java -> controller.javaRuntimeAvailable
    OnboardingStep.Toolchain -> controller.installedToolchains.isNotEmpty()
    OnboardingStep.Ready -> controller.environmentReady
}

private val OnboardingStep.title: String
    get() = when (this) {
        OnboardingStep.Welcome -> "Welcome"
        OnboardingStep.Termux -> "Termux bootstrap"
        OnboardingStep.Java -> "Java runtime"
        OnboardingStep.Toolchain -> "Kotlin toolchain"
        OnboardingStep.Ready -> "Ready to code"
    }
