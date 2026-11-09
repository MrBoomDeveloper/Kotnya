package com.mrboomdev.kotnya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrboomdev.kotnya.BuildStatus
import com.mrboomdev.kotnya.BuildVariant
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.ProjectInfo
import com.mrboomdev.kotnya.terminal.TermuxTerminal
import kotlinx.coroutines.launch

private enum class SheetTab(val title: String) {
    Files("Files"),
    Build("Build"),
    Terminal("Terminal"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(controller: IdeController, project: ProjectInfo) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var sheetTab by remember { mutableStateOf(SheetTab.Files) }
    var menuExpanded by remember { mutableStateOf(false) }

    if (controller.showKeystoreDialog) {
        KeystoreDialog(controller)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(340.dp)) {
                TabRow(selectedTabIndex = sheetTab.ordinal) {
                    SheetTab.entries.forEach { tab ->
                        Tab(
                            selected = sheetTab == tab,
                            onClick = { sheetTab = tab },
                            text = { Text(tab.title) },
                        )
                    }
                }

                when (sheetTab) {
                    SheetTab.Files -> FilesPanel(controller, project) { file ->
                        controller.openFile(file)
                        scope.launch { drawerState.close() }
                    }

                    SheetTab.Build -> BuildPanel(controller) {
                        controller.openKeystoreDialog()
                    }

                    SheetTab.Terminal -> TerminalPanel(controller)
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(project.name) },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰") }
                    },
                    actions = {
                        TextButton(onClick = { controller.saveAll() }) { Text("Save") }
                        TextButton(
                            onClick = { controller.build() },
                            enabled = !controller.isBuilding,
                        ) { Text("Build") }

                        Box {
                            TextButton(
                                onClick = { menuExpanded = true },
                                enabled = !controller.isBuilding,
                            ) { Text("⋮") }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Install debug APK") },
                                    onClick = {
                                        menuExpanded = false
                                        controller.install()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign release APK") },
                                    onClick = {
                                        menuExpanded = false
                                        controller.signApk()
                                    },
                                    enabled = controller.signingConfig != null,
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Close project") },
                                    onClick = {
                                        menuExpanded = false
                                        controller.closeEditor()
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                FileTabs(controller)
                EditorPane(controller, Modifier.weight(1f).fillMaxWidth())
                BuildOutput(controller)
            }
        }
    }
}

@Composable
private fun FileTabs(controller: IdeController) {
    if (controller.tabs.isEmpty()) {
        Text(
            "Open a file from the Files sheet",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
        HorizontalDivider()
        return
    }

    var draggingKey by remember { mutableStateOf<String?>(null) }

    // LazyRow is used instead of Material's ScrollableTabRow: that component keeps an internal
    // tab-position cache and throws IndexOutOfBoundsException when tabs are closed.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(
            items = controller.tabs,
            key = { _, tab -> tab.file.absolutePath },
        ) { index, tab ->
            val currentIndex by rememberUpdatedState(index)
            val selected = index == controller.activeTabIndex
            val dragging = draggingKey == tab.file.absolutePath

            Tab(
                selected = selected,
                onClick = { controller.selectTab(currentIndex) },
                modifier = Modifier
                    .animateItem()
                    .graphicsLayer {
                        if (dragging) {
                            scaleX = 1.06f
                            scaleY = 1.06f
                            alpha = 0.92f
                            shadowElevation = 8.dp.toPx()
                        }
                    }
                    .background(
                        if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                    )
                    .pointerInput(tab.file.absolutePath) {
                        // Long-press a tab and drag it sideways to reorder the strip.
                        var dragged = 0f
                        val threshold = 64.dp.toPx()

                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragged = 0f
                                draggingKey = tab.file.absolutePath
                            },
                            onDragCancel = {
                                dragged = 0f
                                draggingKey = null
                            },
                            onDragEnd = {
                                dragged = 0f
                                draggingKey = null
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragged += amount.x

                                while (dragged > threshold) {
                                    controller.moveTab(currentIndex, currentIndex + 1)
                                    dragged -= threshold
                                }

                                while (dragged < -threshold) {
                                    controller.moveTab(currentIndex, currentIndex - 1)
                                    dragged += threshold
                                }
                            },
                        )
                    },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.name + if (tab.isDirty) " *" else "")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "×",
                            modifier = Modifier
                                .clickable { controller.closeTab(currentIndex) }
                                .padding(horizontal = 2.dp),
                        )
                    }
                },
            )
        }
    }

    HorizontalDivider()
}

