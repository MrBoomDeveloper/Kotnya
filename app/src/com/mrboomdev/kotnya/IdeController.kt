package com.mrboomdev.kotnya

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mrboomdev.kotnya.compiler.AndroidSdk
import com.mrboomdev.kotnya.compiler.CompileRequest
import com.mrboomdev.kotnya.compiler.CompilerEvent
import com.mrboomdev.kotnya.compiler.CompilerModule
import com.mrboomdev.kotnya.compiler.DEFAULT_TOOLCHAIN_VERSION
import com.mrboomdev.kotnya.compiler.KotlinToolchain
import com.mrboomdev.kotnya.compiler.NewProject
import com.mrboomdev.kotnya.compiler.PackageEvent
import com.mrboomdev.kotnya.compiler.ProjectTemplate
import com.mrboomdev.kotnya.compiler.SdkCatalog
import com.mrboomdev.kotnya.compiler.SdkEvent
import com.mrboomdev.kotnya.compiler.SdkPackage
import com.mrboomdev.kotnya.compiler.SdkPackages
import com.mrboomdev.kotnya.compiler.TermuxBootstrapRelease
import com.mrboomdev.kotnya.compiler.ToolchainEvent
import com.mrboomdev.kotnya.terminal.BootstrapSource
import com.mrboomdev.kotnya.terminal.ShellEvent
import com.mrboomdev.kotnya.terminal.TermuxBootstrap
import com.mrboomdev.kotnya.terminal.TermuxSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Projects : Screen
    data object NewProject : Screen
    data object Settings : Screen
    data object SdkDashboard : Screen
    data class Editor(val project: ProjectInfo) : Screen
}

/** The screens of the first-run setup. The first three components are required by the IDE. */
enum class OnboardingStep { Welcome, Termux, Java, Toolchain, Ready }

data class ProjectInfo(val name: String, val directory: File)

data class EditorTab(val file: File, val text: String, val savedText: String) {
    val name: String get() = file.name
    val isDirty: Boolean get() = text != savedText
}

enum class BuildStatus { Idle, Running, Succeeded, Failed }

/**
 * Holds the IDE state shared by all screens: projects on disk, open editor tabs, the terminal
 * session, build output, SDK setup and settings. State lives in Compose snapshot state, so screens
 * only read from it.
 */
