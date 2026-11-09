package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.compiler.AndroidDistribution
import com.mrboomdev.kotnya.compiler.SdkPackage
import com.mrboomdev.kotnya.compiler.TermuxBootstrapRelease

/** One tab per kind of thing the dashboard can install. */
private enum class DashboardTab(val title: String) {
    Platforms("Platforms"),
    BuildTools("Build tools"),
    CommandLineTools("Command-line tools"),
    Termux("Termux bootstrap"),
}

/**
 * Browses every version Kotnya can install, so nothing has to be typed in by hand. Each SDK type has
 * its own tab: Android platforms with their Google Play device share, build-tools, the command-line
 * tools and Termux bootstraps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidSdkDashboardScreen(controller: IdeController) {
    LaunchedEffect(Unit) { controller.loadCatalog() }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tab = DashboardTab.entries[selectedTab.coerceIn(0, DashboardTab.entries.lastIndex)]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Android SDK") },
                navigationIcon = {
                    TextButton(onClick = { controller.back() }) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = { controller.loadCatalog(force = true) }) { Text("Refresh") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusBlock(controller)

                controller.busyMessage?.let { message ->
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }

                if (controller.catalogLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                controller.catalogError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            PrimaryScrollableTabRow(
                selectedTabIndex = tab.ordinal,
                edgePadding = 16.dp,
            ) {
                DashboardTab.entries.forEach { entry ->
                    Tab(
                        selected = entry == tab,
                        onClick = { selectedTab = entry.ordinal },
                        text = { Text(entry.title) },
                    )
                }
            }

            val packages = controller.sdkPackages
            val bootstraps = controller.bootstrapReleases
                .filter { it.abi == controller.deviceAbi }
                .ifEmpty { controller.bootstrapReleases }

            // Each tab keeps its own list state, so switching tabs starts at the top instead of
            // inheriting the scroll offset of the previous list.
            key(tab) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (tab) {
                    DashboardTab.Platforms -> {
                        item {
                            TabNote(
                                "Device share of Google Play, ${AndroidDistribution.capturedOn}.",
                            )
                        }

                        val platforms = packages?.platforms.orEmpty()
                        if (platforms.isEmpty()) item { EmptyCatalogNote(controller) }

                        items(platforms, key = { it.path }) { platform ->
                            PlatformItem(controller, platform)
                        }
                    }

                    DashboardTab.BuildTools -> {
                        item {
                            TabNote("One version is required to build Android projects.")
                        }

                        val buildTools = packages?.buildTools.orEmpty()
                        if (buildTools.isEmpty()) item { EmptyCatalogNote(controller) }

                        items(buildTools, key = { it.path }) { release ->
                            SdkPackageItem(
                                title = release.version,
                                subtitle = if (release.isStable) "Stable" else "Preview",
                                installed = controller.androidSdk?.buildTools?.contains(release.version) == true,
                                enabled = controller.canInstallSdkPackages,
                                onInstall = {
                                    controller.installSdkPackage(
                                        release.path,
                                        "Build tools ${release.version}",
                                    )
                                },
                            )
                        }
                    }

                    DashboardTab.CommandLineTools -> {
                        item {
                            TabNote(
                                "sdkmanager itself. Install this before any other Android package; " +
                                    "it is downloaded and unpacked directly.",
                            )
                        }

                        val commandLineTools = packages?.commandLineTools.orEmpty()
                        if (commandLineTools.isEmpty()) item { EmptyCatalogNote(controller) }

                        items(commandLineTools, key = { it.path }) { release ->
                            SdkPackageItem(
                                title = release.name,
                                subtitle = release.sizeLabel ?: "Download",
                                installed = release.version == "latest" &&
                                    controller.androidSdk?.commandLineTools != null,
                                enabled = controller.canInstallCommandLineTools,
                                onInstall = { controller.installCommandLineTools(release) },
                            )
                        }
                    }

                    DashboardTab.Termux -> {
                        item {
                            TabNote(
                                "Archives for ${controller.deviceAbi}. Updating requires uninstalling " +
                                    "the current bootstrap first.",
                            )
                        }

                        if (bootstraps.isEmpty()) item { EmptyCatalogNote(controller) }

                        items(bootstraps, key = { it.downloadUrl }) { release ->
                            BootstrapItem(controller, release)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun StatusBlock(controller: IdeController) {
    val sdk = controller.androidSdk

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "SDK location: ${sdk?.directory?.absolutePath ?: "-"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Installed platforms: " + (sdk?.platforms?.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Installed build tools: " + (sdk?.buildTools?.takeIf { it.isNotEmpty() }?.joinToString() ?: "none"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (sdk?.isInstalled == true) "Android SDK ready"
            else "Android SDK incomplete: one platform and one build-tools version are required.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TabNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EmptyCatalogNote(controller: IdeController) {
    if (controller.catalogLoading) return

    Text(
        "Could not load this list. Tap Refresh to retry.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun PlatformItem(controller: IdeController, platform: SdkPackage) {
    val share = platform.apiLevel?.let(AndroidDistribution::shareOf)
    val versionName = platform.apiLevel?.let(AndroidDistribution::nameOf)
    val installed = controller.androidSdk?.platforms?.contains(platform.version) == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    versionName ?: platform.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    buildString {
                        append("API ${platform.apiLevel ?: "?"}")
                        if (!platform.isStable) append(" · preview")
                        append(" · ")
                        append(platform.version)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Text(
                share?.let { "%.1f%%".format(it) } ?: "—",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        share?.let { percent ->
            LinearProgressIndicator(
                progress = { (percent / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (installed) {
            Text(
                "Installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(
                onClick = { controller.installSdkPackage(platform.path, "Android platform ${platform.version}") },
                enabled = controller.canInstallSdkPackages,
            ) {
                Text("Install")
            }
        }
    }
}

@Composable
private fun SdkPackageItem(
    title: String,
    subtitle: String,
    installed: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }

        if (installed) {
            Text(
                "Installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(
                onClick = onInstall,
                enabled = enabled,
            ) {
                Text("Install")
            }
        }
    }
}

@Composable
private fun BootstrapItem(controller: IdeController, release: TermuxBootstrapRelease) {
    val installed = controller.bootstrapInstalled &&
        controller.installedBootstrapVersion == release.version
    val recommended = controller.recommendedBootstrap?.downloadUrl == release.downloadUrl

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                release.version + if (recommended) " · recommended" else "",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                listOfNotNull(release.abi, release.publishedAt?.take(10), release.sizeLabel)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        when {
            installed -> Text(
                "Installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            controller.bootstrapInstalled -> Text(
                "Uninstall first",
                style = MaterialTheme.typography.bodySmall,
            )

            else -> TextButton(
                onClick = { controller.installBootstrap(release) },
                enabled = controller.busyMessage == null,
            ) {
                Text("Install")
            }
        }
    }
}

private val SdkPackage.sizeLabel: String?
    get() = sizeBytes?.let(::formatMegabytes)

private val TermuxBootstrapRelease.sizeLabel: String?
    get() = sizeBytes?.let(::formatMegabytes)

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)