@Composable
private fun EditorPane(controller: IdeController, modifier: Modifier) {
    val tab = controller.tabs.getOrNull(controller.activeTabIndex)

    if (tab == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No file open", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    CodeEditorPane(
        fileKey = tab.file.absolutePath,
        text = tab.text,
        fontSize = controller.editorFontSize,
        onTextChanged = { controller.updateTabText(tab.file, it) },
        modifier = modifier,
    )
}

@Composable
private fun BuildPanel(controller: IdeController, onGenerateKeystore: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Build variant", style = MaterialTheme.typography.titleMedium)

        BuildVariant.entries.forEach { variant ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { controller.buildVariant = variant }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = controller.buildVariant == variant,
                    onClick = { controller.buildVariant = variant },
                )

                Column(Modifier.padding(start = 8.dp)) {
                    Text(variant.title, style = MaterialTheme.typography.bodyLarge)
                    Text(variant.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = { controller.build() },
            enabled = !controller.isBuilding,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Build APK") }

        when (controller.buildVariant) {
            BuildVariant.Debug -> Button(
                onClick = { controller.install() },
                enabled = !controller.isBuilding,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Build & install debug APK") }

            BuildVariant.Release -> Button(
                onClick = { controller.signApk() },
                enabled = !controller.isBuilding && controller.signingConfig != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Build & sign release APK") }
        }

        HorizontalDivider()

        Text("Release signing", style = MaterialTheme.typography.titleMedium)

        val signing = controller.signingConfig
        if (signing == null) {
            Text(
                "No keystore yet. Generate one to build a signed release APK.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Keystore: ${signing.keystore.name}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Alias: ${signing.alias}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        OutlinedButton(onClick = onGenerateKeystore, modifier = Modifier.fillMaxWidth()) {
            Text(if (signing == null) "Generate keystore…" else "Generate another keystore…")
        }

        controller.signedApkPath?.let { path ->
            Text(
                "Signed APK: $path",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun KeystoreDialog(controller: IdeController) {
    AlertDialog(
        onDismissRequest = { controller.dismissKeystoreDialog() },
        title = { Text("Generate keystore") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "A self-signed RSA key is created in the project's .kotnya folder and used by " +
                        "the Sign release APK action.",
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    value = controller.keystoreName,
                    onValueChange = { controller.keystoreName = it },
                    label = { Text("Keystore file") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = controller.keystoreAlias,
                    onValueChange = { controller.keystoreAlias = it },
                    label = { Text("Key alias") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = controller.keystorePassword,
                    onValueChange = { controller.keystorePassword = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )

                OutlinedTextField(
                    value = controller.keystorePasswordConfirmation,
                    onValueChange = { controller.keystorePasswordConfirmation = it },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )

                OutlinedTextField(
                    value = controller.keystoreValidityYears,
                    onValueChange = { controller.keystoreValidityYears = it },
                    label = { Text("Validity (years)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Text(
                    "Passwords are stored in .kotnya/signing.properties. Keep that folder private " +
                        "and out of version control.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.generateKeystore() },
                enabled = !controller.keystoreBusy,
            ) { Text(if (controller.keystoreBusy) "Generating…" else "Generate") }
        },
        dismissButton = {
            TextButton(
                onClick = { controller.dismissKeystoreDialog() },
                enabled = !controller.keystoreBusy,
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun TerminalPanel(controller: IdeController) {
    val session = controller.terminalSession
    val error = controller.terminalError

    when {
        session != null -> TermuxTerminal(session, Modifier.fillMaxSize())

        error != null -> Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { controller.retryTerminal() }) { Text("Retry") }
        }

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Starting terminal…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BuildOutput(controller: IdeController) {
    if (controller.buildStatus == BuildStatus.Idle && controller.buildLog.isEmpty()) return

    var expanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(controller.buildLog.size) {
        if (controller.buildLog.isNotEmpty()) {
            listState.animateScrollToItem(controller.buildLog.lastIndex)
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (controller.buildStatus) {
                        BuildStatus.Running -> "Building…"
                        BuildStatus.Succeeded -> "Build succeeded"
                        BuildStatus.Failed -> "Build failed"
                        BuildStatus.Idle -> "Output"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (expanded) {
                if (controller.buildStatus == BuildStatus.Running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                ) {
                    items(controller.buildLog) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