class IdeController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val compilerModule = CompilerModule(appContext)
    val workspace = File(compilerModule.terminal.bootstrap.homeDir, "projects")

    var screen by mutableStateOf<Screen>(Screen.Projects)
        private set

    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Setup gate.
    var setupChecked by mutableStateOf(false)
        private set

    var environmentReady by mutableStateOf(false)
        private set

    // New project form.
    var projectName by mutableStateOf("")
    var packageName by mutableStateOf("com.example.hello")
    var template by mutableStateOf(ProjectTemplate.JvmConsole)

    // Editor.
    var tabs by mutableStateOf<List<EditorTab>>(emptyList())
        private set

    var activeTabIndex by mutableStateOf(0)
        private set

    var buildStatus by mutableStateOf(BuildStatus.Idle)
        private set

    var buildLog by mutableStateOf<List<String>>(emptyList())
        private set

    // Terminal.
    var terminalSession by mutableStateOf<TermuxSession?>(null)
        private set

    var terminalError by mutableStateOf<String?>(null)
        private set

    // SDK setup.
    var androidSdk by mutableStateOf<AndroidSdk?>(null)
        private set

    // First-run setup.
    var onboardingStep by mutableStateOf(OnboardingStep.Welcome.ordinal)
        private set

    var onboardingDismissed by mutableStateOf(false)
        private set

    // Version catalogue, fetched from the vendors' own indexes so nothing has to be typed in.
    var bootstrapReleases by mutableStateOf<List<TermuxBootstrapRelease>>(emptyList())
        private set

    var sdkPackages by mutableStateOf<SdkPackages?>(null)
        private set

    var catalogLoading by mutableStateOf(false)
        private set

    var catalogError by mutableStateOf<String?>(null)
        private set

    var toolchainVersion by mutableStateOf(DEFAULT_TOOLCHAIN_VERSION)

    var busyMessage by mutableStateOf<String?>(null)
        private set

    var installedToolchains by mutableStateOf<List<KotlinToolchain>>(emptyList())
        private set

    var javaRuntimeAvailable by mutableStateOf(false)
        private set

    /**
     * Snapshot of [TermuxBootstrap.isInstalled]. The bootstrap reads the file system directly, so it
     * is not observable; without this, setup screens never re-enable their buttons after installing.
     */
    var bootstrapInstalled by mutableStateOf(false)
        private set

    // Editor settings.
    var editorFontSize by mutableStateOf(13f)

    private var buildJob: Job? = null
    private var terminalProject: File? = null

    val bootstrap: TermuxBootstrap get() = compilerModule.terminal.bootstrap
    val isBuilding: Boolean get() = buildStatus == BuildStatus.Running
    val currentProject: ProjectInfo? get() = (screen as? Screen.Editor)?.project

    val deviceAbi: String get() = SdkCatalog.deviceAbi

    val installedBootstrapVersion: String? get() = bootstrap.installedVersion

    val currentOnboardingStep: OnboardingStep
        get() = OnboardingStep.entries.getOrElse(onboardingStep) { OnboardingStep.Welcome }

    /** The newest bootstrap archive published for this device's ABI. */
    val recommendedBootstrap: TermuxBootstrapRelease?
        get() = bootstrapReleases.firstOrNull { it.abi == deviceAbi } ?: bootstrapReleases.firstOrNull()

    /** The command-line tools only need the bootstrap and a Java runtime. */
    val canInstallCommandLineTools: Boolean
        get() = bootstrapInstalled && javaRuntimeAvailable && busyMessage == null

    /** sdkmanager can only be driven once the bootstrap, Java and the command-line tools exist. */
    val canInstallSdkPackages: Boolean
        get() = canInstallCommandLineTools && androidSdk?.commandLineTools != null

    init {
        refreshProjects()
        refreshSetup()
        loadCatalog()

        scope.launch {
            InstallStatusBus.messages.collect(::appendLog)
        }
    }

    // ---------------------------------------------------------------- navigation

    fun startNewProject() {
        projectName = ""
        packageName = "com.example.hello"
        template = ProjectTemplate.JvmConsole
        screen = Screen.NewProject
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    fun openSdkDashboard() {
        screen = Screen.SdkDashboard
        loadCatalog()
        refreshSetup()
    }

    fun nextOnboardingStep() {
        if (onboardingStep < OnboardingStep.entries.lastIndex) onboardingStep++
    }

    fun previousOnboardingStep() {
        if (onboardingStep > 0) onboardingStep--
    }

    /** Leaves the first-run setup once the required components are installed. */
    fun dismissOnboarding() {
        onboardingDismissed = true
        screen = Screen.Projects
    }

    fun back() {
        when (screen) {
            is Screen.Editor -> closeEditor()
            else -> screen = Screen.Projects
        }
    }

    fun clearError() {
        errorMessage = null
    }

    // ---------------------------------------------------------------- projects

    fun refreshProjects() {
        workspace.mkdirs()

        projects = workspace.listFiles()
            ?.filter { it.isDirectory && File(it, "project.yaml").isFile }
            ?.map { ProjectInfo(name = it.name, directory = it) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun createProject() {
        val name = projectName.trim()
        val packageName = packageName.trim()

        if (name.isEmpty()) {
            errorMessage = "Enter a project name."
            return
        }

        if (File(workspace, name).exists()) {
            errorMessage = "A project called '$name' already exists."
            return
        }

        scope.launch {
            try {
                val directory = withContext(Dispatchers.IO) {
                    compilerModule.projects.generate(
                        NewProject(
                            directory = File(workspace, name),
                            name = name,
                            packageName = packageName,
                            template = template,
                        )
                    )
                }

                refreshProjects()
                openProject(ProjectInfo(name, directory))
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not create the project."
            }
        }
    }

    fun openProject(project: ProjectInfo) {
        tabs = emptyList()
        activeTabIndex = 0
        buildLog = emptyList()
        buildStatus = BuildStatus.Idle
        screen = Screen.Editor(project)
        openTerminal(project.directory)
    }

    fun closeEditor() {
        closeTerminal()
        tabs = emptyList()
        activeTabIndex = 0
        buildLog = emptyList()
        buildStatus = BuildStatus.Idle
        screen = Screen.Projects
        refreshProjects()
    }

    // ---------------------------------------------------------------- editor

    fun openFile(file: File) {
        val existing = tabs.indexOfFirst { it.file == file }
        if (existing >= 0) {
            activeTabIndex = existing
            return
        }

        if (file.length() > MAX_EDITABLE_FILE_SIZE) {
            errorMessage = "${file.name} is too large to open in the editor."
            return
        }

        val text = try {
            file.readText()
        } catch (e: Exception) {
            errorMessage = "Could not open ${file.name}: ${e.message}"
            return
        }

        tabs = tabs + EditorTab(file = file, text = text, savedText = text)
        activeTabIndex = tabs.lastIndex
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) activeTabIndex = index
    }

    fun updateActiveTabText(text: String) {
        val tab = tabs.getOrNull(activeTabIndex) ?: return
        tabs = tabs.toMutableList().apply { set(activeTabIndex, tab.copy(text = text)) }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return

        tabs = tabs.toMutableList().apply { removeAt(index) }
        activeTabIndex = activeTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    }

    /** Writes dirty tabs back to disk. Returns false when a write failed. */
    fun saveAll(): Boolean {
        var saved = true

        tabs = tabs.map { tab ->
            if (!tab.isDirty) return@map tab

            try {
                tab.file.writeText(tab.text)
                tab.copy(savedText = tab.text)
            } catch (e: Exception) {
                errorMessage = "Could not save ${tab.file.name}: ${e.message}"
                saved = false
                tab
            }
        }

        return saved
    }

    // ---------------------------------------------------------------- build

    fun build() = runTask(task = "build")

    fun install() = runTask(task = "package", installApkAfterwards = true)

    private fun runTask(task: String, installApkAfterwards: Boolean = false) {
        val project = currentProject ?: return
        if (isBuilding) return
        if (!saveAll()) return

        if (!bootstrap.isInstalled) {
            errorMessage = "Install the Termux bootstrap in the SDK manager first."
            return
        }

        val toolchain = compilerModule.toolchains.find(toolchainVersion)
        if (!toolchain.isInstalled) {
            errorMessage = "Kotlin Toolchain $toolchainVersion is not installed. Install it in the SDK manager."
            return
        }

        buildLog = emptyList()
        buildStatus = BuildStatus.Running

        buildJob = scope.launch {
            try {
                compilerModule.compiler.compile(
                    CompileRequest(
                        projectDirectory = project.directory,
                        toolchain = toolchain,
                        tasks = listOf(task),
                    )
                ).collect { event ->
                    when (event) {
                        is CompilerEvent.Started -> appendLog("$ ${event.command}")

                        is CompilerEvent.Output -> appendLog(event.line)

                        is CompilerEvent.Finished -> {
                            buildStatus = if (event.isSuccess) BuildStatus.Succeeded else BuildStatus.Failed
                            appendLog(
                                if (event.isSuccess) "Finished in ${event.durationMillis} ms"
                                else "Exited with code ${event.exitCode}"
                            )
                        }

                        is CompilerEvent.Failed -> {
                            buildStatus = BuildStatus.Failed
                            appendLog(event.message)
                            event.cause?.message?.let(::appendLog)
                        }
                    }
                }

                if (installApkAfterwards && buildStatus == BuildStatus.Succeeded) {
                    installApk(project.directory)
                }
            } catch (e: Exception) {
                buildStatus = BuildStatus.Failed
                appendLog("Build failed: ${e.message}")
            }
        }
    }

    private fun installApk(projectDirectory: File) {
        val apk = projectDirectory.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile && it.extension == "apk" }
            .maxByOrNull(File::lastModified)

        if (apk == null) {
            buildStatus = BuildStatus.Failed
            appendLog("No APK was produced. Installing is only available for Android app projects.")
            return
        }

        appendLog("Installing ${apk.name}…")

        try {
            ApkInstaller(appContext).install(apk)
        } catch (e: Exception) {
            buildStatus = BuildStatus.Failed
            appendLog("Install failed: ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        buildLog = (buildLog + line).takeLast(MAX_LOG_LINES)
    }

    // ---------------------------------------------------------------- terminal

    private fun openTerminal(directory: File) {
        closeTerminal()
        terminalProject = directory

        if (!bootstrap.isInstalled) {
            terminalError = "The Termux bootstrap is not installed. Install it in the SDK manager to use the terminal."
            return
        }

        try {
            terminalSession = compilerModule.terminal.openSession(workingDirectory = directory)
        } catch (e: Exception) {
            terminalError = e.message ?: "Could not start the terminal."
        }
    }

    fun retryTerminal() {
        terminalProject?.let(::openTerminal)
    }

    private fun closeTerminal() {
        terminalSession?.close()
        terminalSession = null
        terminalError = null
        terminalProject = null
    }

    // ---------------------------------------------------------------- SDK setup

    /** Re-reads the on-device environment and decides whether the IDE may be entered. */
    fun refreshSetup() {
        scope.launch {
            val sdk = withContext(Dispatchers.IO) { compilerModule.androidSdk.status() }
            val toolchains = withContext(Dispatchers.IO) { compilerModule.toolchains.installed() }
            val javaAvailable = compilerModule.toolchains.hasJavaRuntime()

            androidSdk = sdk
            installedToolchains = toolchains
            javaRuntimeAvailable = javaAvailable
            bootstrapInstalled = bootstrap.isInstalled

            // The Android SDK is optional, so only these three components gate the IDE.
            environmentReady = bootstrapInstalled && javaAvailable && toolchains.isNotEmpty()

            if (!setupChecked) {
                onboardingStep = when {
                    environmentReady -> OnboardingStep.Ready.ordinal
                    !bootstrapInstalled && !javaAvailable && toolchains.isEmpty() ->
                        OnboardingStep.Welcome.ordinal

                    !bootstrapInstalled -> OnboardingStep.Termux.ordinal
                    !javaAvailable -> OnboardingStep.Java.ordinal
                    else -> OnboardingStep.Toolchain.ordinal
                }

                onboardingDismissed = environmentReady
            }

            setupChecked = true
        }
    }

    /** Loads the browseable version lists; nothing here has to be pasted in by the user. */
    fun loadCatalog(force: Boolean = false) {
        if (catalogLoading) return
        if (!force && bootstrapReleases.isNotEmpty() && sdkPackages != null) return

        catalogLoading = true
        catalogError = null

        scope.launch {
            val bootstraps = withContext(Dispatchers.IO) {
                runCatching { compilerModule.catalog.bootstraps() }
            }
            val packages = withContext(Dispatchers.IO) {
                runCatching { compilerModule.catalog.packages() }
            }

            bootstraps.onSuccess { bootstrapReleases = it }
            packages.onSuccess { sdkPackages = it }
            catalogError = (bootstraps.exceptionOrNull() ?: packages.exceptionOrNull())
                ?.message
                ?.takeIf(String::isNotBlank)
            catalogLoading = false
        }
    }

    /** Installs the newest Termux bootstrap published for this device. */
    fun installBootstrap() {
        val release = recommendedBootstrap
        if (release == null) {
            loadCatalog()
            errorMessage = "Still fetching the list of Termux bootstraps. Try again in a moment."
            return
        }

        installBootstrap(release)
    }

    fun installBootstrap(release: TermuxBootstrapRelease) {
        if (busyMessage != null) return

        busyMessage = "Downloading Termux bootstrap ${release.version}…"

        scope.launch {
            try {
                compilerModule.terminal.install(
                    source = BootstrapSource.Url(release.downloadUrl, release.sha256),
                    version = release.version,
                    onProgress = { progress ->
                        busyMessage = "Extracting ${progress.currentEntry ?: "bootstrap"}…"
                    },
                    onDownloadProgress = { downloaded, total ->
                        busyMessage = "Downloading Termux bootstrap: ${formatSize(downloaded, total)}"
                    },
                )
            } catch (e: Exception) {
                errorMessage = "Bootstrap installation failed: ${e.message}"
            } finally {
                // Publish the new environment before clearing the progress message so the setup
                // screen re-enables its Continue button in the same frame.
                bootstrapInstalled = bootstrap.isInstalled
                busyMessage = null
                refreshSetup()
            }
        }
    }

    fun installToolchain() {
        if (busyMessage != null) return

        busyMessage = "Installing Kotlin Toolchain $toolchainVersion…"

        scope.launch {
            compilerModule.toolchains.install(toolchainVersion).collect { event ->
                when (event) {
                    is ToolchainEvent.Downloading -> {
                        busyMessage = "Downloading Kotlin Toolchain: ${formatSize(event.bytesDownloaded, event.totalBytes)}"
                    }

                    is ToolchainEvent.Extracting -> busyMessage = "Extracting Kotlin Toolchain…"

                    is ToolchainEvent.Installed -> busyMessage = null

                    is ToolchainEvent.Failed -> {
                        busyMessage = null
                        errorMessage = event.message + (event.cause?.message?.let { ": $it" } ?: "")
                    }
                }
            }

            refreshSetup()
        }
    }

    fun installJavaRuntime() {
        if (busyMessage != null) return

        busyMessage = "Preparing the Java runtime…"

        scope.launch {
            compilerModule.toolchains.installJavaRuntime().collect { event ->
                when (event) {
                    is PackageEvent.Downloading -> {
                        busyMessage = "Downloading ${event.packageName}: ${formatSize(event.bytes, event.total)}"
                    }

                    is PackageEvent.Extracting -> busyMessage = "Extracting ${event.packageName}…"

                    is PackageEvent.Installed -> Unit

                    is PackageEvent.Failed -> {
                        busyMessage = null
                        errorMessage = "${event.packageName}: ${event.message}" +
                            (event.cause?.message?.let { " ($it)" } ?: "")
                    }
                }
            }

            busyMessage = null
            refreshSetup()
        }
    }

    fun installCommandLineTools(release: SdkPackage) {
        if (busyMessage != null) return

        val url = release.downloadUrl
        if (url == null) {
            errorMessage = "This command-line tools release has no Linux archive."
            return
        }

        busyMessage = "Downloading Android command-line tools…"

        scope.launch {
            compilerModule.androidSdk.installCommandLineTools(url).collect { event ->
                when (event) {
                    is SdkEvent.Downloading -> {
                        busyMessage = "Downloading command-line tools: ${formatSize(event.bytesDownloaded, event.totalBytes)}"
                    }

                    is SdkEvent.Extracting -> busyMessage = "Extracting Android command-line tools…"

                    is SdkEvent.Installed -> {
                        androidSdk = event.sdk
                        busyMessage = null
                    }

                    is SdkEvent.Failed -> {
                        busyMessage = null
                        errorMessage = event.message + (event.cause?.message?.let { ": $it" } ?: "")
                    }
                }
            }

            refreshSetup()
        }
    }

    /** Installs one SDK package chosen in the dashboard, e.g. `platforms;android-36`. */
    fun installSdkPackage(packageId: String, label: String = packageId) {
        if (busyMessage != null) return

        if (!javaRuntimeAvailable) {
            errorMessage = "Install the Java runtime before installing SDK packages."
            return
        }

        if (androidSdk?.commandLineTools == null) {
            errorMessage = "Install the Android command-line tools first."
            return
        }

        busyMessage = "Installing $label…"

        scope.launch {
            compilerModule.androidSdk.installPackage(packageId).collect { event ->
                when (event) {
                    is ShellEvent.Started -> Unit
                    is ShellEvent.Output -> busyMessage = "Installing $label: ${event.line}"
                    is ShellEvent.Finished -> busyMessage = null
                    is ShellEvent.Failed -> {
                        busyMessage = null
                        errorMessage = "Could not install $label: ${event.message}"
                    }
                }
            }

            refreshSetup()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun dispose() {
        buildJob?.cancel()
        closeTerminal()
        scope.cancel()
    }

    private fun formatSize(bytes: Long, total: Long?): String {
        val downloaded = "%.1f MB".format(bytes / 1024.0 / 1024.0)
        return if (total != null && total > 0) "$downloaded / ${"%.1f MB".format(total / 1024.0 / 1024.0)}" else downloaded
    }

    private companion object {
        const val MAX_LOG_LINES = 2_000
        const val MAX_EDITABLE_FILE_SIZE = 512L * 1024L
    }
}
