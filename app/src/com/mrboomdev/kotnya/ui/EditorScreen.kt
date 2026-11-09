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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrboomdev.kotnya.BuildStatus
import com.mrboomdev.kotnya.IdeController
import com.mrboomdev.kotnya.ProjectInfo
import com.mrboomdev.kotnya.terminal.TermuxTerminal
import kotlinx.coroutines.launch
import java.io.File

private enum class SheetTab { Files, Terminal }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(controller: IdeController, project: ProjectInfo) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var sheetTab by remember { mutableStateOf(SheetTab.Files) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(340.dp)) {
                TabRow(selectedTabIndex = sheetTab.ordinal) {
                    Tab(
                        selected = sheetTab == SheetTab.Files,
                        onClick = { sheetTab = SheetTab.Files },
                        text = { Text("Files") },
                    )
                    Tab(
                        selected = sheetTab == SheetTab.Terminal,
                        onClick = { sheetTab = SheetTab.Terminal },
                        text = { Text("Terminal") },
                    )
                }

                when (sheetTab) {
                    SheetTab.Files -> FilesPanel(controller, project) { file ->
                        controller.openFile(file)
                        scope.launch { drawerState.close() }
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
                        TextButton(onClick = { controller.closeEditor() }) { Text("Back") }
                    },
                    actions = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("Files") }
                        TextButton(onClick = { controller.saveAll() }) { Text("Save") }
                        TextButton(
                            onClick = { controller.build() },
                            enabled = !controller.isBuilding,
                        ) { Text("Build") }
                        TextButton(
                            onClick = { controller.install() },
                            enabled = !controller.isBuilding,
                        ) { Text("Install") }
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

    ScrollableTabRow(
        selectedTabIndex = controller.activeTabIndex,
        edgePadding = 0.dp,
    ) {
        controller.tabs.forEachIndexed { index, tab ->
            Tab(
                selected = index == controller.activeTabIndex,
                onClick = { controller.selectTab(index) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.name + if (tab.isDirty) " *" else "")

                        if (tab.isDirty || index == controller.activeTabIndex) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "x",
                                modifier = Modifier.clickable { controller.closeTab(index) },
                            )
                        }
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

    val scrollState = rememberScrollState()

    Box(modifier.verticalScroll(scrollState).padding(12.dp)) {
        BasicTextField(
            value = tab.text,
            onValueChange = controller::updateActiveTabText,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = controller.editorFontSize.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FilesPanel(controller: IdeController, project: ProjectInfo, onFileClick: (File) -> Unit) {
    val files = remember(project.directory) { collectProjectFiles(project.directory) }

    if (files.isEmpty()) {
        Text(
            "This project has no editable files yet.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(files, key = { it.absolutePath }) { file ->
            Text(
                text = file.relativeTo(project.directory).path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFileClick(file) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
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

private val TEXT_EXTENSIONS = setOf(
    "kt", "kts", "java", "xml", "yaml", "yml", "toml", "md", "properties", "txt", "pro", "json",
)

private val SKIPPED_DIRECTORIES = setOf("build", ".git", ".idea", ".gradle")

private fun collectProjectFiles(root: File): List<File> = root.walkTopDown()
    .onEnter { it.name !in SKIPPED_DIRECTORIES }
    .filter { it.isFile && (it.extension in TEXT_EXTENSIONS || it.name == "kotlin") }
    .sortedBy { it.relativeTo(root).path }
    .toList()
